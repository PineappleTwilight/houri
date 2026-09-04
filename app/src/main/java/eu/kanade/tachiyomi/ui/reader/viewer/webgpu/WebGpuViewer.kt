// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.Color
import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import ca.mpreg.webgpuviewer.ImageView
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.transition.TransitionCube
import ca.mpreg.webgpuviewer.transition.TransitionCubeOuter
import ca.mpreg.webgpuviewer.transition.TransitionFade
import ca.mpreg.webgpuviewer.transition.TransitionFadeWhite
import ca.mpreg.webgpuviewer.transition.TransitionFlip
import ca.mpreg.webgpuviewer.transition.TransitionFlipLeft
import ca.mpreg.webgpuviewer.transition.TransitionFlipRight
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.logcat
import java.util.TreeSet
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

open class WebGpuViewer(
    val activity: ReaderActivity,
    val isReversed: Boolean,
    val isVertical: Boolean,
    val pager: ImageView = ImageView(activity, isVertical = isVertical, isReversed = isReversed),
) : Viewer {

    open val isContinuous: Boolean = false

    val readerPreferences by lazy { globalAppGraph.readerPreferences }
    internal val translationManager by lazy {
        try {
            globalAppGraph.translationManager
        } catch (_: Exception) {
            null
        }
    }

    internal fun readerBackgroundColor(): Int = activity.baseContext.readerBackgroundColor(config.theme)

    internal fun readerOnBackgroundColor(): Int = MaterialColors.getColor(
        activity.createReaderThemeContext(),
        com.google.android.material.R.attr.colorOnBackground,
        Color.WHITE,
    )

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
    internal fun viewportPageWidth(half: Boolean): Int = if (half) pager.state.width / 2 else pager.state.width

    /** The half a spread opens on: right reading right-to-left, left otherwise. */
    private val anchorPosition get() = if (isReversed) SpreadPosition.RIGHT else SpreadPosition.LEFT

    private val partnerPosition get() = if (isReversed) SpreadPosition.LEFT else SpreadPosition.RIGHT

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
        val lone = synchronized(lock) { loneIndices[chapterId]?.lower(index) } ?: return 1
        return lone + 1
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
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "decodeReaderPage: ${e.message}" }
                        synchronized(lock) {
                            if (pageInCache(page) && !page.isDecoded && !page.imagePage.destroyed) {
                                val oldImagePage = page.imagePage
                                val errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "Failed to decode image"
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
                    (currentPage?.imagePage as? ProgressPage)?.invalidate()
                } catch (_: Exception) {
                }
                delay(33.milliseconds)
            }
        }
        // KMK <--
    }

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = WebGpuConfig(this, scope, readerPreferences)

    var viewerChapters: ViewerChapters? = null

    val pages: List<ReaderPage>? get() = (currentPage as? ViewerReaderPage)?.page?.chapter?.pages

    @Volatile
    var currentPage: ViewerPage? = null

    open val preloadAhead = 3
    open val preloadBehind = 2

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
        if (pages != null) {
            for (pg in pages) {
                try {
                    val shell = getPage(pg, evictionReference)
                    preloadPage(shell, prioritize = false)
                } catch (_: Exception) {}
            }
        }
        if (!chapterPreloadGuard.tryBegin(key)) {
            // If already in-flight but decodeQueue no longer contains its first page, allow requeue (stale guard)
            val isStale = pages?.firstOrNull()?.let { pg ->
                val k = PageKey.Reader(chapter.chapter.id, pg.index)
                synchronized(lock) { findInCache(k) == null || decodeQueue.none { it.page.index == pg.index } }
            } ?: false
            if (!isStale) return
            chapterPreloadGuard.end(key)
            if (!chapterPreloadGuard.tryBegin(key)) return
        }

        scope.launch(Dispatchers.Default) {
            try {
                if (isDestroyed) return@launch
                activity.viewModel.preload(chapter)
                repeat(100) {
                    if (isDestroyed) return@launch
                    if (chapter.state is ReaderChapter.State.Loaded) {
                        val loadedPages = chapter.pages
                        if (loadedPages != null) {
                            for (pg in loadedPages) {
                                try {
                                    val shell = getPage(pg, evictionReference)
                                    preloadPage(shell, prioritize = false)
                                } catch (_: Exception) {}
                            }
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
            fetchPage = fetch@{ index ->
                val current = currentPage ?: return@fetch null

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

        config.imagePropertyChangedListener = listener@{
            if (isDestroyed) return@listener
            pager.state.apply {
                val isDual = isDualPageMode()
                transition = when (if (isDual) config.transitionAnimationDual else config.transitionAnimation) {
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
                    minZoomWidthFraction = config.continuousMinWidth / 100f
                    scale = minScale
                }
            }

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
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        // KMK -->
        config.doubleTapZoomChangedListener = listener@{
            if (isDestroyed) return@listener
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
            try {
                pager.state.invalidate()
            } catch (_: Exception) {
            }
        }
        // KMK <--
    }

    override fun destroy() {
        synchronized(lock) {
            if (isDestroyed) return
            isDestroyed = true
        }
        config.imagePropertyChangedListener = null
        config.navigationModeChangedListener = null
        config.doubleTapZoomChangedListener = null
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

        synchronized(lock) {
            decodeQueue.clear()
            val snapshot = pageCache.values.toList()
            snapshot.forEach {
                it.state = PageState.IDLE
                (it as? ViewerReaderPage)?.let { readerPage ->
                    try {
                        readerPage.spreadPage?.cleanup()
                    } catch (_: Exception) {
                    }
                    readerPage.spreadBytes = null
                    readerPage.rescaleInFlight = false
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
    }

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        val pages = chapters.currChapter.pages ?: return

        this.viewerChapters = chapters

        val requestedIndex = min(chapters.currChapter.requestedPage, pages.lastIndex)
        val requestedPage = pages[requestedIndex]

        // Get the page and align to spread anchor if needed
        val page = currentPage ?: getPage(requestedPage)
        currentPage = getSpreadAnchor(page)
        (currentPage as? ViewerReaderPage)?.let { reportPageSelected(it) }
        preloadPages(currentPage!!)

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
                (page as? ViewerReaderPage)?.let { reportPageSelected(it) }
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
        (newPage as? ViewerReaderPage)?.let { reportPageSelected(it) }
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
    fun moveToNext() {
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
                val c = if (isVertical && config.imageZoomType == ReaderPageImageView.ZoomStartPosition.RIGHT) -1 else 1
                val x = (page.x - c / page.scale).coerceIn(minX, maxX)
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
                val c = if (isVertical && config.imageZoomType == ReaderPageImageView.ZoomStartPosition.RIGHT) -1 else 1
                val x = (page.x + c / page.scale).coerceIn(minX, maxX)
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

    /**
     * Get the target page when navigating by spreads from the given page.
     * @param from Starting page
     * @param direction Positive = forward in page numbers, negative = backward
     * @return Target page or null if navigation not possible
     */
    private fun nextPage(from: ViewerPage, direction: Int): ViewerPage? {
        var page = getSpreadAnchor(from)

        page = if (direction > 0) {
            // Going forward (next spread)
            if (page is ViewerReaderPage && canFormSpread(page)) {
                page.next?.next ?: return null
            } else {
                page.next ?: return null
            }
        } else {
            // Going backward (prev spread)
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
