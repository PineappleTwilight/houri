// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import ca.mpreg.webgpuviewer.ImageView
import ca.mpreg.webgpuviewer.filter.FilterBrightnessContrast
import ca.mpreg.webgpuviewer.filter.FilterGrayscale
import ca.mpreg.webgpuviewer.filter.FilterHlg
import ca.mpreg.webgpuviewer.filter.FilterLut3d
import ca.mpreg.webgpuviewer.reader.OnReaderStateChanged
import ca.mpreg.webgpuviewer.reader.PageAnchor
import ca.mpreg.webgpuviewer.reader.ReaderState
import ca.mpreg.webgpuviewer.reader.SettingsImpact
import ca.mpreg.webgpuviewer.renderer.UpscalerArtCnn
import ca.mpreg.webgpuviewer.viewer.ImagePage
import com.google.android.material.color.MaterialColors
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.concurrency.AppDispatchersHolder
import tachiyomi.core.common.util.system.logcat
import java.util.TreeSet
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/** Edge pages of an adjacent chapter to reserve shells for up front (see preloadChapterThenRetry). */
private const val CHAPTER_EDGE_PRELOAD = 4

/**
 * Smallest width a page or placeholder may claim.
 *
 * The GPU rejects a texture narrower than this (gralloc 0x3b), and a placeholder that reserved less
 * would divide by zero in the image page's layout maths before it ever got that far.
 */
internal const val MIN_PAGE_WIDTH = 8

/**
 * Width a page or placeholder claims for itself; see [WebGpuViewer.viewportPageWidth].
 *
 * Pure, so the floor and the halving are checkable without a surface attached - which is the only
 * state these pages are ever built in.
 */
internal fun resolveViewportPageWidth(viewportWidth: Int, half: Boolean): Int {
    if (viewportWidth < MIN_PAGE_WIDTH) return MIN_PAGE_WIDTH
    return if (half) {
        (viewportWidth / 2).coerceAtLeast(MIN_PAGE_WIDTH)
    } else {
        viewportWidth.coerceAtLeast(MIN_PAGE_WIDTH)
    }
}

/**
 * The WebGPU paged reader.
 *
 * This class is the viewer's *wiring* and nothing else: the fields it owns, how the pager's
 * callbacks reach the page graph, how a chapter swap is applied, and how the reader navigates.
 * Everything with a state machine or a retry ladder lives beside it, one concern per file:
 *
 * | File | Concern |
 * |---|---|
 * | [WebGpuPageModel] | the page graph itself - `ViewerPage`, its split-aware neighbour links |
 * | [WebGpuPageCache] | which pages are resident, what gets evicted, and split reconciliation |
 * | [WebGpuDecodeQueue] | what the decode worker owes a page, and how a stuck page is retried |
 * | [WebGpuDecodePipeline] | bytes to GPU image for one page |
 * | [WebGpuViewerWorkers] | the three long-lived coroutines |
 * | [WebGpuViewerImageState] | preferences to filters, transition, zoom, HUD |
 * | [WebGpuSpread] / [WebGpuSpreadHeightMatch] | dual-page pairing and height matching |
 * | [WebGpuZoom] / [WebGpuExif] | per-page presentation applied after every decode |
 */
open class WebGpuViewer(
    val activity: ReaderActivity,
    val isReversed: Boolean,
    override val isVertical: Boolean,
    val pager: ImageView = ImageView(activity, isVertical = isVertical, isReversed = isReversed),
) : Viewer {

    private val positionStore by lazy { WebGpuReadingPositionStore(activity) }

    // KMK -->
    // internal: the deferred restores arm and clear these from WebGpuRestore.kt, and the guard in
    // reportPageSelected below depends on both being cleared the moment the restore ends.
    internal var pendingContinuousRestoreChapterId: Long? = null
    internal var pendingPagedRestoreChapterId: Long? = null

    /** Last Ready anchor from the viewer state; drive progress/save display from this. */
    @Volatile
    var currentAnchor: PageAnchor = PageAnchor(pageIndex = 0)
        private set
    // KMK <--

    open val isContinuous: Boolean = false

    val readerPreferences by lazy { globalAppGraph.readerPreferences }
    internal val translationManager by lazy {
        try {
            globalAppGraph.translationManager
        } catch (_: Exception) {
            null
        }
    }

    // KMK -->
    /** Resolved once: render() asks per frame, and createReaderThemeContext builds Resources. */
    @Volatile
    internal var cachedBackgroundColor: Int? = null

    @Volatile
    internal var cachedOnBackgroundColor: Int? = null
    // KMK <--

    // KMK -->
    internal val darkModeFilter = WebGpuDarkModeFilter()

    internal val brightnessContrastFilter = FilterBrightnessContrast()

    internal val hlgFilter = FilterHlg()

    internal val lutFilter = FilterLut3d()

    internal val einkGrayscaleFilter = FilterGrayscale(saturation = 1f)

    @Volatile
    internal var appliedLutKey: String? = null

    @Volatile
    internal var lutResolveGeneration = 0

    @Volatile
    internal var perfHudView: TextView? = null

    @Volatile
    internal var perfHudLastUpdate = 0L
    // KMK <--

    // KMK -->
    internal var artCnnUpscaler: UpscalerArtCnn? = null
    // KMK <--

    internal fun readerBackgroundColor(): Int =
        cachedBackgroundColor ?: activity.baseContext.readerBackgroundColor(config.theme)
            .also { cachedBackgroundColor = it }

    internal fun readerOnBackgroundColor(): Int = cachedOnBackgroundColor ?: MaterialColors.getColor(
        activity.createReaderThemeContext(),
        com.google.android.material.R.attr.colorOnBackground,
        Color.WHITE,
    ).also { cachedOnBackgroundColor = it }

    internal val scope = MainScope()

    @Volatile
    internal var isDestroyed = false

    // Dedicated thread for decode worker to avoid blocking Dispatchers.Default pool
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WebGpuViewer-Decode").apply { isDaemon = true }
    }
    internal val decodeDispatcher = decodeExecutor.asCoroutineDispatcher()

    // Single lock for all page cache and queue operations
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    internal val lock = Object()

    // Page cache - keyed by stable PageKey for O(1) lookup
    internal val pageCache = LinkedHashMap<PageKey, ViewerPage>()

    /**
     * How the liveness sweep has had to recover each page, keyed like [pageCache] so a shell that is
     * torn down and rebuilt cannot reset its own record by being replaced.
     *
     * Deliberately viewer-level rather than a field on the shell: the escalation this drives
     * rebuilds the page, and a counter living on the shell would start over on the new one, which is
     * precisely the loop it exists to stop.
     */
    internal val stuckRecords = HashMap<PageKey, StuckPageRecord>()

    // Decode queue - pages waiting to be decoded, processed LIFO (last = highest priority)
    internal val decodeQueue = ArrayDeque<ViewerReaderPage>()

    /**
     * Requests a stuck-page sweep. Conflated, so signalling during a burst of evictions costs one
     * scan. Emitted from the sites that can strand a page in an in-flight state without work behind
     * it; see [startStuckPageSweep].
     */
    internal val stuckSignal = Channel<Unit>(Channel.CONFLATED)

    // KMK -->

    /**
     * How many times each page has been re-queried at its source, keyed like [pageCache] - see
     * [RequeryRecord] for why this cannot live on the shell.
     */
    internal val requeryRecords = HashMap<PageKey, RequeryRecord>()

    /**
     * The page the decode worker is holding, published so the watchdog can report a stall from
     * outside the worker. A worker stuck in native code cannot report itself, which is the entire
     * reason this field exists.
     */
    @Volatile
    internal var activeDecode: DecodeTrace? = null

    private val chapterPreloadGuard = ChapterPreloadGuard()
    // KMK <--

    /**
     * Indices of the pages that take a spread to themselves, by chapter - see [spreadStartIndex].
     * Outlives [pageCache]: every page after one of these depends on it, long since evicted.
     */
    internal val loneIndices = HashMap<Long?, TreeSet<Int>>()

    /** Above this, an untagged page is a spread already, not half of one. */
    internal val wideAspect = 1.2f

    /** How far two untagged pages' aspect ratios may differ and still pair. */
    private val pairAspectTolerance = 0.1f

    /** Read live: these pages are built before the surface has a size, and outlive a rotation. */
    internal fun viewportPageWidth(half: Boolean): Int = resolveViewportPageWidth(readViewportWidth(), half)

    /**
     * The width a placeholder reserves for itself.
     *
     * A placeholder has no decoded size, so it claims the width a real page of this slot would take:
     * the whole viewport, or half of it for a spread side. The floor matters because these pages are
     * built before the surface has a size - a zero width would divide by zero in the image page's
     * own layout maths - and it outlives a rotation, when the width it captured is long stale.
     */
    private fun readViewportWidth(): Int = try {
        pager.state.width
    } catch (_: Exception) {
        0
    }

    private val anchorPosition get() = if (isReversed xor config.invertDoublePages) SpreadPosition.RIGHT else SpreadPosition.LEFT

    private val partnerPosition get() = if (isReversed xor config.invertDoublePages) SpreadPosition.LEFT else SpreadPosition.RIGHT

    /**
     * Which half a page falls on when nothing tags the file: alternating from its spread's start,
     * anchor then partner. SINGLE outside dual page mode, so nothing pairs while one page fills
     * the viewer.
     */
    internal fun derivedSpreadPosition(page: ReaderPage): SpreadPosition {
        if (!isDualPageMode()) return SpreadPosition.SINGLE
        if (page.splitSegment) return SpreadPosition.SINGLE
        val offset = page.index - spreadStartIndex(page.chapter.chapter.id, page.index)
        return if (offset >= 0 && offset % 2 == 0) anchorPosition else partnerPosition
    }

    /**
     * Where the spread holding [index] starts: just past the last page before it that took one to
     * itself, so the page after a detected spread opens the next one instead of inheriting a parity
     * that page broke. Defaults to 1 - page 0 is the cover, and pairs with nothing.
     */
    private fun spreadStartIndex(chapterId: Long?, index: Int): Int {
        val base = synchronized(lock) { loneIndices[chapterId]?.lower(index) }?.plus(1) ?: 1
        // Shifted pairing starts one page earlier, so the cover pairs instead
        // of standing solo — the WebGPU equivalent of the legacy pager's
        // shift button. Lone-page segmentation is preserved either way.
        return if (config.shiftDoublePage) (base - 1).coerceAtLeast(0) else base
    }

    /** Registers whether [page] stands alone, for [spreadStartIndex]. Must hold [lock]. */
    internal fun noteIfLone(page: ViewerReaderPage) {
        if (page.page.splitSegment) return
        val indices = loneIndices.getOrPut(page.page.chapter.chapter.id) { TreeSet() }
        if (page.standsAlone) indices.add(page.page.index) else indices.remove(page.page.index)
    }

    /**
     * Whether these two may share a spread, beyond their positions agreeing. Both tagged is taken
     * as read; a pair resting on page order needs the same shape - halves of one sheet scan alike.
     * Undecoded pairs anyway, or a loading page draws its ring mid-screen.
     */
    internal fun canPairShapes(anchor: ViewerReaderPage, partner: ViewerReaderPage): Boolean {
        if (anchor.taggedSpreadPosition != null && partner.taggedSpreadPosition != null) return true
        val a = anchor.aspectRatio ?: return true
        val b = partner.aspectRatio ?: return true
        return abs(a - b) <= pairAspectTolerance
    }

    internal fun findInCache(key: PageKey): ViewerPage? = pageCache[key]

    /** Check if a page is in the cache by identity. O(1) via key lookup. */
    internal fun pageInCache(page: ViewerPage): Boolean = pageCache[pageKey(page)] === page

    // KMK --> Resolved once: decodeReaderPage runs per page on the decode thread.
    internal val isLowRamDevice: Boolean by lazy {
        try {
            eu.kanade.tachiyomi.util.system.DeviceUtil.isLowRamDevice(activity)
        } catch (_: Exception) {
            false
        }
    }
    // KMK <--

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = WebGpuConfig(this, scope, readerPreferences)

    // Read from the render and decode threads, via the prevChapter/nextChapter getters.
    @Volatile
    var viewerChapters: ViewerChapters? = null

    val pages: List<ReaderPage>? get() = (currentPage as? ViewerReaderPage)?.page?.chapter?.pages

    /** Mirrors [currentPage] so a coroutine can suspend on it instead of polling. */
    internal var currentPageFlow = MutableStateFlow<ViewerPage?>(null)

    /** The page the reader is on. Mirrored into [currentPageFlow] so observers can suspend on it. */
    @Volatile
    var currentPage: ViewerPage? = null
        set(value) {
            field = value
            // Every writer already sets this under the viewer's lock, so mirroring here keeps the
            // flow and the field consistent without each call site having to do both.
            currentPageFlow.value = value
            // The render window is anchored on this page, so a change of anchor invalidates it.
            if (continuousPageWindow.anchor !== value) continuousPageWindow.reset(value)
        }

    /**
     * The per-anchor window the continuous render walk resolves through, and the watch that notices
     * a loader replacing a chapter's page list mid-session. Both are state, not logic, so they live
     * here with the rest of it - see `ContinuousPageWindow` and `PageListWatch`.
     */
    internal val continuousPageWindow = ContinuousPageWindow(CONTINUOUS_PAGE_CACHE_RADIUS)
    internal val pageListWatch = PageListWatch()

    // KMK --> User-tunable preload window; continuous takes max() with live reach.
    open val preloadAhead get() = config.preloadAhead
    open val preloadBehind get() = config.preloadBehind
    // KMK <--

    /**
     * Everything [preloadPages] reaches, plus slack. Sized exactly, a chapter transition page - or
     * in dual mode a spread partner - evicts a page the next fetch asks for, and it decodes again.
     */
    open val cacheSize get() = 1 + preloadAhead + preloadBehind + if (isDualPageMode()) 3 else 1

    // KMK -->
    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() = shrinkCacheOnTrim()

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onTrimMemory(level: Int) {
            // KMK --> Screen-off is TRIM_MEMORY_UI_HIDDEN (20), and this guard used to require
            // MODERATE (60) - so every sleep held the whole decoded working set. The numeric
            // order is not the urgency order: RUNNING_CRITICAL (15) matters more than UI_HIDDEN
            // (20), so a single `>=` cannot express this set.
            if (
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            ) {
                shrinkCacheOnTrim()
            }
            // KMK <--
        }
    }
    // KMK <--

    private val deviceLostListener =
        ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.Companion.DeviceLostListener { _, _ ->
            if (isDestroyed) return@DeviceLostListener
            // KMK --> Counted on the loss itself, not on a successful recovery: a device loss
            // on a phone is usually the app being backgrounded and the GPU going away, and
            // recovery routinely succeeds either way, so waiting for success would under-count.
            runCatching { mihon.app.di.globalAppGraph.achievementManager.incrementCounter("webgpu_rescues") }
            scope.launch {
                try {
                    val recovered = pager.state.recoverFromDeviceLoss()
                    if (!recovered) {
                        logcat(LogPriority.ERROR) { "WebGPU device lost and recovery failed" }
                    }
                    resetDecodedPagesAfterDeviceLoss()
                    try {
                        pager.state.invalidate()
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                }
            }
        }

    init {
        // KMK --> Shed off-screen decoded pages on system memory pressure.
        try {
            activity.registerComponentCallbacks(trimCallbacks)
        } catch (_: Exception) {}
        // KMK <--
        try {
            ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.addDeviceLostListener(deviceLostListener)
        } catch (_: Exception) {}
        // KMK --> Populates the decode path's extension compartments before the worker that consumes
        // them starts. Installing here rather than lazily means no page can decode through a chain
        // that is missing its upscaler, and install-by-id makes a second viewer's call a no-op.
        installBuiltinWebGpuExtensions()
        startDecodeWorker()
        startStuckPageSweep()
        // KMK --> Drives the live spin of the ProgressPage pineapple while a page is loading.
        startProgressSpinner()
        // KMK -->
        // KMK --> Reports a decode stage that never finishes. Separate from the worker on purpose:
        // the worker is what gets stuck, so it cannot be the thing that notices.
        startDecodeStallWatchdog()
        // KMK <--
    }

    init {
        pager.state.apply {
            // KMK --> Feed currentAnchor from coalesced Ready emissions (Idle/Released ignored).
            onReaderStateChanged = OnReaderStateChanged { state ->
                if (state is ReaderState.Ready) currentAnchor = state.anchor
            }
            // KMK <--
            fetchPage = { index -> resolveFetchedPage(index) }

            onTap = { offset ->
                val current = currentPage as? ViewerReaderPage
                if (current != null && current.imagePage is ErrorPage) {
                    // KMK -->
                    // Tap on an error page retries the decode/load.
                    synchronized(lock) {
                        current.imagePage.cleanup()
                        current.imagePage = ProgressPage(this@WebGpuViewer)
                        current.state = PageState.IDLE
                    }
                    queueForDecode(current, prioritize = true)
                    pager.state.invalidate()
                    // KMK <--
                } else {
                    when (config.navigator.getAction(PointF(offset.x, offset.y))) {
                        NavigationRegion.MENU -> activity.toggleMenu()
                        NavigationRegion.NEXT -> if (isReversed) moveToPrevious() else moveToNext()
                        NavigationRegion.PREV -> if (isReversed) moveToNext() else moveToPrevious()
                        NavigationRegion.RIGHT -> if (isReversed) moveLeft() else moveRight()
                        NavigationRegion.LEFT -> if (isReversed) moveRight() else moveLeft()
                    }
                }
            }

            onLongTap = { _ ->
                if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                    (currentPage as? ViewerReaderPage)?.let { activity.onPageLongTap(it.page) }
                }
            }
        }

        // KMK --> Single settings channel: SettingsDiff.highest picks rebuild vs live.
        config.onSettingsChanged = listener@{ diff ->
            if (isDestroyed) return@listener
            pager.state.doubleTapZoomEnabled = config.resolveDoubleTapZoom()

            if (diff.previous.doubleTapZoom != diff.current.doubleTapZoom ||
                diff.previous.disableZoomIn != diff.current.disableZoomIn
            ) {
                synchronized(lock) {
                    if (isDestroyed) return@listener
                    pageCache.values.toList().forEach { page ->
                        (page as? ViewerReaderPage)?.let { readerPage ->
                            (readerPage.imagePage as? ImagePage.ImageSingle)?.let { applyDoubleTapZoomPolicy(it) }
                            readerPage.spreadPage?.let { spread ->
                                (spread.left as? ImagePage.ImageSingle)?.let { applyDoubleTapZoomPolicy(it) }
                                (spread.right as? ImagePage.ImageSingle)?.let { applyDoubleTapZoomPolicy(it) }
                            }
                        }
                    }
                }
            }

            if (diff.highest >= SettingsImpact.REDECODE) {
                applyImageState()
                rebuildPageCache()
            } else {
                applyImageState()
                try {
                    applyPageOffset()
                } catch (_: Exception) {
                }
                try {
                    pager.state.invalidate()
                } catch (_: Exception) {
                }
            }
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        pager.state.doubleTapZoomEnabled = config.resolveDoubleTapZoom()
        pager.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPageOffset()
            syncPerfHud()
        }
        scope.launch {
            try {
                readerPreferences.webgpuPageOffset().changes().collect { applyPageOffset() }
            } catch (_: Exception) {}
        }
        applyPageOffset()
        applyImageState()
        // KMK <--
    }

    /**
     * Throws away every decoded page and re-resolves the current one - the response to a preference
     * that changes what a decode produces.
     */
    private fun rebuildPageCache() {
        synchronized(lock) {
            if (isDestroyed) return
            decodeQueue.clear()
            // Snapshot to avoid ConcurrentModification if cleanup triggers callbacks
            val snapshot = pageCache.values.toList()
            snapshot.forEach { page ->
                // A transition page owns only its own placeholder; a reader page owns the spread,
                // the rescale and the translation pair as well, so it goes through the one owner.
                if (page is ViewerReaderPage) {
                    releasePageResources(page)
                } else {
                    page.state = PageState.IDLE
                    runCatching { page.imagePage.cleanup() }
                }
            }
            pageCache.clear()
            stuckRecords.clear()
            loneIndices.clear()
            continuousPageWindow.reset(null)

            currentPage = (currentPage as? ViewerReaderPage)?.page?.let { getPage(it) }
                ?: (currentPage as? ViewerTransitionPage)?.let {
                    getPage(it.prevChapter, it.nextChapter)
                }

            currentPage?.let { preloadPages(it) }
        }

        try {
            pager.state.invalidate()
        } catch (_: Exception) {
        }
    }

    /**
     * Kicks off loading [chapter] and, once its pages actually show up, re-runs
     * [preloadPages] from the current page - [ReaderActivity]'s viewModel.preload isn't
     * guaranteed to have finished loading by the time it returns, so a single immediate
     * retry can race it and silently never queue the adjacent chapter's edge page for
     * decode. Gives up after 5 seconds if the chapter never finishes loading.
     *
     * Guarded by [chapterPreloadGuard]: this method is reached through the prev/next
     * getters, which the pager library re-evaluates on every render snapshot and gesture
     * frame while nearing a boundary. Unguarded, each hit spawned its own preload +
     * polling cycle - many concurrent ChapterLoader runs and preload walks that showed
     * up as freezing/choppiness at chapter transitions.
     */
    internal fun preloadChapterThenRetry(chapter: ReaderChapter) {
        if (isDestroyed) return
        val key = chapter.chapter.url.takeIf { it.isNotBlank() } ?: "chapter-${chapter.chapter.id}"
        // Reserve placeholder ProgressPage shells immediately so contentHeight reflects true length and scroll doesn't wrap
        val pages = chapter.pages
        // Reference eviction from the page the user is actually reading. Without this, getPage
        // falls back to using the newly created shell as the eviction reference and background
        // preload evicts the current chapter's pages - including the page on screen - which
        // reverts the reader to a loading screen (black flash) until the next chapter is decoded.
        val evictionReference = currentPage
        // Only reserve the edge window: creating a shell for every page of the
        // adjacent chapter blows the small page cache and evicts the pages being
        // read, which then redraw as placeholders (flicker). Four covers the
        // continuous reach, the pager preload window, and the transition page.
        val edgePages = when {
            pages == null -> emptyList()
            chapter === viewerChapters?.prevChapter -> pages.takeLast(CHAPTER_EDGE_PRELOAD)
            else -> pages.take(CHAPTER_EDGE_PRELOAD)
        }
        for (pg in edgePages) {
            try {
                val shell = getPage(pg, evictionReference)
                preloadPage(shell, prioritize = false)
            } catch (_: Exception) {}
        }
        if (!chapterPreloadGuard.tryBegin(key)) {
            // If already in-flight but decodeQueue no longer contains its edge page, allow requeue (stale guard).
            // The queue probe is chapter-qualified: bare index matching collides across chapters
            // (every chapter has an index 0..3), which read the guard as stale on every boundary
            // approach and refired the whole preload cycle in a loop.
            val isStale = edgePages.firstOrNull()?.let { pg ->
                val k = PageKey.Reader(chapter.chapter.id, pg.index)
                val chapterId = chapter.chapter.id
                synchronized(lock) {
                    findInCache(k) == null ||
                        decodeQueue.none { it.page.chapter.chapter.id == chapterId && it.page.index == pg.index }
                }
            } ?: false
            if (!isStale) return
            chapterPreloadGuard.end(key)
            if (!chapterPreloadGuard.tryBegin(key)) return
        }

        scope.launch(AppDispatchersHolder.get().default) {
            try {
                if (isDestroyed) return@launch
                activity.viewModel.preload(chapter)
                repeat(100) {
                    if (isDestroyed) return@launch
                    if (chapter.state is ReaderChapter.State.Loaded) {
                        val loadedPages = chapter.pages
                        val loadedEdgePages = when {
                            loadedPages == null -> emptyList()
                            chapter === viewerChapters?.prevChapter -> loadedPages.takeLast(CHAPTER_EDGE_PRELOAD)
                            else -> loadedPages.take(CHAPTER_EDGE_PRELOAD)
                        }
                        for (pg in loadedEdgePages) {
                            try {
                                val shell = getPage(pg, evictionReference)
                                preloadPage(shell, prioritize = false)
                            } catch (_: Exception) {}
                        }
                        chapterPreloadGuard.end(key)
                        currentPage?.let { if (!isDestroyed) preloadPages(it) }
                        return@launch
                    }
                    delay(50.milliseconds)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Chapter preload retry failed: $key" }
            } finally {
                chapterPreloadGuard.end(key)
            }
        }
    }

    override fun destroy() {
        try {
            (currentPage as? ViewerReaderPage)?.let { reportPageSelected(it, force = true) }
        } catch (_: Exception) {}
        synchronized(lock) {
            if (isDestroyed) return
            isDestroyed = true
        }
        config.onSettingsChanged = null
        config.navigationModeChangedListener = null
        // KMK -->
        try {
            pager.state.onReaderStateChanged = null
        } catch (_: Exception) {}
        // KMK <--
        try {
            ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.profilingEnabled = false
        } catch (_: Exception) {}
        try {
            perfHudView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        } catch (_: Exception) {}
        perfHudView = null
        // KMK -->
        try {
            activity.unregisterComponentCallbacks(trimCallbacks)
        } catch (_: Exception) {}
        try {
            ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.removeDeviceLostListener(deviceLostListener)
        } catch (_: Exception) {}
        // KMK <--
        try {
            scope.cancel()
        } catch (_: Exception) {
        }

        try {
            decodeExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            decodeDispatcher.close()
        } catch (_: Exception) {
        }

        // KMK --> The shared pineapple spinner texture outlives pages (cached per
        // GPU device in ProgressPage); destroy it here so it never leaks the
        // old device across viewer teardown.
        try {
            ProgressPage.destroyPineappleTexture()
        } catch (_: Exception) {
        }
        // KMK <--
        chapterPreloadGuard.clear()
        synchronized(lock) {
            decodeQueue.clear()
            val snapshot = pageCache.values.toList()
            snapshot.forEach { page ->
                // A transition page owns only its own placeholder; a reader page owns the spread,
                // the rescale and the translation pair as well, so it goes through the one owner.
                if (page is ViewerReaderPage) {
                    releasePageResources(page)
                } else {
                    page.state = PageState.IDLE
                    runCatching { page.imagePage.cleanup() }
                }
            }
            pageCache.clear()
            stuckRecords.clear()
            loneIndices.clear()
            continuousPageWindow.reset(null)
            try {
                lock.notifyAll()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View = pager

    // KMK -->
    /**
     * Retry translating the currently displayed page after a failure (or when the
     * user explicitly requests it). Reads the source bytes again from the page stream
     * and re-runs the translation pipeline.
     */
    override fun retryTranslation() {
        retryCurrentPageTranslation()
    }

    fun retryCurrentPageTranslation() {
        if (isDestroyed) return
        val mgr = translationManager ?: return
        if (!mgr.isEnabled() || mgr.isGated()) return
        val page = currentPage as? ViewerReaderPage ?: return
        if (page.isDecoded) {
            runPageDecodedExtensions(page, page.sourceBytes())
        }
    }
    // KMK <--

    /**
     * Reports the active [page] to the activity. When the page forms a spread in dual-page mode,
     * marks it as having an extra page so the counter shows "N-N+1" instead of just "N".
     */
    private fun reportPageSelected(page: ViewerReaderPage, force: Boolean = false) {
        val hasExtraPage = isDualPageMode() && canFormSpread(page)
        activity.onPageSelected(page.page, hasExtraPage)
        try {
            val cid = page.page.chapter.chapter.id
            if (cid != null && cid != -1L) {
                // KMK --> A deferred resume restore is pending for this chapter: the
                // viewport is not there yet, so saving now would clobber it with 0.
                if (isContinuous && pendingContinuousRestoreChapterId == cid) return
                if (!isContinuous && pendingPagedRestoreChapterId == cid) return
                // KMK <--
                // Live position, not Page.index: an anchor is restored with
                // coerceIn(0, pages.lastIndex), so a split segment's out-of-range index would
                // clamp to the chapter's last page and resume the reader at the end. This is what the
                // running session and the progress display are measured against.
                val position = page.page.chapter.positionOf(page.page)
                if (position < 0) return
                pager.state.seedPageIndex(position)
                val anchor = try {
                    pager.state.captureAnchor().copy(pageIndex = position)
                } catch (_: Exception) {
                    PageAnchor(pageIndex = position)
                }
                currentAnchor = anchor
                // Stored in the coordinates the chapter has on the next open instead: the loader
                // folds the segments away when it persists the page list, so a live position names a
                // later page once that has happened. Only the copy on disk is converted - the live
                // anchor above stays in the list the reader is actually paging through.
                val saved = page.page.chapter.savedIndexOf(page.page)
                positionStore.saveAnchor(
                    cid,
                    if (saved >= 0) anchor.copy(pageIndex = saved) else anchor,
                    force = force,
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * Tells this viewer to set the given [chapters] as active. Mirrors
     * [eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer.setChapters] for modular parity.
     */
    override fun setChapters(chapters: ViewerChapters) = setChaptersInternal(chapters)

    private fun pageBelongsToChapters(page: ViewerPage, chapters: ViewerChapters): Boolean = when (page) {
        is ViewerReaderPage ->
            page.page.chapter == chapters.prevChapter ||
                page.page.chapter == chapters.currChapter ||
                page.page.chapter == chapters.nextChapter
        is ViewerTransitionPage ->
            page.prevChapter == chapters.prevChapter || page.prevChapter == chapters.currChapter ||
                page.prevChapter == chapters.nextChapter || page.nextChapter == chapters.prevChapter ||
                page.nextChapter == chapters.currChapter || page.nextChapter == chapters.nextChapter
        else -> false
    }

    private fun setChaptersInternal(chapters: ViewerChapters) {
        // KMK --> Empty too: lastIndex would be -1, and the requested page is read from it.
        val pages = chapters.currChapter.pages
        if (pages.isNullOrEmpty()) return
        // KMK <--

        this.viewerChapters = chapters

        // Baseline for syncPageList: it exists to catch pages replaced after this point, and
        // without recording the current versions the initial load reads as a change.
        pageListWatch.reset(chapters.pageListVersions())

        val chapterId = chapters.currChapter.chapter.id
        val stored = if (chapterId != null && chapterId != -1L) positionStore.load(chapterId) else null
        val targetIndex = stored?.pageIndex?.coerceIn(0, pages.lastIndex)
            ?: min(chapters.currChapter.requestedPage, pages.lastIndex)
        val requestedPage = pages[targetIndex]

        // Get the page and align to spread anchor if needed
        // Captured before reassignment: a non-null previous page already inside the
        // new chapters means seamless scroll entry (see restore guard below). A stale
        // page from an unrelated chapter (explicit jump before moveToPage lands)
        // resolves null neighbors in every direction, so only a linked page is reused.
        val previousPage = currentPage
        val page = previousPage?.takeIf { pageBelongsToChapters(it, chapters) }
            ?: getPage(requestedPage)
        currentPage = getSpreadAnchor(page)
        // KMK --> Seamless scroll entry: the user is already reading inside the new
        // chapter (previousPage resolved there via onPageChange before the chapter
        // switch landed). Restoring the stored offset now would yank the viewport
        // to a stale position. Fresh and explicit opens still restore below.
        val alreadyInsideNewChapter =
            (previousPage as? ViewerReaderPage)?.page?.chapter == chapters.currChapter
        val needsDeferredRestore = stored != null && isContinuous && !alreadyInsideNewChapter
        // KMK --> Arm before reporting: the report below must not save docY=0 over
        // the stored resume the restore is about to apply.
        pendingContinuousRestoreChapterId = if (needsDeferredRestore) chapterId else null
        // KMK <--
        // KMK --> Paged parity: a stored zoom/offset must survive a process kill
        // the same way continuous documentY does. Armed here so the report below
        // cannot clobber it with the fresh 1f default before restore lands.
        val needsPagedRestore = stored != null && !isContinuous && !alreadyInsideNewChapter &&
            (stored.zoom != 1f || stored.offsetX != 0f)
        pendingPagedRestoreChapterId = if (needsPagedRestore) chapterId else null
        // KMK <--
        // KMK --> Report the spread's lastmost page, not the anchor.
        progressPage(currentPage!!)?.let { reportPageSelected(it) }
        // KMK <--
        preloadPages(currentPage!!)
        // needsDeferredRestore already implies stored != null; takeIf keeps that
        // in one place and gives the restore coroutine a smart-cast value.
        val deferredStored = stored?.takeIf { needsDeferredRestore }
        if (deferredStored != null) restoreContinuousAfterDecode(chapterId, targetIndex, deferredStored)
        // KMK --> Paged parity: see restorePagedZoomAfterDecode.
        restorePagedZoomAfterDecode(chapterId, targetIndex, needsPagedRestore, stored)

        pager.state.apply {
            onPageChange = onPageChange@{ delta ->
                activity.hideMenu()

                // The viewer already showed the page at fetchPage(delta).
                // We need to update currentPage to match that.
                val current = currentPage ?: return@onPageChange

                // Navigate the same way fetchPage does
                var page = current
                val step = if (delta > 0) 1 else -1
                repeat(abs(delta)) {
                    page = nextPage(page, step) ?: return@onPageChange
                }

                currentPage = page
                // KMK --> Report the spread's lastmost page, not the anchor.
                progressPage(page)?.let { reportPageSelected(it) }
                // KMK <--
                preloadPages(page)

                (page as? ViewerTransitionPage)?.let { viewerTransitionPage ->
                    if (viewerTransitionPage.prevChapter == null || viewerTransitionPage.nextChapter == null) {
                        activity.showMenu()
                    }
                }
            }

            invalidate()
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     * In dual page mode, aligns to the start of the spread containing the page.
     */
    override fun moveToPage(page: ReaderPage) {
        // Get the page and align to spread anchor based on image position
        moveToPage(getSpreadAnchor(getPage(page)))
    }

    internal fun moveToPage(newPage: ViewerPage) {
        val previousPage = currentPage

        currentPage = newPage
        // KMK --> Report the spread's lastmost page, not the anchor.
        progressPage(newPage)?.let { reportPageSelected(it) }
        // KMK <--
        preloadPages(newPage)

        (newPage as? ViewerTransitionPage)?.let { viewerTransitionPage ->
            if (viewerTransitionPage.prevChapter == null || viewerTransitionPage.nextChapter == null) {
                activity.showMenu()
            }
        }

        if (previousPage == null) {
            invalidatePager()
            return
        }

        val direction = when (previousPage) {
            is ViewerReaderPage if newPage is ViewerReaderPage -> if (previousPage.page.chapter ==
                newPage.page.chapter
            ) {
                (newPage.page.index - previousPage.page.index).coerceIn(-1, 1)
            } else if (previousPage.page.chapter == newPage.prevChapter) {
                1
            } else {
                -1
            }

            is ViewerTransitionPage if newPage is ViewerReaderPage -> if (previousPage.nextChapter ==
                newPage.page.chapter
            ) {
                1
            } else {
                -1
            }

            is ViewerReaderPage if newPage is ViewerTransitionPage -> if (previousPage.page.chapter ==
                newPage.prevChapter
            ) {
                1
            } else {
                -1
            }

            else -> 0
        }

        if (direction != 0) {
            pager.state.transitionFromPage = buildSpreadPage(previousPage)
            pager.state.animatePageTurn(if (isReversed) direction else -direction)
        } else {
            invalidatePager()
        }
    }

    /**
     * Moves onto a page that occupies the reader's current slot - the segments that replaced a page
     * a split removed, or nothing at all.
     *
     * Deliberately not [moveToPage]: the replacement stands exactly where its parent was, so
     * animating a page turn into it would scroll a screen the reader never asked to move, and the
     * turn direction cannot be derived - a segment's index is synthetic and its difference from the
     * parent's is meaningless.
     */
    internal fun reanchorTo(newPage: ViewerPage) {
        currentPage = newPage
        progressPage(newPage)?.let { reportPageSelected(it) }
        preloadPages(newPage)
        (newPage as? ViewerTransitionPage)?.let { viewerTransitionPage ->
            if (viewerTransitionPage.prevChapter == null || viewerTransitionPage.nextChapter == null) {
                activity.showMenu()
            }
        }
        invalidatePager()
    }

    private fun invalidatePager() {
        try {
            pager.state.invalidate()
        } catch (_: Exception) {
        }
    }

    /**
     * Moves to the next page.
     */
    override fun moveToNext() {
        moveRight()
    }

    /**
     * Moves to the previous page.
     */
    fun moveToPrevious() {
        moveLeft()
    }

    /**
     * Moves to the page at the right.
     */
    protected open fun moveRight() {
        pager.state.getPage(0)?.let { page ->
            if (config.navigateToPan && panTowardNextPage(page)) return
            navigateSpread(1)
        }
    }

    /**
     * Moves to the page at the left.
     */
    protected open fun moveLeft() {
        pager.state.getPage(0)?.let { page ->
            if (config.navigateToPan && panTowardPreviousPage(page)) return
            navigateSpread(-1)
        }
    }

    /**
     * Steps the current page one viewport along the axis it actually turns on, and reports whether
     * it did.
     *
     * Pan has to run on the page-turn axis. Panning X while the strip is vertical slides the page
     * sideways instead of advancing: with the page panned into a corner the pan range is open, so
     * "next page" dragged the reader to the opposite corner of the same page and only turned the
     * page once the sideways travel ran out. The pager's own viewer pans vertically for this
     * reason ([eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.panDown]).
     *
     * Both directions walk the offset *down* for next and *up* for previous: a larger offset pushes
     * the content down/right, which shows the top/left of the page, so advancing means revealing
     * what lies beyond and the offset has to shrink.
     *
     * Returns false when the page has no travel left on that axis, which is the caller's cue to
     * turn the page instead.
     */
    private fun panOneViewport(page: ImagePage, forward: Boolean): Boolean {
        val step = 1f / page.scale
        if (isVertical) {
            val current = page.animationTargetY ?: page.y
            val target = (if (forward) current - step else current + step)
                .coerceIn(page.minY(page.scale), page.maxY(page.scale))
            if (target == page.y) return false
            if (page.animationJob?.isActive == true && page.animationTargetY == target) {
                page.animationJob?.cancel()
                return false
            }
            page.animateTo(targetX = page.x, targetY = target)
            return true
        }
        val current = page.animationTargetX ?: page.x
        val target = (if (forward) current - step else current + step)
            .coerceIn(page.minX(page.scale), page.maxX(page.scale))
        if (target == page.x) return false
        if (page.animationJob?.isActive == true && page.animationTargetX == target) {
            page.animationJob?.cancel()
            return false
        }
        page.animateTo(targetX = target, targetY = page.y)
        return true
    }

    private fun panTowardNextPage(page: ImagePage) = panOneViewport(page, forward = true)

    private fun panTowardPreviousPage(page: ImagePage) = panOneViewport(page, forward = false)

    /** Target anchor page one spread past [from], in [direction] (positive = forward). */
    internal fun nextPage(from: ViewerPage, direction: Int): ViewerPage? {
        var page = getSpreadAnchor(from)

        page = if (direction > 0) {
            if (page is ViewerReaderPage && spreadPartner(page) != null) {
                page.next?.next ?: return null
            } else {
                page.next ?: return null
            }
        } else {
            page.prev ?: return null
        }

        return getSpreadAnchor(page)
    }

    /**
     * Navigate by spreads from current page.
     * @param direction Positive = forward in page numbers, negative = backward
     */
    private fun navigateSpread(direction: Int) {
        val target = currentPage?.let { nextPage(it, direction) } ?: return
        moveToPage(target)
    }

    /**
     * Moves to the page at the top (or previous).
     */
    protected fun moveUp() {
        moveToPrevious()
    }

    /**
     * Moves to the page at the bottom (or next).
     */
    protected fun moveDown() {
        moveToNext()
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val ctrlPressed = event.metaState.and(KeyEvent.META_CTRL_ON) > 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveDown() else moveUp()
                }
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (config.volumeKeysInverted) moveDown() else moveUp()
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> if (isUp) if (ctrlPressed) moveToNext() else moveRight()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (isUp) if (ctrlPressed) moveToPrevious() else moveLeft()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_DPAD_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
                        moveDown()
                    } else {
                        moveUp()
                    }
                    return true
                }
            }
        }
        return false
    }
}
// Mihon <--
