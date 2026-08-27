// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.core.graphics.createBitmap
import androidx.webgpu.GPUTexture
import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.ImageView
import ca.mpreg.webgpuviewer.draw.TextAlign
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.Image.Companion.invoke
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.transition.TransitionCube
import ca.mpreg.webgpuviewer.transition.TransitionCubeOuter
import ca.mpreg.webgpuviewer.transition.TransitionFade
import ca.mpreg.webgpuviewer.transition.TransitionFadeWhite
import ca.mpreg.webgpuviewer.transition.TransitionFlipLeft
import ca.mpreg.webgpuviewer.transition.TransitionFlipRight
import ca.mpreg.webgpuviewer.transition.TransitionSphere
import ca.mpreg.webgpuviewer.transition.TransitionStackDown
import ca.mpreg.webgpuviewer.transition.TransitionStackLeft
import ca.mpreg.webgpuviewer.transition.TransitionStackRight
import ca.mpreg.webgpuviewer.transition.TransitionStackUp
import ca.mpreg.webgpuviewer.viewer.ImagePage
import com.google.android.material.color.MaterialColors
import de.stefan_oltmann.kim.Kim
import de.stefan_oltmann.kim.android.readMetadata
import de.stefan_oltmann.kim.format.tiff.constant.TiffTag
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TransitionAnimation
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.ZoomStartPosition
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
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

open class WebGpuViewer(
    val activity: ReaderActivity,
    val isReversed: Boolean,
    val isVertical: Boolean,
    val pager: ImageView = ImageView(activity, isVertical = isVertical, isReversed = isReversed),
) : Viewer {

    open val isContinuous: Boolean = false

    val readerPreferences by lazy { globalAppGraph.readerPreferences }

    private fun readerBackgroundColor(): Int = activity.baseContext.readerBackgroundColor(config.theme)

    private fun readerOnBackgroundColor(): Int = MaterialColors.getColor(
        activity.createReaderThemeContext(),
        com.google.android.material.R.attr.colorOnBackground,
        Color.WHITE,
    )

    private val scope = MainScope()

    @Volatile
    private var isDestroyed = false

    // Dedicated thread for decode worker to avoid blocking Dispatchers.Default pool
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WebGpuViewer-Decode").apply { isDaemon = true }
    }
    private val decodeDispatcher = decodeExecutor.asCoroutineDispatcher()

    // Single lock for all page cache and queue operations
    private val lock = Object()

    // Page cache - keyed by stable PageKey for O(1) lookup
    private val pageCache = LinkedHashMap<PageKey, ViewerPage>()

    // Decode queue - pages waiting to be decoded, processed LIFO (last = highest priority)
    private val decodeQueue = ArrayDeque<ViewerReaderPage>()

    // KMK -->
    private val chapterPreloadGuard = ChapterPreloadGuard()
    // KMK <--

    /**
     * Which side of a dual-page spread a [ViewerReaderPage] belongs on - app-level bookkeeping
     * for [getSpreadAnchor]/[buildSpreadPage], independent of the decoded image itself.
     */
    internal enum class SpreadPosition { LEFT, RIGHT, SINGLE }

    // Stable key types for page identity - data classes provide correct equals/hashCode
    private sealed class PageKey {
        data class Reader(val chapterId: Long?, val index: Int) : PageKey()
        data class Transition(val prevId: Long?, val nextId: Long?) : PageKey()
    }

    private fun pageKey(page: ViewerPage): PageKey = when (page) {
        is ViewerReaderPage -> PageKey.Reader(page.page.chapter.chapter.id, page.page.index)
        is ViewerTransitionPage -> PageKey.Transition(page.prevChapter?.chapter?.id, page.nextChapter?.chapter?.id)
        else -> PageKey.Transition(null, null)
    }

    private fun findInCache(key: PageKey): ViewerPage? = pageCache[key]

    /** Check if a page is in the cache by identity. O(1) via key lookup. */
    private fun pageInCache(page: ViewerPage): Boolean = pageCache[pageKey(page)] === page

    /**
     * Queue a page for decoding if not already queued/loading/decoded.
     * If prioritize=true and page is already queued, moves it to front.
     * Must be called while holding lock.
     */
    private fun queueForDecode(page: ViewerReaderPage, prioritize: Boolean = false) {
        // Already has a decoded image
        if (page.isDecoded) return

        when (page.state) {
            PageState.IDLE -> {
                page.state = PageState.QUEUED
                if (prioritize) {
                    decodeQueue.addLast(page)
                } else {
                    decodeQueue.addFirst(page)
                }
                lock.notify()
            }

            PageState.QUEUED -> {
                // Already queued - move to front if prioritizing
                if (prioritize && decodeQueue.remove(page)) {
                    decodeQueue.addLast(page)
                }
            }

            PageState.LOADING, PageState.DECODING -> {
                // Already being processed
            }
        }
    }

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
                                page.imagePage = ErrorPage("Out of memory", page.spreadPosition)
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
                                page.imagePage = ErrorPage(errorMessage, page.spreadPosition)
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

    open val cacheSize get() = 1 + preloadAhead + preloadBehind

    /**
     * Page processing state
     */
    enum class PageState {
        IDLE,
        QUEUED,
        LOADING,
        DECODING,
    }

    /**
     * Evicts the page farthest from reference. Must be called while holding lock.
     * Hardened: coerces cacheSize, handles null current via newest entry, and avoids
     * infinite loops when reference is not in cache.
     */
    private fun evictFarthestPage(reference: ViewerPage? = null) {
        val effectiveCacheSize = cacheSize.coerceAtLeast(1)
        val current = reference ?: currentPage ?: pageCache.values.lastOrNull() ?: return
        val allCandidates = pageCache.values.filter { it !== current }.toMutableSet()
        if (allCandidates.isEmpty()) return
        val candidates = allCandidates.filter { it.state == PageState.IDLE }.toMutableSet()
        val effectiveCandidates = if (candidates.isEmpty()) allCandidates else candidates

        fun findNext(page: ViewerPage): ViewerPage? = when (page) {
            is ViewerReaderPage -> {
                val chapterId = page.page.chapter.chapter.id
                val nextIndex = page.page.index + 1
                allCandidates.find {
                    it is ViewerReaderPage && it.page.chapter.chapter.id == chapterId && it.page.index == nextIndex
                } ?: allCandidates.find { it is ViewerTransitionPage && it.prevChapter?.chapter?.id == chapterId }
                    ?: page.nextChapter?.chapter?.id?.let { nextChapterId ->
                        allCandidates.find {
                            it is ViewerReaderPage && it.page.chapter.chapter.id == nextChapterId && it.page.index == 0
                        }
                    }
            }

            is ViewerTransitionPage -> {
                val nextChapterId = page.nextChapter?.chapter?.id
                allCandidates.find {
                    it is ViewerReaderPage && it.page.chapter.chapter.id == nextChapterId && it.page.index == 0
                }
            }

            else -> null
        }

        fun findPrev(page: ViewerPage): ViewerPage? = when (page) {
            is ViewerReaderPage -> {
                val chapterId = page.page.chapter.chapter.id
                val prevIndex = page.page.index - 1
                allCandidates.find {
                    it is ViewerReaderPage && it.page.chapter.chapter.id == chapterId && it.page.index == prevIndex
                } ?: allCandidates.find { it is ViewerTransitionPage && it.nextChapter?.chapter?.id == chapterId }
                    ?: page.prevChapter?.let { prevChapter ->
                        prevChapter.pages?.lastIndex?.let { lastIndex ->
                            allCandidates.find {
                                it is ViewerReaderPage && it.page.chapter.chapter.id == prevChapter.chapter.id &&
                                    it.page.index == lastIndex
                            }
                        }
                    }
            }

            is ViewerTransitionPage -> {
                val prevChapterId = page.prevChapter?.chapter?.id
                page.prevChapter?.pages?.lastIndex?.let { lastIndex ->
                    allCandidates.find {
                        it is ViewerReaderPage && it.page.chapter.chapter.id == prevChapterId &&
                            it.page.index == lastIndex
                    }
                }
            }

            else -> null
        }

        var farthest: ViewerPage? = null
        var forward: ViewerPage? = current
        var backward: ViewerPage? = current

        for (i in 0 until effectiveCacheSize) {
            if (effectiveCandidates.isEmpty()) break
            forward = forward?.let { findNext(it) }
            backward = backward?.let { findPrev(it) }
            if (forward == null && backward == null) break
            if (forward != null && effectiveCandidates.remove(forward)) farthest = forward
            if (backward != null && effectiveCandidates.remove(backward)) farthest = backward
        }

        val toRemove = effectiveCandidates.firstOrNull() ?: farthest ?: allCandidates.firstOrNull() ?: return

        pageCache.remove(pageKey(toRemove))
        decodeQueue.remove(toRemove)
        toRemove.state = PageState.IDLE
        // KMK -->
        (toRemove as? ViewerReaderPage)?.let {
            it.spreadPage?.cleanup()
            it.spreadBytes = null
        }
        // KMK <--
        toRemove.imagePage.cleanup()
    }

    /**
     * Gets or creates a page. Thread-safe.
     * @param referencePage The page to use as reference for eviction (defaults to currentPage)
     */
    fun getPage(page: ReaderPage, referencePage: ViewerPage? = null): ViewerPage {
        val key = PageKey.Reader(page.chapter.chapter.id, page.index)
        return synchronized(lock) {
            findInCache(key) ?: ViewerReaderPage(page).also { newPage ->
                pageCache[key] = newPage
                var guard = 0
                while (pageCache.size > cacheSize.coerceAtLeast(1) && guard++ < 16) {
                    val before = pageCache.size
                    evictFarthestPage(referencePage ?: newPage)
                    if (pageCache.size == before) break
                }
            }
        }
    }

    fun getPage(
        prevChapter: ReaderChapter?,
        nextChapter: ReaderChapter?,
        referencePage: ViewerPage? = null,
    ): ViewerPage {
        val key = PageKey.Transition(prevChapter?.chapter?.id, nextChapter?.chapter?.id)
        return synchronized(lock) {
            findInCache(key) ?: ViewerTransitionPage(prevChapter, nextChapter).also { newPage ->
                pageCache[key] = newPage
                var guard = 0
                while (pageCache.size > cacheSize.coerceAtLeast(1) && guard++ < 16) {
                    val before = pageCache.size
                    evictFarthestPage(referencePage ?: newPage)
                    if (pageCache.size == before) break
                }
            }
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
    private fun preloadChapterThenRetry(chapter: ReaderChapter) {
        if (isDestroyed) return
        val key = chapter.chapter.url?.takeIf { it.isNotBlank() } ?: "chapter-${chapter.chapter.id}"
        // Reserve placeholder ProgressPage shells immediately so contentHeight reflects true length and scroll doesn't wrap
        val pages = chapter.pages
        if (pages != null) {
            for (pg in pages) {
                try {
                    val shell = getPage(pg)
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
                                    val shell = getPage(pg)
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

    inner class ErrorPage internal constructor(
        var message: String,
        spreadPosition: SpreadPosition = SpreadPosition.SINGLE,
    ) : ImagePage.Render(
        (if (spreadPosition == SpreadPosition.SINGLE) pager.state.width else pager.state.width / 2).coerceAtLeast(1),
        pager.state.height.coerceAtLeast(1),
    ) {
        init {
            minScale = 1f
            maxScale = 1f
            homeScale = 1f
        }

        override val backgroundColor: Int
            get() = try {
                readerBackgroundColor()
            } catch (_: Exception) {
                Color.BLACK
            }

        override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
            if (isDestroyed || dst.width <= 0 || dst.height <= 0) return
            val padding = try {
                with(pager.state.density) { 24.dp.toPx() }
            } catch (_: Exception) {
                24f
            }
            val size = try {
                scale * with(pager.state.density) { 16.dp.toPx() }
            } catch (_: Exception) {
                16f * scale
            }

            val cx = dst.width * (0.5f + scale * x)
            val cy = dst.height * (0.5f + scale * y)

            try {
                text(
                    dst,
                    activity.baseContext,
                    FontFamily.Default,
                    message.takeIf { it.isNotBlank() } ?: "Error",
                    cx,
                    cy,
                    size,
                    color = try {
                        readerOnBackgroundColor()
                    } catch (_: Exception) {
                        Color.WHITE
                    },
                    align = TextAlign.Center,
                    maxWidth = (dst.width - 2f * padding).coerceAtLeast(0f),
                )
            } catch (_: Exception) {
            }
        }
    }

    inner class ProgressPage(
        var foregroundColor: Int = try {
            readerOnBackgroundColor()
        } catch (_: Exception) {
            Color.WHITE
        },
    ) : ImagePage.Render(
        (if (!isDualPageMode()) pager.state.width else pager.state.width / 2).coerceAtLeast(1),
        pager.state.height.coerceAtLeast(1),
    ) {
        var progress: Float = 0f

        init {
            minScale = 1f
            maxScale = 1f
            homeScale = 1f
        }

        override val backgroundColor: Int
            get() = try {
                readerBackgroundColor()
            } catch (_: Exception) {
                Color.BLACK
            }

        override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
            if (isDestroyed || dst.width <= 0 || dst.height <= 0) return
            val cx = dst.width * (0.5f + scale * x)
            val cy = dst.height * (0.5f + scale * y)

            val full = try {
                width * 0.5f * scale
            } catch (_: Exception) {
                return
            }
            if (full <= 0f) return
            try {
                circle(cx, cy, full / 2f, 0xAAAAAAAA.toInt())
            } catch (_: Exception) {
            }

            val diameter = full * progress.fastCoerceIn(0f, 1f)
            if (diameter > 0) {
                try {
                    circle(cx, cy, diameter / 2f, foregroundColor)
                } catch (_: Exception) {
                }
            }
        }
    }

    inner class TransitionPage(val prevChapter: ReaderChapter?, val nextChapter: ReaderChapter?) : ImagePage.Render(
        min(pager.state.width.coerceAtLeast(1), pager.state.height.coerceAtLeast(1)).coerceAtLeast(1),
        min(pager.state.width.coerceAtLeast(1), pager.state.height.coerceAtLeast(1)).coerceAtLeast(1),
    ) {
        init {
            minScale = 1f
            maxScale = 1f
            homeScale = 1f
        }

        override val backgroundColor: Int
            get() = try {
                readerBackgroundColor()
            } catch (_: Exception) {
                Color.BLACK
            }

        override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
            if (isDestroyed || dst.width <= 0 || dst.height <= 0) return
            val lines: MutableList<String> = mutableListOf()
            try {
                prevChapter?.chapter?.let { chapter ->
                    lines.add(activity.stringResource(MR.strings.action_previous_chapter) + ": " + chapter.name)
                }
                nextChapter?.chapter?.let { chapter ->
                    lines.add(activity.stringResource(MR.strings.action_next_chapter) + ": " + chapter.name)
                }
            } catch (_: Exception) {
            }

            val text = lines.joinToString("\n")
            if (text.isBlank()) return

            val padding = try {
                with(pager.state.density) { 24.dp.toPx() }
            } catch (_: Exception) {
                24f
            }
            val size = try {
                scale * with(pager.state.density) { 16.dp.toPx() }
            } catch (_: Exception) {
                16f * scale
            }

            val cx = dst.width * (0.5f + scale * x)
            val cy = dst.height * (0.5f + scale * y)

            try {
                text(
                    dst,
                    activity.baseContext,
                    FontFamily.Default,
                    text,
                    cx,
                    cy,
                    size,
                    try {
                        readerOnBackgroundColor()
                    } catch (_: Exception) {
                        Color.WHITE
                    },
                    align = TextAlign.Center,
                    maxWidth = (dst.width - 2f * padding).coerceAtLeast(0f),
                )
            } catch (_: Exception) {
            }
        }
    }

    abstract class ViewerPage {
        abstract val prevChapter: ReaderChapter?
        abstract val nextChapter: ReaderChapter?
        abstract val prev: ViewerPage?
        abstract val next: ViewerPage?

        @Volatile
        var state: PageState = PageState.IDLE

        @Volatile
        open var imagePage: ImagePage = ImagePage.Dummy(400, 400)

        open val isDecoded = true
    }

    inner class ViewerTransitionPage(
        override val prevChapter: ReaderChapter?,
        override val nextChapter: ReaderChapter?,
    ) : ViewerPage() {
        override var imagePage: ImagePage = TransitionPage(prevChapter, nextChapter)

        override val prev: ViewerPage?
            get() = prevChapter?.pages?.lastOrNull()?.let { getPage(it, currentPage) }

        override val next: ViewerPage?
            get() = nextChapter?.pages?.firstOrNull()?.let { getPage(it, currentPage) }
    }

    inner class ViewerReaderPage(val page: ReaderPage) : ViewerPage() {
        /** Cached spread ImagePage when this page is the anchor of a dual-page spread */
        var spreadPage: ImagePage.ImageSpread? = null

        /** Which side of a dual-page spread this page belongs on - set once decoding tags it. */
        internal var spreadPosition: SpreadPosition = SpreadPosition.SINGLE

        // KMK -->
        /**
         * Compressed source bytes retained for spread height-matching. Only set for
         * partner-position pages decoded in dual-page mode; freed on eviction.
         */
        @Volatile
        var spreadBytes: ByteArray? = null

        /** Guards against scheduling duplicate height-match rescales for this page */
        @Volatile
        var rescaleInFlight: Boolean = false
        // KMK <--

        override var imagePage: ImagePage = ProgressPage()

        override val isDecoded
            get() = (imagePage as? ImagePage.ImageSingle)?.isDecoded == true

        override val prevChapter: ReaderChapter?
            get() = when (page.chapter) {
                viewerChapters?.currChapter -> viewerChapters?.prevChapter
                viewerChapters?.nextChapter -> viewerChapters?.currChapter
                else -> null
            }

        override val nextChapter: ReaderChapter?
            get() = when (page.chapter) {
                viewerChapters?.currChapter -> viewerChapters?.nextChapter
                viewerChapters?.prevChapter -> viewerChapters?.currChapter
                else -> null
            }

        override val prev: ViewerPage?
            get() = page.chapter.pages?.let { pages ->
                pages.getOrNull(page.index - 1)?.let { getPage(it, currentPage) } ?: run {
                    val prevChapter = prevChapter ?: return@run getPage(null, page.chapter, currentPage)

                    if (prevChapter.state !is ReaderChapter.State.Loaded) {
                        preloadChapterThenRetry(prevChapter)
                    }

                    if (config.alwaysShowChapterTransition) {
                        getPage(prevChapter, page.chapter, currentPage)
                    } else {
                        prevChapter.pages?.lastOrNull()?.let { getPage(it, currentPage) }
                    }
                }
            }

        override val next: ViewerPage?
            get() = page.chapter.pages?.let { pages ->
                pages.getOrNull(page.index + 1)?.let { getPage(it, currentPage) } ?: run {
                    val nextChapter = nextChapter ?: return@run getPage(page.chapter, null, currentPage)

                    if (nextChapter.state !is ReaderChapter.State.Loaded) {
                        preloadChapterThenRetry(nextChapter)
                    }

                    if (config.alwaysShowChapterTransition) {
                        getPage(page.chapter, nextChapter, currentPage)
                    } else {
                        nextChapter.pages?.firstOrNull()?.let { getPage(it, currentPage) }
                    }
                }
            }
    }

    /**
     * Check if dual page mode is currently active based on config and view dimensions.
     * Dual page is never active for continuous (scrolling) viewers.
     */
    private fun isDualPageMode(): Boolean {
        if (isContinuous) return false
        return when (config.dualPageView) {
            ReaderPreferences.DualPageView.NEVER -> false
            ReaderPreferences.DualPageView.ALWAYS -> true
            ReaderPreferences.DualPageView.WIDE -> {
                val width = pager.state.width
                val height = pager.state.height
                width > 0 && height > 0 && width.toFloat() / height > 1f
            }
        }
    }

    /**
     * Check if the given page can form a spread with the next page.
     * Uses page.spreadPosition to determine: anchor + partner = spread
     * RTL: RIGHT is anchor, looks for LEFT on next
     * LTR: LEFT is anchor, looks for RIGHT on next
     */
    private fun canFormSpread(page: ViewerReaderPage): Boolean {
        if (!isDualPageMode()) return false
        val anchorPosition = if (isReversed) SpreadPosition.RIGHT else SpreadPosition.LEFT
        val partnerPosition = if (isReversed) SpreadPosition.LEFT else SpreadPosition.RIGHT
        if (page.spreadPosition != anchorPosition) return false
        val next = page.next as? ViewerReaderPage ?: return false
        if (next.page.chapter != page.page.chapter) return false
        return next.spreadPosition == partnerPosition
    }

    /**
     * Get the anchor page for a spread.
     * RTL: anchor is RIGHT, for LEFT page returns previous RIGHT
     * LTR: anchor is LEFT, for RIGHT page returns previous LEFT
     */
    private fun getSpreadAnchor(page: ViewerPage): ViewerPage {
        if (!isDualPageMode()) return page
        if (page !is ViewerReaderPage) return page

        val anchorPosition = if (isReversed) SpreadPosition.RIGHT else SpreadPosition.LEFT
        val partnerPosition = if (isReversed) SpreadPosition.LEFT else SpreadPosition.RIGHT

        // If this is a partner page, check if previous is anchor
        if (page.spreadPosition == partnerPosition) {
            val prev = page.prev as? ViewerReaderPage ?: return page
            if (prev.page.chapter == page.page.chapter && prev.spreadPosition == anchorPosition) {
                return prev
            }
        }

        // This page is the anchor or standalone
        return page
    }

    private fun buildSpreadPage(page: ViewerPage): ImagePage {
        // For ViewerTransitionPage, return its imagePage directly
        if (page !is ViewerReaderPage) {
            return page.imagePage
        }

        // Only form spreads in dual page mode
        if (!isDualPageMode()) {
            return page.imagePage
        }

        // Whatever the page is holding takes its half of the seam, decoded or not:
        // [ImagePage.ImageSpread] draws a [ImagePage.Render] side into its own half. A page left
        // out would take the whole viewport instead, hiding its partner with it.
        val imagePage = page.imagePage

        if (page.spreadPosition == SpreadPosition.SINGLE) {
            page.spreadPage = null
            return imagePage
        }

        val anchorPosition = if (isReversed) SpreadPosition.RIGHT else SpreadPosition.LEFT
        val partnerPosition = if (isReversed) SpreadPosition.LEFT else SpreadPosition.RIGHT

        // Only the anchor side looks for a partner on the next page. A partner-tagged page only
        // reaches this function directly (rather than being redirected here via
        // [getSpreadAnchor]) when it has no anchor before it - a lone RIGHT with no preceding
        // LEFT (or vice versa), e.g. at a chapter boundary - so it renders alone on its own side
        // instead of looking anywhere else for a partner.
        val nextReaderPage = if (page.spreadPosition == anchorPosition) {
            (page.next as? ViewerReaderPage)?.takeIf { it.page.chapter == page.page.chapter }
        } else {
            null
        }
        val partnerImagePage = nextReaderPage?.imagePage?.takeIf {
            nextReaderPage.spreadPosition == partnerPosition
        }

        // LEFT/RIGHT map directly to the spread's left/right slot - independent of reading
        // direction, which only decides which side is the anchor for pairing purposes above.
        val left = if (page.spreadPosition == SpreadPosition.LEFT) imagePage else partnerImagePage
        val right = if (page.spreadPosition == SpreadPosition.RIGHT) imagePage else partnerImagePage

        // Reuse existing spread if the sides match - preserves transform state
        val spread = if (existing(left, right, page.spreadPage)) {
            page.spreadPage!!
        } else {
            ImagePage.ImageSpread(left, right).also { page.spreadPage = it }
        }

        // KMK -->
        maybeScheduleSpreadHeightMatch(page, spread, nextReaderPage)
        // KMK <--
        return spread
    }

    private fun existing(left: ImagePage?, right: ImagePage?, spreadPage: ImagePage.ImageSpread?): Boolean {
        return spreadPage != null && spreadPage.left === left && spreadPage.right === right
    }

    // KMK -->
    /**
     * Dual-page spread height matching: when both halves are decoded images with differing
     * heights, rescale the shorter side to the taller side's height. The rescaled image
     * replaces that side's [ImagePage.ImageSingle], and the spread recomposes from slot
     * identity on the next fetch. Prior logic could rescale the taller side down (tiny on
     * e-ink) or rescale to its own height (no-op on first spread); this version is
     * deterministic and retry-safe.
     */
    private fun maybeScheduleSpreadHeightMatch(
        anchorPage: ViewerReaderPage,
        spread: ImagePage.ImageSpread,
        nextReaderPage: ViewerReaderPage?,
    ) {
        if (!config.matchDoublePageHeights) return

        val leftImage = (spread.left as? ImagePage.ImageSingle)?.image
        val rightImage = (spread.right as? ImagePage.ImageSingle)?.image
        if (leftImage == null || rightImage == null || leftImage.height == rightImage.height) return
        if (leftImage.height <= 0 || rightImage.height <= 0) return

        // Deterministically scale the shorter side up to the taller side. This avoids
        // shrinking a large page down to a small partner (which produced tiny spreads on
        // e-ink) and makes the target independent of decode order.
        val isLeftShorter = leftImage.height < rightImage.height
        val targetHeight = maxOf(leftImage.height, rightImage.height)

        // Resolve which ViewerReaderPage backs the shorter side.
        val shorterPage: ViewerReaderPage? = when {
            isLeftShorter && anchorPage.spreadPosition == SpreadPosition.LEFT -> anchorPage
            isLeftShorter && nextReaderPage != null && nextReaderPage.spreadPosition == SpreadPosition.LEFT -> nextReaderPage
            !isLeftShorter && anchorPage.spreadPosition == SpreadPosition.RIGHT -> anchorPage
            !isLeftShorter && nextReaderPage != null && nextReaderPage.spreadPosition == SpreadPosition.RIGHT -> nextReaderPage
            else -> null
        }

        val sourcePage = when {
            shorterPage != null && shorterPage.spreadBytes != null && !shorterPage.rescaleInFlight -> shorterPage
            // Fallback: if the shorter side's bytes are gone (evicted or already used),
            // scale the taller side down to at least make heights equal rather than leave
            // a persistent mismatch. Prefer partner for the fallback to keep anchor stable.
            nextReaderPage != null && nextReaderPage !== anchorPage &&
                nextReaderPage.spreadBytes != null && !nextReaderPage.rescaleInFlight -> nextReaderPage
            anchorPage.spreadBytes != null && !anchorPage.rescaleInFlight -> anchorPage
            else -> return
        }

        // If we fell back to scaling the taller side, adjust target to the shorter height.
        val resolvedTarget = if (sourcePage === shorterPage) {
            targetHeight
        } else {
            minOf(leftImage.height, rightImage.height)
        }
        scheduleSpreadHeightMatch(sourcePage, resolvedTarget)
    }

    private fun scheduleSpreadHeightMatch(sourcePage: ViewerReaderPage, targetHeight: Int) {
        synchronized(lock) { sourcePage.rescaleInFlight = true }

        scope.launch(decodeDispatcher) {
            var scaledImage: Image? = null
            try {
                val bytes = synchronized(lock) { sourcePage.spreadBytes }
                if (bytes != null && targetHeight in 1..8192) {
                    scaledImage = rescaleImageToHeight(bytes, targetHeight)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                logcat(LogPriority.ERROR) { "Spread height-match OOM target $targetHeight" }
                System.gc()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Spread height-match rescale failed" }
            }

            var swapped = false
            synchronized(lock) {
                sourcePage.rescaleInFlight = false
                if (scaledImage != null && pageInCache(sourcePage)) {
                    val scaledSingle = ImagePage.ImageSingle(scaledImage!!)
                    applyDoubleTapZoomPolicy(scaledSingle)
                    val oldImagePage = sourcePage.imagePage
                    sourcePage.imagePage = scaledSingle
                    sourcePage.spreadBytes = null
                    oldImagePage.cleanup()
                    swapped = true
                } else {
                    sourcePage.spreadBytes = null
                    scaledImage?.let { stale -> ImagePage.ImageSingle(stale).cleanup() }
                }
            }

            if (swapped) pager.state.invalidate()
        }
    }

    private suspend fun rescaleImageToHeight(bytes: ByteArray, targetHeight: Int): Image {
        require(targetHeight in 1..8192) { "targetHeight out of range: $targetHeight" }
        val dec = ImageDecoder.new(bytes.inputStream())
        val frame = dec.decodeNext()
        val srcWidth = frame.width
        val srcHeight = frame.height
        require(srcWidth in 1..8192 && srcHeight in 1..8192) { "src dimensions out of range: ${srcWidth}x$srcHeight" }

        val srcBitmap = createBitmap(srcWidth, srcHeight)
        try {
            frame.image.rewind()
            srcBitmap.copyPixelsFromBuffer(frame.image)
        } catch (e: Exception) {
            srcBitmap.recycle()
            throw e
        }

        val scaledWidth = (srcWidth.toFloat() * targetHeight / srcHeight)
            .roundToInt()
            .coerceIn(1, 8192)

        if (scaledWidth * targetHeight > 16 * 1024 * 1024) {
            srcBitmap.recycle()
            throw IllegalArgumentException("scaled area too large: ${scaledWidth}x$targetHeight")
        }

        val scaledBitmap = try {
            Bitmap.createScaledBitmap(srcBitmap, scaledWidth, targetHeight, true)
        } catch (e: OutOfMemoryError) {
            srcBitmap.recycle()
            System.gc()
            throw e
        }
        if (scaledBitmap !== srcBitmap) srcBitmap.recycle()

        val buffer = try {
            ByteBuffer.allocateDirect(scaledWidth * targetHeight * 4)
        } catch (e: OutOfMemoryError) {
            scaledBitmap.recycle()
            System.gc()
            throw e
        }
        try {
            scaledBitmap.copyPixelsToBuffer(buffer)
        } catch (e: Exception) {
            scaledBitmap.recycle()
            throw e
        }
        buffer.rewind()
        scaledBitmap.recycle()

        return Image(
            buffer,
            scaledWidth,
            targetHeight,
            createMipMaps = true,
            backgroundColor = if (config.automaticBackground) null else readerBackgroundColor(),
        )
    }
    // KMK <--

    /**
     * The viewer library always performs its built-in double-tap zoom, so when the
     * preference is disabled the page's max scale is clamped to its home scale - the
     * zoom animation then lands where it started. Pinch zoom sets scale directly and
     * never consults this value. The library sentinel -1f restores the computed default.
     */
    private fun applyDoubleTapZoomPolicy(page: ImagePage.ImageSingle) {
        if (isDestroyed || isContinuous) return
        if (config.doubleTapZoom) {
            try {
                page.maxScale = -1f
            } catch (_: Exception) {
            }
            return
        }
        val w = try {
            pager.state.width
        } catch (_: Exception) {
            0
        }
        val h = try {
            pager.state.height
        } catch (_: Exception) {
            0
        }
        if (w <= 0 || h <= 0) return
        try {
            page.maxScale = page.homeScale
        } catch (_: Exception) {
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
                        current.imagePage = ProgressPage()
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
                transition = when (config.transitionAnimation) {
                    TransitionAnimation.DEFAULT -> if (isVertical) TransitionBasic.Vertical else TransitionBasic
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

                when (config.cutoutMode) {
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
                        (readerPage.imagePage as? ImagePage.ImageSingle)?.let(::applyDoubleTapZoomPolicy)
                        readerPage.spreadPage?.let { spread ->
                            (spread.left as? ImagePage.ImageSingle)?.let(::applyDoubleTapZoomPolicy)
                            (spread.right as? ImagePage.ImageSingle)?.let(::applyDoubleTapZoomPolicy)
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

    /**
     * Start loading a page and set up listener to re-queue when ready.
     * Hardened: checks destroyed, handles loader null, cleans up jobs on eviction/cancel,
     * and surfaces load errors as tap-retry ErrorPage without leaking collectors.
     */
    private fun startPageLoad(page: ViewerReaderPage) {
        if (isDestroyed) return
        val loader = page.page.chapter.pageLoader ?: run {
            synchronized(lock) { if (pageInCache(page)) page.state = PageState.IDLE }
            return
        }

        if (page.page.status == Page.State.Ready) {
            synchronized(lock) {
                if (!pageInCache(page)) return
                if (!page.isDecoded) {
                    page.state = PageState.IDLE
                    queueForDecode(page, prioritize = currentPage?.let { pageKey(it) == pageKey(page) } ?: false)
                    synchronized(lock) { lock.notify() }
                } else {
                    page.state = PageState.IDLE
                }
            }
            return
        }

        synchronized(lock) {
            if (!pageInCache(page) || isDestroyed) return
            if (page.state != PageState.IDLE && page.state != PageState.QUEUED && page.state != PageState.DECODING) return
            page.state = PageState.LOADING
        }

        if (page.page.status == Page.State.Queue) {
            scope.launch(Dispatchers.IO) {
                try {
                    loader.loadPage(page.page)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "loadPage failed for ${page.page.index}" }
                }
            }
        }

        scope.launch {
            var downloadProgressJob: kotlinx.coroutines.Job? = null
            try {
                downloadProgressJob = launch {
                    try {
                        page.page.progressFlow.collect { value ->
                            if (isDestroyed) return@collect
                            val stillValid = synchronized(lock) {
                                pageInCache(page) && page.imagePage is ProgressPage
                            }
                            if (!stillValid) return@collect
                            (page.imagePage as? ProgressPage)?.apply {
                                progress = value.coerceIn(0, 100) / 100f
                                try {
                                    invalidate()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }

                try {
                    page.page.statusFlow.takeWhile { state ->
                        when (state) {
                            Page.State.Queue, Page.State.LoadPage, Page.State.DownloadImage -> true
                            is Page.State.Error -> {
                                logcat(LogPriority.ERROR) { "Page load error: ${state.error}" }
                                false
                            }
                            Page.State.Ready -> false
                        }
                    }.collect {}
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "statusFlow collect failed" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "startPageLoad error" }
                synchronized(lock) { if (pageInCache(page)) page.state = PageState.IDLE }
            } finally {
                try {
                    downloadProgressJob?.cancel()
                } catch (_: Exception) {
                }
                synchronized(lock) {
                    if (isDestroyed || !pageInCache(page) || page.state != PageState.LOADING) return@synchronized
                    page.state = PageState.IDLE
                    when (val s = page.page.status) {
                        Page.State.Ready -> {
                            if (!page.isDecoded) {
                                queueForDecode(
                                    page,
                                    prioritize = currentPage?.let { pageKey(it) == pageKey(page) } ?: false,
                                )
                                lock.notify()
                            }
                        }
                        is Page.State.Error -> {
                            val message = s.error.message?.takeIf { it.isNotBlank() } ?: "Failed to load page"
                            val oldImagePage = page.imagePage
                            if (!oldImagePage.destroyed) {
                                page.imagePage = ErrorPage(message, page.spreadPosition)
                                try {
                                    oldImagePage.cleanup()
                                } catch (_: Exception) {
                                }
                                try {
                                    pager.state.invalidate()
                                } catch (_: Exception) {
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private suspend fun decodeReaderPage(page: ViewerReaderPage) {
        if (isDestroyed) return
        if (page.page.status != Page.State.Ready) {
            startPageLoad(page)
            return
        }

        val stream = try {
            page.page.stream?.invoke()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "page.stream failed index ${page.page.index}" }
            null
        } ?: run {
            synchronized(lock) { if (pageInCache(page)) page.state = PageState.IDLE }
            return
        }

        stream.use { input ->
            synchronized(lock) {
                if (isDestroyed || !pageInCache(page) || page.isDecoded) {
                    if (pageInCache(page) && !isDestroyed) page.state = PageState.IDLE
                    return
                }
            }

            val bytes: ByteArray? = try {
                if (config.dualPageView != ReaderPreferences.DualPageView.NEVER) {
                    // Cap read to 32MB to avoid OOM on corrupt pages
                    val limited = input.readBytes()
                    if (limited.size > 32 * 1024 * 1024) null else limited
                } else {
                    null
                }
            } catch (e: OutOfMemoryError) {
                System.gc()
                null
            } catch (_: Exception) {
                null
            }

            page.spreadPosition = if (bytes != null) {
                val tag = try {
                    Kim.readMetadata(bytes.inputStream(), bytes.size.toLong())
                        ?.findStringValue(TiffTag.TIFF_TAG_PAGE_NAME)
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
                when (tag) {
                    "Left" -> SpreadPosition.LEFT
                    "Right" -> SpreadPosition.RIGHT
                    null -> if (isReversed) {
                        if (page.page.index % 2 == 0) SpreadPosition.LEFT else SpreadPosition.RIGHT
                    } else {
                        if (page.page.index % 2 == 0) SpreadPosition.RIGHT else SpreadPosition.LEFT
                    }
                    else -> SpreadPosition.SINGLE
                }
            } else {
                SpreadPosition.SINGLE
            }

            if (config.matchDoublePageHeights &&
                bytes != null &&
                page.spreadPosition != SpreadPosition.SINGLE &&
                bytes.size in 1..32 * 1024 * 1024
            ) {
                page.spreadBytes = bytes
            } else {
                page.spreadBytes = null
            }

            val dec = try {
                ImageDecoder.new(bytes?.inputStream() ?: input)
            } catch (e: Exception) {
                throw Exception("ImageDecoder init failed: ${e.message}", e)
            }

            val pageCount = dec.pages

            if (pageCount == 0) throw Exception("No frames decoded")

            val backgroundColor = if (config.automaticBackground) null else readerBackgroundColor()

            val firstFrame = dec.decodeNext()

            val imagePage = if (pageCount == 1) {
                // Only trim when not animated and not in dual page mode
                val trimColors = if (config.imageCropBorders && !isDualPageMode()) {
                    listOf(
                        floatArrayOf(1f, 1f, 1f),
                        floatArrayOf(0f, 0f, 0f),
                    )
                } else {
                    null
                }

                val firstImage = Image(
                    firstFrame.image,
                    firstFrame.width,
                    firstFrame.height,
                    createMipMaps = true,
                    trimColors = trimColors,
                    trimThreshold = 0.15f,
                    backgroundColor = backgroundColor,
                )

                ImagePage.ImageSingle(firstImage)
            } else {
                val frames = ArrayList<Pair<Image, Int>>(pageCount)

                val firstImage = Image(
                    firstFrame.image,
                    firstFrame.width,
                    firstFrame.height,
                    createMipMaps = false,
                    backgroundColor = backgroundColor,
                )

                frames.add(Pair(firstImage, firstFrame.duration))

                repeat(pageCount - 1) {
                    (page.imagePage as? ProgressPage)?.apply {
                        progress = (it + 1).toFloat() / pageCount
                        invalidate()
                    }
                    val frame = dec.decodeNext()
                    val image = Image(
                        frame.image,
                        frame.width,
                        frame.height,
                        createMipMaps = false,
                        backgroundColor = firstImage.backgroundColor,
                    )
                    frames.add(Pair(image, frame.duration))
                }

                ImagePage.ImageSingle(frames)
            }

            synchronized(lock) {
                if (pageInCache(page) && !page.isDecoded && !page.imagePage.destroyed) {
                    val oldImagePage = page.imagePage
                    page.imagePage = imagePage
                    page.state = PageState.IDLE
                    oldImagePage.cleanup()
                    val decodedSingle = page.imagePage as? ImagePage.ImageSingle
                    if (decodedSingle != null) {
                        // KMK -->
                        if (page.spreadPosition == SpreadPosition.SINGLE) {
                            if (!applyWideZoomIfNeeded(decodedSingle)) {
                                applyFitModeAnchor(decodedSingle)
                            }
                        }
                        applyDoubleTapZoomPolicy(decodedSingle)
                        // KMK <--
                    }
                    pager.state.invalidate()
                } else {
                    if (pageInCache(page)) page.state = PageState.IDLE
                }
            }
        }
    }

    private fun applyWideZoomIfNeeded(page: ImagePage.ImageSingle): Boolean {
        if (isDestroyed || !config.landscapeZoom) return false
        val image = page.image ?: return false

        val screenW = try {
            pager.state.width
        } catch (_: Exception) {
            0
        }
        val screenH = try {
            pager.state.height
        } catch (_: Exception) {
            0
        }
        if (screenW <= 0 || screenH <= 0) return false

        // Wide page: half the image width is wider than the screen aspect ratio
        if (image.width.toFloat() / image.height <= 2f * screenW.toFloat() / screenH) return false

        // Scale to fit half the image width to the full screen width
        val wideScale = screenW.toFloat() / (image.width / 2f)

        page.homeScale = wideScale

        // need to set parent for positioning to work
        page.parent = pager.state

        val minX = page.minX(page.homeScale)
        val maxX = page.maxX(page.homeScale)

        val startX = when (config.imageZoomType) {
            ZoomStartPosition.LEFT -> maxX
            ZoomStartPosition.RIGHT -> minX
            ZoomStartPosition.CENTER -> 0f
        }

        page.homeX = startX
        page.scale = page.homeScale
        page.x = startX
        page.y = page.homeY
        return true
    }

    private fun applyFitModeAnchor(page: ImagePage.ImageSingle) {
        if (isDestroyed) return
        val scaleType = config.imageScaleType
        if (scaleType != 3 && scaleType != 4 && scaleType != 5) return

        val image = page.image ?: return

        val screenW = try {
            pager.state.width
        } catch (_: Exception) {
            0
        }
        val screenH = try {
            pager.state.height
        } catch (_: Exception) {
            0
        }
        if (screenW <= 0 || screenH <= 0) return

        val w = page.trimWidth.toFloat()
        val h = page.trimHeight.toFloat()
        if (w <= 0f || h <= 0f) return

        val cutoutTopPx = pager.state.cutoutTopPx
        val contentW = screenW.toFloat()
        val contentH = if (pager.state.avoidCutout && cutoutTopPx > 0f) screenH - cutoutTopPx else screenH.toFloat()

        page.homeScale = when (scaleType) {
            3 -> contentW / w
            4 -> contentH / h
            else -> 1f // original size
        }.coerceAtLeast(0.01f)

        page.parent = pager.state

        if (scaleType == 5) { // original size
            val minScaleComputed = minOf(contentW / page.width, contentH / page.height).coerceAtLeast(0.01f)
            if (page.homeScale < minScaleComputed) {
                page.minScale = page.homeScale
            }
        }

        // zoom start for fit height/original size
        if (scaleType == 4 || scaleType == 5) {
            val minX = page.minX(page.homeScale)
            val maxX = page.maxX(page.homeScale)
            page.homeX = when (config.imageZoomType) {
                ZoomStartPosition.LEFT -> maxX
                ZoomStartPosition.RIGHT -> minX
                ZoomStartPosition.CENTER -> 0f
            }
        }

        // push below cutout for fit width/original size
        val trimTop = image.trim?.top ?: 0
        val imageTopY = (screenH - page.height * page.homeScale) / 2f
        val trimTopY = imageTopY + trimTop * page.homeScale
        if ((scaleType == 3 || scaleType == 5) && h * page.homeScale > screenH) {
            val target = if (pager.state.avoidCutout && cutoutTopPx > 0f) {
                if (pager.state.alwaysAvoidCutout) cutoutTopPx / 2f else cutoutTopPx
            } else {
                0f
            }
            page.homeY = maxOf(0f, (target - trimTopY) / (page.homeScale * screenH))
        }

        page.scale = page.homeScale
        page.x = page.homeX
        page.y = page.homeY
    }

    /**
     * Queue a page for decoding. If prioritize=true, moves existing queued page to front.
     */
    protected fun preloadPage(page: ViewerPage, prioritize: Boolean = false) {
        synchronized(lock) {
            val cachedPage = findInCache(pageKey(page)) ?: return
            if (cachedPage is ViewerReaderPage) {
                queueForDecode(cachedPage, prioritize)
            }
        }
    }

    protected fun preloadPages(page: ViewerPage) {
        // Get the canonical page from cache to ensure we're working with current data
        val key = pageKey(page)
        val cachedPage = synchronized(lock) { findInCache(key) } ?: return

        // Priority order: current (highest), next1, next2, prev1, prev2 (lowest)
        // Add in reverse for LIFO, current page gets prioritized

        // Add prev pages (lowest priority)
        val prevPages = mutableListOf<ViewerPage>()
        var p: ViewerPage? = cachedPage
        for (i in 0 until preloadBehind) {
            p = p?.prev ?: break
            prevPages.add(p)
        }
        prevPages.asReversed().forEach { preloadPage(it) }

        // Add next pages (medium priority)
        val nextPages = mutableListOf<ViewerPage>()
        p = cachedPage
        for (i in 0 until preloadAhead) {
            p = p?.next ?: break
            nextPages.add(p)
        }
        nextPages.asReversed().forEach { preloadPage(it) }

        // Add current spread last with priority flag (highest priority in LIFO)
        // Also preload the paired page
        cachedPage.next?.let { preloadPage(it, prioritize = true) }
        preloadPage(cachedPage, prioritize = true)
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
        (currentPage as? ViewerReaderPage)?.let { activity.onPageSelected(it.page) }
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
                (page as? ViewerReaderPage)?.let { activity.onPageSelected(it.page) }
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
        (newPage as? ViewerReaderPage)?.let { activity.onPageSelected(it.page) }
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
                val c = if (isReversed) -1 else 1
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
                val c = if (isReversed) -1 else 1
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
