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
import android.widget.FrameLayout
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
import ca.mpreg.webgpuviewer.renderer.UpscalerCatmullRom
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.transition.TransitionCube
import ca.mpreg.webgpuviewer.transition.TransitionCubeOuter
import ca.mpreg.webgpuviewer.transition.TransitionFade
import ca.mpreg.webgpuviewer.transition.TransitionFadeWhite
import ca.mpreg.webgpuviewer.transition.TransitionFlip
import ca.mpreg.webgpuviewer.transition.TransitionFlipLeft
import ca.mpreg.webgpuviewer.transition.TransitionFlipRight
import ca.mpreg.webgpuviewer.transition.TransitionNone
import ca.mpreg.webgpuviewer.transition.TransitionSphere
import ca.mpreg.webgpuviewer.transition.TransitionStackDown
import ca.mpreg.webgpuviewer.transition.TransitionStackLeft
import ca.mpreg.webgpuviewer.transition.TransitionStackRight
import ca.mpreg.webgpuviewer.transition.TransitionStackUp
import ca.mpreg.webgpuviewer.viewer.ImagePage
import com.google.android.material.color.MaterialColors
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TransitionAnimation
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

open class WebGpuViewer(
    val activity: ReaderActivity,
    val isReversed: Boolean,
    override val isVertical: Boolean,
    val pager: ImageView = ImageView(activity, isVertical = isVertical, isReversed = isReversed),
) : Viewer {

    private val positionStore by lazy { WebGpuReadingPositionStore(activity) }

    // KMK -->
    private var pendingContinuousRestoreChapterId: Long? = null
    private var pendingPagedRestoreChapterId: Long? = null

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
    private var cachedBackgroundColor: Int? = null

    @Volatile
    private var cachedOnBackgroundColor: Int? = null
    // KMK <--

    // KMK -->
    private val darkModeFilter = WebGpuDarkModeFilter()

    private val brightnessContrastFilter = FilterBrightnessContrast()

    private val hlgFilter = FilterHlg()

    private val lutFilter = FilterLut3d()

    private val einkGrayscaleFilter = FilterGrayscale(saturation = 1f)

    @Volatile
    private var appliedLutKey: String? = null

    @Volatile
    private var lutResolveGeneration = 0

    @Volatile
    private var perfHudView: TextView? = null

    @Volatile
    private var perfHudLastUpdate = 0L
    // KMK <--

    // KMK -->
    private var artCnnUpscaler: UpscalerArtCnn? = null
    // KMK <--

    // KMK -->
    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() = shrinkCacheOnTrim()
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) shrinkCacheOnTrim()
        }
    }
    // KMK <--

    private val deviceLostListener =
        ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.Companion.DeviceLostListener { _, _ ->
            if (isDestroyed) return@DeviceLostListener
            scope.launch {
                try {
                    val recovered =
                        ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.reinit()
                    if (!recovered) {
                        logcat(LogPriority.ERROR) { "WebGPU device lost and reinit failed" }
                    }
                    try {
                        pager.state.invalidate()
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                }
            }
        }

    // KMK -->
    /** Resolved once: decodeReaderPage runs per page on the decode thread. */
    internal val isLowRamDevice: Boolean by lazy {
        try {
            eu.kanade.tachiyomi.util.system.DeviceUtil.isLowRamDevice(activity)
        } catch (_: Exception) {
            false
        }
    }
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

    // Decode queue - pages waiting to be decoded, processed LIFO (last = highest priority)
    internal val decodeQueue = ArrayDeque<ViewerReaderPage>()

    // KMK -->
    private val chapterPreloadGuard = ChapterPreloadGuard()
    // KMK <--

    /**
     * Indices of the pages that take a spread to themselves, by chapter - see [spreadStartIndex].
     * Outlives [pageCache]: every page after one of these depends on it, long since evicted.
     */
    private val loneIndices = HashMap<Long?, TreeSet<Int>>()

    /** Above this, an untagged page is a spread already, not half of one. */
    internal val wideAspect = 1.2f

    /** How far two untagged pages' aspect ratios may differ and still pair. */
    private val pairAspectTolerance = 0.1f

    /** Read live: these pages are built before the surface has a size, and outlive a rotation. */
    internal fun viewportPageWidth(half: Boolean): Int {
        val w = try {
            pager.state.width
        } catch (_: Exception) {
            0
        }
        if (w < 8) return 8
        return if (half) (w / 2).coerceAtLeast(8) else w.coerceAtLeast(8)
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

    init {
        // KMK --> Shed off-screen decoded pages on system memory pressure.
        try {
            activity.registerComponentCallbacks(trimCallbacks)
        } catch (_: Exception) {}
        // KMK <--
        try {
            ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.addDeviceLostListener(deviceLostListener)
        } catch (_: Exception) {}
        // Decode worker thread - processes pages from the queue. Hardened: respects scope
        // cancellation, handles spurious wakeups, avoids tight-loop on evicted pages, and
        // surfaces OOM as a retryable error page instead of killing the worker.
        scope.launch(decodeDispatcher) {
            try {
                while (!isDestroyed) {
                    val page: ViewerReaderPage? = synchronized(lock) {
                        while (decodeQueue.isEmpty() && !isDestroyed) {
                            try {
                                lock.wait(1000)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return@launch
                            }
                        }
                        if (decodeQueue.isEmpty()) return@synchronized null
                        decodeQueue.removeLast().apply { state = PageState.DECODING }
                    }
                    if (page == null) continue

                    val shouldProcess = synchronized(lock) {
                        pageInCache(page) && page.state == PageState.DECODING && !page.isDecoded
                    }

                    if (!shouldProcess) {
                        synchronized(lock) {
                            if (pageInCache(page) && page.state == PageState.DECODING) {
                                page.state = PageState.IDLE
                            }
                        }
                        continue
                    }

                    try {
                        decodeReaderPage(page)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OutOfMemoryError) {
                        logcat(LogPriority.ERROR) { "decodeReaderPage OOM: ${e.message}" }
                        System.gc()
                        synchronized(lock) {
                            if (pageInCache(page) && !page.isDecoded && !page.imagePage.destroyed) {
                                val oldImagePage = page.imagePage
                                page.imagePage = ErrorPage(this@WebGpuViewer, "Out of memory", page.spreadPosition)
                                page.state = PageState.IDLE
                                oldImagePage.cleanup()
                                page.imagePage.invalidate()
                            } else if (pageInCache(page)) {
                                page.state = PageState.IDLE
                            }
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        val isLinkage = e is LinkageError || e is NoClassDefFoundError || e is UnsatisfiedLinkError
                        logcat(LogPriority.ERROR, e) { "decodeReaderPage${if (isLinkage) " linkage" else ""}: ${e.message}" }
                        synchronized(lock) {
                            if (pageInCache(page) && !page.isDecoded && !page.imagePage.destroyed) {
                                val oldImagePage = page.imagePage
                                val errorMessage = when {
                                    isLinkage -> "Decoder not available on this device"
                                    e.message?.isNotBlank() == true -> e.message!!
                                    else -> "Failed to decode image"
                                }
                                page.imagePage = ErrorPage(this@WebGpuViewer, errorMessage, page.spreadPosition)
                                page.state = PageState.IDLE
                                oldImagePage.cleanup()
                                page.imagePage.invalidate()
                            } else if (pageInCache(page)) {
                                page.state = PageState.IDLE
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Scope cancelled — normal shutdown
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Decode worker died" }
            }
        }

        // KMK -->
        // Drives the live spin of the ProgressPage pineapple while a page is loading.
        // ProgressPage is time-based; without periodic invalidate the viewer would render
        // it once and the animation would freeze.
        scope.launch {
            while (!isDestroyed) {
                try {
                    val progress = currentPage?.imagePage as? ProgressPage
                    progress?.invalidate()
                    // Animate at ~30fps only while a progress page is current; poll
                    // slowly otherwise so the viewer does not wake every 33ms idle.
                    delay(if (progress != null) 33.milliseconds else 250.milliseconds)
                    if (config.perfHud) syncPerfHud()
                } catch (_: Exception) {
                }
            }
        }
        // KMK <--
    }

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = WebGpuConfig(this, scope, readerPreferences)

    // KMK -->
    private fun applyPageOffset() {
        try {
            val offset = config.pageOffset
            if (offset == 0) {
                pager.translationX = 0f
                return
            }
            val w = if (pager.width > 0) pager.width else activity.resources.displayMetrics.widthPixels
            pager.translationX = w * offset / 100f * 0.5f
        } catch (_: Exception) {
        }
    }
    // KMK <--

    // KMK -->
    /**
     * Re-resolves spread pairing after [WebGpuConfig.shiftDoublePage] toggles.
     * Positions derive live, so a re-fetch is enough to re-pair everything.
     */
    fun refreshSpreads() {
        if (isDestroyed) return
        try {
            pager.state.invalidate()
        } catch (_: Exception) {
        }
    }
    // KMK <--

    // Read from the render and decode threads, via the prevChapter/nextChapter getters.
    @Volatile
    var viewerChapters: ViewerChapters? = null

    val pages: List<ReaderPage>? get() = (currentPage as? ViewerReaderPage)?.page?.chapter?.pages

    @Volatile
    var currentPage: ViewerPage? = null

    // KMK --> User-tunable preload window; continuous takes max() with live reach.
    open val preloadAhead get() = config.preloadAhead
    open val preloadBehind get() = config.preloadBehind
    // KMK <--

    /**
     * Everything [preloadPages] reaches, plus slack. Sized exactly, a chapter transition page - or
     * in dual mode a spread partner - evicts a page the next fetch asks for, and it decodes again.
     */
    open val cacheSize get() = 1 + preloadAhead + preloadBehind + if (isDualPageMode()) 3 else 1

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

    init {
        pager.state.apply {
            // KMK --> Feed currentAnchor from coalesced Ready emissions (Idle/Released ignored).
            onReaderStateChanged = OnReaderStateChanged { state ->
                if (state is ReaderState.Ready) currentAnchor = state.anchor
            }
            // KMK <--
            fetchPage = fetch@{ index ->
                val current = currentPage ?: return@fetch null

                // KMK --> Continuous never forms spreads: getSpreadAnchor and
                // buildSpreadPage both early-return the page's own imagePage, so
                // skip them outright instead of re-proving it on every frame walk.
                // (Pager mode keeps the full pipeline, including the existing()
                // identity reuse inside buildSpreadPage.)
                if (isContinuous) {
                    if (index == 0) return@fetch current.imagePage
                    var page = current
                    val step = if (index > 0) 1 else -1
                    repeat(abs(index)) {
                        page = nextPage(page, step) ?: return@fetch null
                    }
                    return@fetch page.imagePage
                }
                // KMK <--

                // For index 0, return the current spread
                if (index == 0) {
                    return@fetch buildSpreadPage(getSpreadAnchor(current))
                }

                // Navigate by spreads from current
                var page = current
                val step = if (index > 0) 1 else -1
                repeat(abs(index)) {
                    page = nextPage(page, step) ?: return@fetch null
                }

                return@fetch buildSpreadPage(page)
            }

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
                synchronized(lock) {
                    if (isDestroyed) return@listener
                    decodeQueue.clear()
                    // Snapshot to avoid ConcurrentModification if cleanup triggers callbacks
                    val snapshot = pageCache.values.toList()
                    snapshot.forEach {
                        it.state = PageState.IDLE
                        // KMK -->
                        (it as? ViewerReaderPage)?.let { readerPage ->
                            try {
                                readerPage.spreadPage?.cleanup()
                            } catch (_: Exception) {
                            }
                            readerPage.spreadBytes = null
                            readerPage.rescaleInFlight = false
                            readerPage.cleanupCompare()
                        }
                        // KMK <--
                        try {
                            it.imagePage.cleanup()
                        } catch (_: Exception) {
                        }
                    }
                    pageCache.clear()
                    loneIndices.clear()

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
        pager.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPageOffset() }
        scope.launch {
            try {
                readerPreferences.webgpuPageOffset().changes().collect { applyPageOffset() }
            } catch (_: Exception) {}
        }
        applyPageOffset()
        applyImageState()
        // KMK <--
    }

    // KMK -->
    private fun resolveLutFilter() {
        val preset = config.lutPreset
        val path = config.lutCustomPath
        val key = "$preset|$path"
        if (appliedLutKey == key) return
        if (preset == WEBGPU_LUT_PRESET_NONE) {
            appliedLutKey = key
            lutFilter.lut = null
            return
        }
        val builtIn = webgpuBuiltInLut(preset)
        if (builtIn != null) {
            appliedLutKey = key
            lutFilter.lut = builtIn
            return
        }
        if (preset == WEBGPU_LUT_PRESET_CUSTOM && path.isNotBlank()) {
            val generation = ++lutResolveGeneration
            scope.launch(decodeDispatcher) {
                try {
                    val parsed = webgpuParseCustomLut(path)
                    if (generation != lutResolveGeneration || isDestroyed) return@launch
                    appliedLutKey = key
                    lutFilter.lut = parsed
                    try {
                        pager.state.invalidate()
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                }
            }
            return
        }
        appliedLutKey = key
        lutFilter.lut = null
    }

    private fun applyTranslationCompare() {
        val showOriginal = config.compareTranslation
        synchronized(lock) {
            if (isDestroyed) return
            pageCache.values.toList().forEach { page ->
                val readerPage = page as? ViewerReaderPage ?: return@forEach
                if (!readerPage.hasTranslation) return@forEach
                val original = readerPage.compareOriginal ?: return@forEach
                if (original.destroyed) return@forEach
                if (showOriginal) {
                    val displayed = readerPage.imagePage
                    if (displayed !== original && displayed is ImagePage.ImageSingle) {
                        readerPage.compareTranslated?.let {
                            if (it !== displayed) {
                                try {
                                    it.cleanup()
                                } catch (_: Exception) {
                                }
                            }
                        }
                        readerPage.compareTranslated = displayed
                        readerPage.imagePage = original
                    }
                } else {
                    val parked = readerPage.compareTranslated ?: return@forEach
                    if (parked.destroyed) {
                        readerPage.compareTranslated = null
                        return@forEach
                    }
                    if (readerPage.imagePage !== parked) {
                        readerPage.imagePage = parked
                        readerPage.compareTranslated = null
                    }
                }
            }
        }
        try {
            pager.state.invalidate()
        } catch (_: Exception) {
        }
    }

    private fun syncPerfHud() {
        val want = config.perfHud && eu.kanade.tachiyomi.util.system.isDebugBuildType && !isDestroyed
        try {
            ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.profilingEnabled = want
        } catch (_: Exception) {
        }
        if (!want) {
            try {
                perfHudView?.visibility = View.GONE
            } catch (_: Exception) {
            }
            return
        }
        val hud = try {
            perfHudView ?: TextView(pager.context).apply {
                setBackgroundColor(0x99000000.toInt())
                setTextColor(0xFF00FF00.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(12, 8, 12, 8)
                visibility = View.GONE
                perfHudView = this
                (pager.parent as? ViewGroup)?.addView(
                    this,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.TOP or android.view.Gravity.START,
                    ),
                )
            }
        } catch (_: Exception) {
            null
        } ?: return
        try {
            hud.visibility = View.VISIBLE
            hud.bringToFront()
            val now = android.os.SystemClock.uptimeMillis()
            if (now - perfHudLastUpdate < 500) return
            perfHudLastUpdate = now
            val renderer = ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
            val poolKb = try {
                pager.state.filters.poolBytes() / 1024
            } catch (_: Exception) {
                -1L
            }
            hud.text = "avg %.1fms · fps %.0f · tile %dKB".format(
                renderer.recentAvgFrameTimeMs,
                renderer.estimatedFps,
                poolKb,
            )
        } catch (_: Exception) {
        }
    }
    // KMK <--

    // KMK -->
    /**
     * Applies state-only reader settings (transition, cutout, zoom floors, gap,
     * theme colors) without touching decoded pages. Prefs that change what a decode
     * produces (crop, dual-page geometry, match-heights, theme background baking)
     * still rebuild via [config.onSettingsChanged] when SettingsDiff.highest is
     * REDECODE or above.
     */
    private fun applyImageState() {
        if (isDestroyed) return
        // KMK --> A theme change comes through here.
        cachedBackgroundColor = null
        cachedOnBackgroundColor = null
        // KMK <--
        // KMK -->
        // Post-process color filters apply live with no page re-decode: uniforms
        // update in place and the chain reconciles attach/detach every state change.
        try {
            darkModeFilter.amoled = config.webgpuDarkModeAmoled
            darkModeFilter.tolerance = config.darkModeTolerance
            darkModeFilter.chunkRange = config.darkModeChunkRange
            darkModeFilter.enabled = config.webgpuDarkMode
            val effectiveContrast = if (config.einkPreset) {
                maxOf(config.contrast, 1.15f)
            } else {
                config.contrast
            }
            brightnessContrastFilter.brightness = config.brightness
            brightnessContrastFilter.contrast = effectiveContrast
            brightnessContrastFilter.enabled =
                config.brightness != 0f || effectiveContrast != 1f
            hlgFilter.exposure = config.hlgExposure
            hlgFilter.enabled = config.hlgEnabled
            einkGrayscaleFilter.saturation = if (config.einkPreset) 0f else 1f
            einkGrayscaleFilter.enabled = config.einkPreset
            lutFilter.intensity = config.lutIntensity
            lutFilter.enabled = true
            resolveLutFilter()
            val lutActive = lutFilter.lut != null && config.lutIntensity > 0f &&
                config.lutPreset != WEBGPU_LUT_PRESET_NONE
            val desired = buildList {
                if (brightnessContrastFilter.enabled) add(brightnessContrastFilter)
                if (hlgFilter.enabled) add(hlgFilter)
                if (lutActive) add(lutFilter)
                if (einkGrayscaleFilter.enabled) add(einkGrayscaleFilter)
                if (darkModeFilter.enabled) add(darkModeFilter)
            }
            val current = pager.state.filters.filters
            if (current != desired) {
                pager.state.filters.filters = desired
            } else {
                pager.state.invalidate()
            }
        } catch (_: Exception) {
        }
        try {
            applyTranslationCompare()
        } catch (_: Exception) {
        }
        try {
            syncPerfHud()
        } catch (_: Exception) {
        }
        // KMK <--
        // KMK -->
        // Swap the tile upscaler only on a real change: assigning drops every
        // generated tile, so an unconditional set here would re-gen tiles on
        // each state-only change. The cached instance is reused because the
        // setter is identity-guarded. Unsupported devices fall back to
        // Catmull-Rom inside the tile path by themselves.
        try {
            val wantArtCnn = config.artCnnUpscaler
            val hasArtCnn = pager.state.upscaler is UpscalerArtCnn
            if (wantArtCnn != hasArtCnn) {
                pager.state.upscaler = if (wantArtCnn) {
                    (artCnnUpscaler ?: UpscalerArtCnn().also { artCnnUpscaler = it })
                } else {
                    UpscalerCatmullRom()
                }
            }
        } catch (_: Exception) {
        }
        // KMK <--
        // KMK -->
        // Fast render skips the tile cache on decoded pages (direct mipmap draw);
        // flipping back reuses the same path once, tiles regenerate on demand.
        try {
            val fast = config.fastRender
            synchronized(lock) {
                pageCache.values.forEach {
                    (it.imagePage as? ImagePage.ImageSingle)?.let { single ->
                        if (!single.isAnimated) single.highQuality = !fast
                    }
                }
            }
            pager.state.invalidate()
        } catch (_: Exception) {
        }
        // KMK <--
        pager.state.apply {
            val isDual = isDualPageMode()
            transition = if (config.einkPreset) {
                if (isVertical) TransitionNone.Vertical else TransitionNone
            } else {
                when (if (isDual) config.transitionAnimationDual else config.transitionAnimation) {
                    // KMK -->
                    TransitionAnimation.NONE -> if (isVertical) TransitionNone.Vertical else TransitionNone
                    // KMK <--
                    TransitionAnimation.BASIC -> if (isVertical) TransitionBasic.Vertical else TransitionBasic
                    TransitionAnimation.FLIP -> TransitionFlip
                    TransitionAnimation.FLIP_LEFT -> TransitionFlipLeft
                    TransitionAnimation.FLIP_RIGHT -> TransitionFlipRight
                    TransitionAnimation.STACK_LEFT -> TransitionStackLeft
                    TransitionAnimation.STACK_RIGHT -> TransitionStackRight
                    TransitionAnimation.STACK_UP -> TransitionStackUp
                    TransitionAnimation.STACK_DOWN -> TransitionStackDown
                    TransitionAnimation.SPHERE -> TransitionSphere
                    TransitionAnimation.CUBE_INSIDE -> TransitionCube
                    TransitionAnimation.CUBE_OUTSIDE -> TransitionCubeOuter
                    TransitionAnimation.FADE -> TransitionFade
                    TransitionAnimation.FADE_WHITE -> TransitionFadeWhite
                }
            }

            when (if (isDual) config.cutoutModeDual else config.cutoutMode) {
                ReaderPreferences.CutoutMode.IGNORE -> avoidCutout = false

                ReaderPreferences.CutoutMode.AVOID -> {
                    avoidCutout = true
                    alwaysAvoidCutout = false
                }

                ReaderPreferences.CutoutMode.SHIFT -> {
                    avoidCutout = true
                    alwaysAvoidCutout = true
                }
            }

            (this as? ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState)?.let {
                // KMK -->
                // WEBTOON (long strip, no gap) always locks zoom-out to the strip
                // width; CONTINUOUS_VERTICAL follows the disable-zoom-out pref.
                val isWebtoonStrip = (this@WebGpuViewer as? WebGpuViewerContinuous)?.useGap == false
                val lockToStrip = isWebtoonStrip || config.zoomOutDisabled
                homeScale = config.continuousMinWidth / 100f
                minScale = if (lockToStrip) 0f else 0.1f
                // Never clobber the reader's zoom here: these listeners fire on
                // state-only changes too. Only lift out of an illegal range
                // (homeScale's own setter already lifts scale when the floor
                // itself rises).
                if (lockToStrip && scale < homeScale) scale = homeScale

                if ((this@WebGpuViewer as? WebGpuViewerContinuous)?.useGap == true) {
                    pageGap = config.continuousGap / 100f
                }
                // KMK <--
            }
        }
    }
    // KMK <--

    override fun destroy() {
        try {
            (currentPage as? ViewerReaderPage)?.let { reportPageSelected(it) }
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
        synchronized(lock) {
            decodeQueue.clear()
            val snapshot = pageCache.values.toList()
            snapshot.forEach {
                it.state = PageState.IDLE
                (it as? ViewerReaderPage)?.let { readerPage ->
                    try {
                        cancelSpreadHeightRetry(readerPage)
                    } catch (_: Exception) {
                    }
                    try {
                        readerPage.spreadPage?.cleanup()
                    } catch (_: Exception) {
                    }
                    readerPage.spreadBytes = null
                    readerPage.rescaleInFlight = false
                    readerPage.cleanupCompare()
                }
                try {
                    it.imagePage.cleanup()
                } catch (_: Exception) {
                }
            }
            pageCache.clear()
            loneIndices.clear()
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
            scheduleTranslation(page, page.sourceBytes())
        }
    }
    // KMK <--

    /**
     * Reports the active [page] to the activity. When the page forms a spread in dual-page mode,
     * marks it as having an extra page so the counter shows "N-N+1" instead of just "N".
     */
    private fun reportPageSelected(page: ViewerReaderPage) {
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
                pager.state.seedPageIndex(page.page.index)
                val anchor = try {
                    pager.state.captureAnchor().copy(pageIndex = page.page.index)
                } catch (_: Exception) {
                    PageAnchor(pageIndex = page.page.index)
                }
                currentAnchor = anchor
                positionStore.saveAnchor(cid, anchor)
            }
        } catch (_: Exception) {}
    }

    private var isIdle = true
    private var awaitingIdleViewerChapters: ViewerChapters? = null

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     * Mirrors [eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer.setChapters] for modular parity.
     */
    override fun setChapters(chapters: ViewerChapters) {
        if (!isIdle) {
            awaitingIdleViewerChapters = chapters
            return
        }
        setChaptersInternal(chapters)
    }

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
        if (deferredStored != null) {
            // Restoring now would measure against ProgressPage placeholders
            // (viewport-height each), landing the viewport in empty space once
            // real heights decode: black screen, then phantom page walks on the
            // first scroll. Wait for the target page to decode, then restore
            // once against real heights. Aborts if the user scrolls, navigates,
            // or the chapter changes first.
            try {
                val cont = pager as? ca.mpreg.webgpuviewer.ImageViewContinuous
                val st = cont?.state
                if (st != null) {
                    val restoreChapterId = chapterId
                    val anchorPage = currentPage
                    val startDocY = try {
                        st.documentY
                    } catch (_: Exception) {
                        0f
                    }
                    scope.launch {
                        try {
                            var ready = false
                            var waited = 0
                            while (waited < 100) {
                                if (isDestroyed) return@launch
                                if (viewerChapters?.currChapter?.chapter?.id != restoreChapterId) return@launch
                                val target = synchronized(lock) {
                                    findInCache(PageKey.Reader(restoreChapterId, targetIndex)) as? ViewerReaderPage
                                }
                                val surfaceReady = try {
                                    pager.state.width > 0 && pager.state.height > 0
                                } catch (_: Exception) {
                                    false
                                }
                                if (surfaceReady && (target?.isDecoded == true || target?.imagePage is ErrorPage)) {
                                    ready = true
                                    break
                                }
                                kotlinx.coroutines.delay(100)
                                waited++
                            }
                            if (!ready || isDestroyed) return@launch
                            if (viewerChapters?.currChapter?.chapter?.id != restoreChapterId) return@launch
                            if (currentPage !== anchorPage) return@launch
                            val nowDocY = try {
                                st.documentY
                            } catch (_: Exception) {
                                startDocY
                            }
                            if (!nowDocY.isFinite() || !startDocY.isFinite() || abs(nowDocY - startDocY) > 2f) return@launch
                            when {
                                deferredStored.isV2 -> {
                                    val pos = ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState.ContinuousPosition(
                                        documentY = deferredStored.offsetRatio,
                                        scale = deferredStored.zoom,
                                        offsetX = deferredStored.offsetX,
                                        pageIndexHint = deferredStored.pageIndex,
                                        fractionWithinPage = deferredStored.fraction,
                                    )
                                    st.restorePosition(pos, animate = false)
                                }
                                else -> {
                                    // legacy fraction 0..1 — restore by page+fraction.
                                    // Suppress callbacks: the scroll walk would re-drive
                                    // currentPage mid-restore and jump somewhere random.
                                    val cb = st.onPageChange
                                    st.onPageChange = null
                                    try {
                                        if (deferredStored.fraction.isFinite() && deferredStored.fraction > 0f) {
                                            st.scrollToPage(deferredStored.pageIndex, deferredStored.fraction)
                                        }
                                    } finally {
                                        st.onPageChange = cb
                                    }
                                    val maxOffsetX = maxOf(0f, (deferredStored.zoom - 1f) / (2f * deferredStored.zoom))
                                    st.scale = deferredStored.zoom.coerceIn(st.minScale, st.maxScale)
                                    st.offsetX = deferredStored.offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                                }
                            }
                            try {
                                pager.state.invalidate()
                            } catch (_: Exception) {}
                        } finally {
                            if (pendingContinuousRestoreChapterId == restoreChapterId) {
                                pendingContinuousRestoreChapterId = null
                            }
                        }
                    }
                } else {
                    pendingContinuousRestoreChapterId = null
                }
            } catch (_: Exception) {
                pendingContinuousRestoreChapterId = null
            }
        }
        // KMK --> Paged zoom+offset restore: mirrors the continuous deferred
        // restore above (abort on user nav/chapter change, clamp to the live
        // page bounds). Restoring before decode would measure against the
        // ProgressPage placeholder, so wait for the real page like continuous.
        if (needsPagedRestore && stored != null) {
            try {
                val restoreChapterId = chapterId
                val anchorPage = currentPage
                val wantZoom = stored.zoom
                val wantX = stored.offsetX
                scope.launch {
                    try {
                        var ready = false
                        var waited = 0
                        while (waited < 100) {
                            if (isDestroyed) return@launch
                            if (viewerChapters?.currChapter?.chapter?.id != restoreChapterId) return@launch
                            val target = synchronized(lock) {
                                findInCache(PageKey.Reader(restoreChapterId, targetIndex)) as? ViewerReaderPage
                            }
                            val surfaceReady = try {
                                pager.state.width > 0 && pager.state.height > 0
                            } catch (_: Exception) {
                                false
                            }
                            if (surfaceReady && (target?.isDecoded == true || target?.imagePage is ErrorPage)) {
                                ready = true
                                break
                            }
                            kotlinx.coroutines.delay(100)
                            waited++
                        }
                        if (!ready || isDestroyed) return@launch
                        if (viewerChapters?.currChapter?.chapter?.id != restoreChapterId) return@launch
                        if (currentPage !== anchorPage) return@launch
                        try {
                            val page = pager.state.getPage(0)
                            if (page != null && wantZoom.isFinite()) {
                                page.scale = wantZoom.coerceIn(page.minScale, page.maxScale)
                                if (wantX.isFinite() && wantX != 0f) {
                                    val minX = page.minX(page.scale)
                                    val maxX = page.maxX(page.scale)
                                    page.animateTo(targetX = wantX.coerceIn(minX, maxX), targetY = page.y)
                                }
                            }
                        } catch (_: Exception) {
                        }
                        try {
                            pager.state.invalidate()
                        } catch (_: Exception) {}
                    } finally {
                        if (pendingPagedRestoreChapterId == restoreChapterId) {
                            pendingPagedRestoreChapterId = null
                        }
                    }
                }
            } catch (_: Exception) {
                pendingPagedRestoreChapterId = null
            }
        }
        // KMK <--

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

    private fun moveToPage(newPage: ViewerPage) {
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

        if (previousPage == null) return

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
            pager.state.invalidate()
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
            if (config.navigateToPan) {
                val minX = page.minX(page.scale)
                val maxX = page.maxX(page.scale)
                // Where a running pan is headed, else where it sits.
                val currentX = page.animationTargetX ?: page.x

                val c = if (isVertical && config.imageZoomType == ReaderPageImageView.ZoomStartPosition.RIGHT) -1 else 1
                val x = (currentX - c / page.scale).coerceIn(minX, maxX)
                if (x != page.x) {
                    if (page.animationJob?.isActive == true && page.animationTargetX == x) {
                        page.animationJob?.cancel()
                    } else {
                        page.animateTo(targetX = x, targetY = page.y)
                        return
                    }
                }
            }

            navigateSpread(1)
        }
    }

    /**
     * Moves to the page at the left.
     */
    protected open fun moveLeft() {
        pager.state.getPage(0)?.let { page ->
            if (config.navigateToPan) {
                val minX = page.minX(page.scale)
                val maxX = page.maxX(page.scale)
                val currentX = page.animationTargetX ?: page.x

                val c = if (isVertical && config.imageZoomType == ReaderPageImageView.ZoomStartPosition.RIGHT) -1 else 1
                val x = (currentX + c / page.scale).coerceIn(minX, maxX)
                if (x != page.x) {
                    if (page.animationJob?.isActive == true && page.animationTargetX == x) {
                        page.animationJob?.cancel()
                    } else {
                        page.animateTo(targetX = x, targetY = page.y)
                        return
                    }
                }
            }

            navigateSpread(-1)
        }
    }

    /** Target anchor page one spread past [from], in [direction] (positive = forward). */
    private fun nextPage(from: ViewerPage, direction: Int): ViewerPage? {
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
                    if (!config.volumeKeysInverted) moveUp() else moveDown()
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
