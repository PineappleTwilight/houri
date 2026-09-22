// Mihon -->
@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // Object() lock used for wait/notify

package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.Image.Companion.invoke
import ca.mpreg.webgpuviewer.viewer.ImagePage
import de.stefan_oltmann.kim.Kim
import de.stefan_oltmann.kim.android.readMetadata
import de.stefan_oltmann.kim.format.tiff.constant.TiffTag
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.UpscaleReaderHook
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.ZoomStartPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap

// KMK -->
/**
 * EXIF orientation retained per decoded page (1 when absent/unparseable). Weak keys so an
 * evicted page cannot leak its viewer; translation re-applies the same rotation to the baked
 * result so it matches the displayed base without re-encoding any bytes.
 */
private val pageExifOrientations: MutableMap<ViewerReaderPage, Int> =
    Collections.synchronizedMap(WeakHashMap())

internal fun noteExifOrientation(page: ViewerReaderPage, orientation: Int) {
    synchronized(pageExifOrientations) {
        if (orientation in 2..8) {
            pageExifOrientations[page] = orientation
        } else {
            pageExifOrientations.remove(page)
        }
    }
}

internal fun exifOrientationOf(page: ViewerReaderPage): Int =
    synchronized(pageExifOrientations) { pageExifOrientations[page] } ?: 1

/** A rotated RGBA buffer with its new dimensions. Pure math, no Android dependency. */
internal data class RotatedRgba(val buffer: ByteBuffer, val width: Int, val height: Int)

/**
 * Reorients an RGBA [src] (4 bytes/pixel) per EXIF orientation 1-8, moving 4-byte units
 * opaquely. Returns the input untouched for orientation 1/out-of-range, degenerate dims, or a
 * short buffer - never throws. 5-8 swap axes. Single pass, no intermediate allocation.
 */
internal fun rotateRgbaForExif(src: ByteBuffer, width: Int, height: Int, orientation: Int): RotatedRgba {
    if (orientation !in 2..8 || width <= 0 || height <= 0) return RotatedRgba(src, width, height)
    val swapAxes = orientation >= 5
    val dstWidth = if (swapAxes) height else width
    val dstHeight = if (swapAxes) width else height
    if (dstWidth !in 1..SPREAD_MAX_DIM || dstHeight !in 1..SPREAD_MAX_DIM) {
        return RotatedRgba(src, width, height)
    }
    return try {
        val srcInts = src.duplicate().asIntBuffer()
        if (srcInts.remaining() < width * height) return RotatedRgba(src, width, height)
        val out = ByteBuffer.allocateDirect(dstWidth * dstHeight * 4)
        val outInts = out.asIntBuffer()
        for (dy in 0 until dstHeight) {
            for (dx in 0 until dstWidth) {
                val sx: Int
                val sy: Int
                when (orientation) {
                    2 -> {
                        sx = width - 1 - dx
                        sy = dy
                    }
                    3 -> {
                        sx = width - 1 - dx
                        sy = height - 1 - dy
                    }
                    4 -> {
                        sx = dx
                        sy = height - 1 - dy
                    }
                    5 -> {
                        sx = dy
                        sy = dx
                    }
                    6 -> {
                        sx = dy
                        sy = height - 1 - dx
                    }
                    7 -> {
                        sx = width - 1 - dy
                        sy = height - 1 - dx
                    }
                    else -> {
                        sx = width - 1 - dy
                        sy = dx
                    }
                }
                outInts.put(dy * dstWidth + dx, srcInts.get(sy * width + sx))
            }
        }
        out.rewind()
        RotatedRgba(out, dstWidth, dstHeight)
    } catch (_: OutOfMemoryError) {
        System.gc()
        RotatedRgba(src, width, height)
    } catch (_: Exception) {
        RotatedRgba(src, width, height)
    }
}
// KMK <--

/**
 * Queue a page for decoding if not already queued/loading/decoded.
 * If prioritize=true and page is already queued, moves it to front.
 * Thread-safe: acquires [lock] internally.
 */
internal fun WebGpuViewer.queueForDecode(page: ViewerReaderPage, prioritize: Boolean = false) {
    synchronized(lock) {
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
}

/**
 * Evicts the page farthest from reference. Must be called while holding lock.
 *
 * Hardened: victim selection is distance-based, computed from chapter/index math
 * instead of a cache chain-walk. The old walk needed every intermediate page
 * cached to measure distance, so any gap (chapter edge, transition page, earlier
 * eviction) broke the walk and eviction fell back to oldest-first - eating pages
 * right next to the one being read and re-showing them as placeholders (flicker,
 * and unloads when scrolling back). Distance here is gap-proof: same-chapter
 * pages use index math, adjacent-chapter pages count from the chapter edge, a
 * transition page bridging the anchor chapter sits at distance 0, and anything
 * unrelated sorts farthest. Pages inside the directional preload window are
 * never victims while anything outside it exists; in-flight decodes (non-IDLE)
 * are only taken when no IDLE victim exists. Distance ties prefer undecoded
 * placeholder shells over decoded content (dropping them is invisible), then
 * break oldest-first via insertion order. The anchor and the live current page
 * are never candidates. Pages the renderer drew on the last frame
 * (ImagePage.isOnScreen) are never victims while any undrawn candidate exists,
 * regardless of distance: the distance math is anchored on currentPage, which the
 * continuous viewer tracks through the submodule's relative +/-1 page-change
 * deltas, so any lockstep disagreement mis-centers the window and distance alone
 * would destroy visible pages (blank gaps on the next frame, since the capture
 * skips destroyed pages, plus a re-decode loop when scrolling back). The
 * last-drawn set is ground truth for visibility, so it overrules distance. The
 * drawn read may lag the render thread by a frame; keeping a stale-drawn page is
 * invisible, destroying a live one is not. Fallback order is oldest undrawn,
 * then oldest, so the cache stays bounded exactly as before.
 */
internal fun WebGpuViewer.evictFarthestPage(reference: ViewerPage? = null) {
    val anchor = reference ?: currentPage ?: pageCache.values.lastOrNull() ?: return
    val liveCurrent = currentPage

    // A shell that never decoded still shows its placeholder, so dropping it is
    // invisible and rebuilding it is cheap; dropping decoded content reverts a
    // possibly half-visible page to a placeholder (flicker + re-decode). At equal
    // distance the placeholder sorts as the farther victim.
    fun isCheapPlaceholder(page: ViewerPage): Boolean =
        page is ViewerReaderPage && !page.isDecoded

    // Higher rank sorts as the farther victim; ties prefer the cheap placeholder,
    // then earliest insertion (strict > keeps the first encounter).
    fun beats(rank: Int, cheap: Boolean, bestRank: Int, bestCheap: Boolean, hasBest: Boolean): Boolean {
        if (!hasBest) return true
        if (rank != bestRank) return rank > bestRank
        return cheap && !bestCheap
    }

    var bestIdle: ViewerPage? = null
    var bestIdleRank = Int.MIN_VALUE
    var bestIdleCheap = false
    var bestAny: ViewerPage? = null
    var bestAnyRank = Int.MIN_VALUE
    var bestAnyCheap = false
    var oldestIdle: ViewerPage? = null
    var oldestAny: ViewerPage? = null
    // Oldest non-anchor candidates the renderer did NOT draw last frame; preferred
    // over oldestIdle/oldestAny so the absolute fallback stays a last resort.
    var oldestIdleSafe: ViewerPage? = null
    var oldestAnySafe: ViewerPage? = null

    for (page in pageCache.values) {
        if (page === anchor) continue
        if (liveCurrent != null && page === liveCurrent) continue
        if (oldestAny == null) oldestAny = page
        val isIdle = page.state == PageState.IDLE
        if (isIdle && oldestIdle == null) oldestIdle = page
        // Drawn-page immunity (see KDoc): drawn pages only feed the absolute
        // oldest fallback and never the distance-ranked victims.
        val drawn = page.imagePage.isOnScreen
        if (!drawn) {
            if (oldestAnySafe == null) oldestAnySafe = page
            if (isIdle && oldestIdleSafe == null) oldestIdleSafe = page
        } else {
            continue
        }

        val distance = pageDistance(anchor, page)
        // Inside the directional preload window (plus one slack for a spread
        // partner or transition page): keep while anything outside it exists.
        val inWindow = distance != null &&
            (distance == 0 || distance in 1..preloadAhead + 1 || distance in -(preloadBehind + 1)..-1)
        if (!inWindow) {
            // Unrelated chapters sort past every related page, so stale shells go first.
            // Absolute reach: a stale page far behind must shed before a fresh shell
            // just past the leading edge. Signed comparison did the opposite - every
            // prewarm shell past the window self-evicted on insert, so decode-ahead
            // never completed (its queue entry is removed with it and the worker
            // skips out-of-cache pages) and fast scrolling arrived at placeholders.
            val rank = distance?.let { kotlin.math.abs(it) } ?: Int.MAX_VALUE
            val cheap = isCheapPlaceholder(page)
            if (isIdle && beats(rank, cheap, bestIdleRank, bestIdleCheap, bestIdle != null)) {
                bestIdleRank = rank
                bestIdleCheap = cheap
                bestIdle = page
            }
            if (beats(rank, cheap, bestAnyRank, bestAnyCheap, bestAny != null)) {
                bestAnyRank = rank
                bestAnyCheap = cheap
                bestAny = page
            }
        }
    }

    // Fully in-window cache (steady state plus one new shell): drop the farthest
    // page at the window edge rather than the oldest, which may be adjacent.
    // Placeholders go before decoded content at equal reach, for the same
    // no-visible-flicker reason as above.
    var victim = bestIdle ?: bestAny
    if (victim == null) {
        var edgeIdle: ViewerPage? = null
        var edgeIdleRank = Int.MIN_VALUE
        var edgeIdleCheap = false
        var edgeAny: ViewerPage? = null
        var edgeAnyRank = Int.MIN_VALUE
        var edgeAnyCheap = false
        for (page in pageCache.values) {
            if (page === anchor) continue
            if (liveCurrent != null && page === liveCurrent) continue
            // Drawn-page immunity (see KDoc).
            if (page.imagePage.isOnScreen) continue
            val distance = pageDistance(anchor, page) ?: continue
            val rank = kotlin.math.abs(distance)
            val cheap = isCheapPlaceholder(page)
            if (page.state == PageState.IDLE &&
                beats(rank, cheap, edgeIdleRank, edgeIdleCheap, edgeIdle != null)
            ) {
                edgeIdleRank = rank
                edgeIdleCheap = cheap
                edgeIdle = page
            }
            if (beats(rank, cheap, edgeAnyRank, edgeAnyCheap, edgeAny != null)) {
                edgeAnyRank = rank
                edgeAnyCheap = cheap
                edgeAny = page
            }
        }
        victim = edgeIdle ?: edgeAny
    }

    val toRemove = victim ?: oldestIdleSafe ?: oldestAnySafe ?: oldestIdle ?: oldestAny ?: return

    pageCache.remove(pageKey(toRemove))
    decodeQueue.remove(toRemove)
    toRemove.state = PageState.IDLE
    // KMK -->
    (toRemove as? ViewerReaderPage)?.let {
        // An evicted anchor is terminal for its height-match: drop any coalesced retry
        // with it so a dead spread can never spin. Fresh bytes on re-decode re-arm.
        cancelSpreadHeightRetry(it)
        synchronized(pageExifOrientations) { pageExifOrientations.remove(it) }
        it.spreadPage?.cleanup()
        it.spreadBytes = null
    }
    // KMK <--
    toRemove.imagePage.cleanup()
}

// KMK -->
// Memory-pressure shed: shrink the cache toward current + one neighbor each
// side via the hardened farthest-first evictor. Evicted pages re-decode on
// demand; the decode worker skips them while out of cache, so no wasted work.
internal fun WebGpuViewer.shrinkCacheOnTrim() {
    if (isDestroyed) return
    try {
        synchronized(lock) {
            val anchor = currentPage
            var guard = 0
            while (pageCache.size > 3 && guard++ < 16) {
                val before = pageCache.size
                evictFarthestPage(anchor)
                if (pageCache.size == before) break
            }
        }
        pager.state.invalidate()
    } catch (_: Exception) {}
}
// KMK <--

// KMK -->
/**
 * Signed page distance from [anchor] to [page]: positive ahead, negative behind.
 * Same-chapter pages use index math; pages in the adjacent chapters count inward
 * from the shared chapter edge; a transition page bridging the anchor chapter is
 * 0 (drawn next, never a victim while anything else exists). Null when [page]
 * belongs to no chapter adjacent to the anchor - unrelated shells that should be
 * evicted first. Gap-proof by construction: no cache walk, only chapter/index
 * math, so holes from earlier evictions cannot mismeasure it.
 */
internal fun WebGpuViewer.pageDistance(anchor: ViewerReaderPage, page: ViewerPage): Int? {
    val anchorChapter = anchor.page.chapter
    return when (page) {
        is ViewerReaderPage -> {
            val chapter = page.page.chapter
            when {
                chapter === anchorChapter -> page.page.index - anchor.page.index
                chapter === anchor.nextChapter -> {
                    val edge = anchorChapter.pages?.size?.let { it - 1 - anchor.page.index } ?: 0
                    edge + 1 + page.page.index
                }
                chapter === anchor.prevChapter -> {
                    val edge = chapter.pages?.size?.let { it - 1 - page.page.index } ?: 0
                    -(anchor.page.index + 1 + edge)
                }
                else -> null
            }
        }
        is ViewerTransitionPage ->
            if (page.prevChapter === anchorChapter || page.nextChapter === anchorChapter) 0 else null
        else -> null
    }
}

/**
 * Signed page distance from a [ViewerTransitionPage] anchor to [page].
 *
 * Transition anchors sit exactly on chapter boundaries, where the continuous viewer
 * spends whole reading sessions, so they need the same gap-proof math as reader
 * anchors: without it every page measured null (outside-window), eviction fell back
 * to oldest-first, and half-visible decoded pages next to the transition were eaten
 * and re-shown as placeholders in a loop (constant flicker at chapter edges).
 * Pages in the next chapter count up from +1, pages in the previous chapter count
 * down from -1, and a transition sharing either bridge chapter sits at 0.
 */
internal fun WebGpuViewer.pageDistance(anchor: ViewerTransitionPage, page: ViewerPage): Int? {
    val prev = anchor.prevChapter
    val next = anchor.nextChapter
    return when (page) {
        is ViewerReaderPage -> {
            val chapter = page.page.chapter
            when {
                chapter === next -> page.page.index + 1
                chapter === prev -> {
                    val size = chapter.pages?.size
                    if (size == null) -1 else -(size - page.page.index)
                }
                else -> null
            }
        }
        is ViewerTransitionPage ->
            if (page.prevChapter === prev || page.prevChapter === next ||
                page.nextChapter === prev || page.nextChapter === next
            ) {
                0
            } else {
                null
            }
        else -> null
    }
}

/**
 * Signed page distance from any [anchor] to [page]. Dispatches to the reader or
 * transition overload above; null when [page] belongs to no chapter adjacent to
 * the anchor - unrelated shells that should be evicted first.
 */
internal fun WebGpuViewer.pageDistance(anchor: ViewerPage, page: ViewerPage): Int? = when (anchor) {
    is ViewerReaderPage -> pageDistance(anchor, page)
    is ViewerTransitionPage -> pageDistance(anchor, page)
    else -> null
}
// KMK <--

/**
 * Gets or creates a page. Thread-safe.
 * @param referencePage The page to use as reference for eviction (defaults to currentPage)
 */
fun WebGpuViewer.getPage(page: ReaderPage, referencePage: ViewerPage? = null): ViewerPage {
    val key = PageKey.Reader(page.chapter.chapter.id, page.index)
    return synchronized(lock) {
        findInCache(key) ?: ViewerReaderPage(this, page).also { newPage ->
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

fun WebGpuViewer.getPage(
    prevChapter: ReaderChapter?,
    nextChapter: ReaderChapter?,
    referencePage: ViewerPage? = null,
): ViewerPage {
    val key = PageKey.Transition(prevChapter?.chapter?.id, nextChapter?.chapter?.id)
    return synchronized(lock) {
        findInCache(key) ?: ViewerTransitionPage(this, prevChapter, nextChapter).also { newPage ->
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
 * Start loading a page and set up listener to re-queue when ready.
 * Hardened: checks destroyed, handles loader null, cleans up jobs on eviction/cancel,
 * and surfaces load errors as tap-retry ErrorPage without leaking collectors.
 */
internal fun WebGpuViewer.startPageLoad(page: ViewerReaderPage) {
    if (isDestroyed) return
    val viewer = this
    val loader = page.page.chapter.pageLoader ?: run {
        synchronized(lock) { if (pageInCache(page)) page.state = PageState.IDLE }
        return
    }

    if (page.page.status == Page.State.Ready) {
        synchronized(lock) {
            if (!pageInCache(page)) return
            page.state = PageState.IDLE
        }
        if (!page.isDecoded) {
            queueForDecode(page, prioritize = currentPage?.let { pageKey(it) == pageKey(page) } ?: false)
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
                        // KMK --> Set under the lookup's lock, or an eviction's cleanup() lands between.
                        synchronized(lock) {
                            if (!pageInCache(page)) return@collect
                            (page.imagePage as? ProgressPage)?.apply {
                                progress = value.coerceIn(0, 100) / 100f
                                try {
                                    invalidate()
                                } catch (_: Exception) {
                                }
                            }
                        }
                        // KMK <--
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }

            try {
                page.page.statusFlow.takeWhile { state ->
                    // KMK --> Evicted: stop watching, rather than holding the page until the download ends.
                    if (!synchronized(lock) { pageInCache(page) }) return@takeWhile false
                    // KMK <--
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
                        }
                    }
                    is Page.State.Error -> {
                        val message = s.error.message?.takeIf { it.isNotBlank() } ?: "Failed to load page"
                        val oldImagePage = page.imagePage
                        if (!oldImagePage.destroyed) {
                            page.imagePage = ErrorPage(viewer, message, page.spreadPosition)
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

internal suspend fun WebGpuViewer.decodeReaderPage(page: ViewerReaderPage) {
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

        val isLowRam = isLowRamDevice
        val maxPageBytes = if (isLowRam) 40 * 1024 * 1024 else 80 * 1024 * 1024
        val decodeBytes: ByteArray? = try {
            val bytes = input.readBytes()
            if (bytes.size > maxPageBytes) throw Exception("Page too large: ${bytes.size} bytes >${maxPageBytes / (1024 * 1024)}MB")
            if (bytes.isEmpty()) null else bytes
        } catch (e: OutOfMemoryError) {
            System.gc()
            null
        } catch (_: Exception) {
            null
        }
        if (decodeBytes == null) throw Exception("Failed to read page bytes")
        // KMK -->
        // Display-time upscale; translation and spread matching below keep the originals.
        val displayBytes = UpscaleReaderHook.upscaleDisplayBytes(
            page.page.chapter.chapter.manga_id,
            decodeBytes,
        ) ?: decodeBytes
        // KMK <--
        val isJxlBytes = try {
            tachiyomi.core.common.util.system.ImageUtil.findImageType(decodeBytes.inputStream()) == tachiyomi.core.common.util.system.ImageUtil.ImageType.JXL
        } catch (_: Exception) {
            false
        }
        if (isLowRam && isJxlBytes && decodeBytes.size > 16 * 1024 * 1024) {
            throw Exception("JXL too large for low-RAM: ${decodeBytes.size} bytes >16MB (downsample via Tachiyomi decoder instead)")
        }
        // Translation gate: only small-enough pages are sent to LLM/cache
        val translationBytes: ByteArray? = if (decodeBytes.size in 1..32 * 1024 * 1024) decodeBytes else null

        // KMK -->
        // Single shared source array: decode, spread height-match bytes, and translation input all
        // reference this one array - nothing below re-reads the page stream (no refetch).
        val dualModeForTags = isDualPageMode()
        // One Kim parse per decode serves both EXIF orientation (always honored) and the
        // spread side tag (dual mode only - single-page display never pairs, so the tag
        // lookup is skipped there). EXIF lives in the original bytes: the display hook
        // below may strip it, so orientation is read here, before any transform.
        var exifOrientation = 1
        val spreadTag: SpreadPosition? = try {
            val metadata = Kim.readMetadata(decodeBytes.inputStream(), decodeBytes.size.toLong())
            if (metadata != null) {
                exifOrientation = metadata.findStringValue(TiffTag.TIFF_TAG_ORIENTATION)
                    ?.let { raw -> Regex("\\d+").find(raw)?.value?.toIntOrNull() }
                    ?.takeIf { it in 1..8 } ?: 1
            }
            if (dualModeForTags) {
                when (metadata?.findStringValue(TiffTag.TIFF_TAG_PAGE_NAME)) {
                    "Left" -> SpreadPosition.LEFT
                    "Right" -> SpreadPosition.RIGHT
                    // Left untouched for a file that names no side - [spreadPosition] then derives one.
                    null -> null
                    else -> SpreadPosition.SINGLE
                }
            } else {
                // Single-page display never pairs: leave untagged so [spreadPosition] derives
                // geometrically on rotation into dual mode instead of reusing a stale tag.
                null
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            System.gc()
            null
        }
        page.taggedSpreadPosition = spreadTag
        noteExifOrientation(page, exifOrientation)

        // Store bytes for height-matching regardless of SINGLE tag — pages decoded before
        // viewport layout (width <8) may be tagged SINGLE initially but become LEFT/RIGHT
        // after rotation/layout, and webp pages decoded via fallback need bytes for retry.
        // Low-RAM devices retain nothing: up to 32MB per cached page is unaffordable there,
        // and the spread then simply skips height-matching instead of OOMing.
        if (!isLowRam && config.matchDoublePageHeights && decodeBytes.size in 1..32 * 1024 * 1024) {
            page.spreadBytes = decodeBytes
        } else {
            page.spreadBytes = null
        }

        val dec = try {
            ImageDecoder.new(displayBytes.inputStream()).also { d ->
                if (d.pages <= 0) {
                    try {
                        d.close()
                    } catch (_: Exception) {}
                    throw Exception("No pages reported by decoder")
                }
            }
        } catch (e: ImageDecoder.UnknownFormatException) {
            throw Exception("Unsupported image format: ${e.message}", e)
        } catch (e: ImageDecoder.DecodeException) {
            throw Exception("ImageDecoder init failed: ${e.message}", e)
        } catch (e: Exception) {
            throw Exception("ImageDecoder init failed: ${e.message}", e)
        }

        val pageCount = dec.pages

        if (pageCount == 0) throw Exception("No frames decoded")

        val backgroundColor = if (config.automaticBackground) null else readerBackgroundColor()

        val firstFrame = dec.decodeNext()
        if (firstFrame.width <= 4 || firstFrame.height <= 4) {
            try {
                dec.close()
            } catch (_: Exception) {}
            throw Exception("Image too small ${firstFrame.width}x${firstFrame.height}, skipping GPU upload (avoids gralloc 0x3b on Adreno)")
        }
        // KMK --> Honor EXIF orientation at decode: the native decoder hands back raw
        // pixels, so a camera-scan page would otherwise display sideways and pair with
        // the wrong aspect. Single-frame path only - an animated frame stack with an
        // orientation tag is vanishingly rare and rotating every frame would multiply
        // transient memory. Falls back to unrotated pixels on any failure.
        var framePixels = firstFrame.image
        var frameWidth = firstFrame.width
        var frameHeight = firstFrame.height
        if (pageCount == 1 && exifOrientation != 1) {
            val rotated = rotateRgbaForExif(framePixels, frameWidth, frameHeight, exifOrientation)
            framePixels = rotated.buffer
            frameWidth = rotated.width
            frameHeight = rotated.height
        }
        // KMK <--

        val imagePage = if (pageCount == 1) {
            val isJxl = dec.format == "jxl"
            val trimColors = if (!isJxl && config.imageCropBorders && !isDualPageMode()) {
                listOf(
                    floatArrayOf(1f, 1f, 1f),
                    floatArrayOf(0f, 0f, 0f),
                )
            } else {
                null
            }

            val firstImage = Image(
                framePixels,
                frameWidth,
                frameHeight,
                createMipMaps = true,
                trimColors = trimColors,
                trimThreshold = 0.15f,
                backgroundColor = backgroundColor,
            )

            ImagePage.ImageSingle(firstImage)
        } else {
            val frames = ArrayList<Pair<Image, Int>>(pageCount)

            // KMK --> Built frames hold uploaded textures, and ImageSingle owns the only teardown.
            fun discardFrames() {
                if (frames.isNotEmpty()) ImagePage.ImageSingle(frames).cleanup()
            }
            // KMK <--

            val firstImage = Image(
                firstFrame.image,
                firstFrame.width,
                firstFrame.height,
                createMipMaps = false,
                backgroundColor = backgroundColor,
            )

            frames.add(Pair(firstImage, firstFrame.duration))

            // KMK -->
            try {
                for (i in 1 until pageCount) {
                    // Under lock: a decode this long gives an eviction's cleanup() time to land.
                    val stillWanted = synchronized(lock) {
                        pageInCache(page).also { inCache ->
                            if (inCache) {
                                (page.imagePage as? ProgressPage)?.apply {
                                    progress = i.toFloat() / pageCount
                                    invalidate()
                                }
                            }
                        }
                    }

                    // Scrolled past: the frames left are work nothing will draw.
                    if (!stillWanted) {
                        discardFrames()
                        try {
                            dec.close()
                        } catch (_: Exception) {}
                        return
                    }

                    val frame = dec.decodeNext()
                    if (frame.width <= 4 || frame.height <= 4) {
                        try {
                            dec.close()
                        } catch (_: Exception) {}
                        throw Exception("Frame too small ${frame.width}x${frame.height}, skipping GPU upload")
                    }
                    val image = Image(
                        frame.image,
                        frame.width,
                        frame.height,
                        createMipMaps = false,
                        backgroundColor = firstImage.backgroundColor,
                    )
                    frames.add(Pair(image, frame.duration))
                }
            } catch (e: Throwable) {
                discardFrames()
                throw e
            }
            // KMK <--
            try {
                dec.close()
            } catch (_: Exception) {}

            ImagePage.ImageSingle(frames)
        }

        synchronized(lock) {
            if (pageInCache(page) && !page.isDecoded && !page.imagePage.destroyed) {
                val oldImagePage = page.imagePage
                page.imagePage = imagePage
                noteIfLone(page)
                page.state = PageState.IDLE
                oldImagePage.cleanup()
                val decodedSingle = page.imagePage as? ImagePage.ImageSingle
                if (decodedSingle != null) {
                    // KMK -->
                    if (!decodedSingle.isAnimated) decodedSingle.highQuality = !config.fastRender
                    if (!isDualPageMode()) {
                        if (!applyWideZoomIfNeeded(decodedSingle)) {
                            applyFitModeAnchor(decodedSingle)
                        }
                    }
                    applyDoubleTapZoomPolicy(decodedSingle)
                    // KMK <--
                }
                pager.state.invalidate()
                // Hook AI translation: baked Image replacement (handles dual-page height-match, no overlay drift)
                translationBytes?.let { bytes ->
                    scheduleTranslation(page, bytes)
                }
            } else {
                if (pageInCache(page)) page.state = PageState.IDLE
            }
        }
    }
}

/**
 * The viewer library always performs its built-in double-tap zoom, so when the
 * preference is disabled the page's max scale is clamped to its home scale - the
 * zoom animation then lands where it started. The paged "disable zoom in" pref
 * clamps the same way, and the library additionally caps pinch/double-tap-drag
 * gestures at a restricted maxScale. The library sentinel -1f restores the computed default.
 */
internal fun WebGpuViewer.applyDoubleTapZoomPolicy(page: ImagePage.ImageSingle) {
    if (isDestroyed || isContinuous) return
    if (config.doubleTapZoom && !config.disableZoomIn) {
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

// KMK -->
internal fun WebGpuViewer.applyWideZoomIfNeeded(page: ImagePage.ImageSingle): Boolean {
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

    // Don't zoom if the trimmed page already fits at original scale.
    if (page.trimWidth <= screenW) return false

    // Wide page: half the (trimmed) image width is wider than the screen aspect ratio.
    val aspectRatio = minOf(
        page.trimWidth.toFloat() / page.trimHeight.toFloat(),
        image.width.toFloat() / image.height.toFloat(),
    )

    // not wide enough
    if (aspectRatio < 1.1) return false

    if (aspectRatio <= 2f * screenW.toFloat() / screenH) return false

    // Scale to fit half the image width to the full screen width
    val wideScale = screenW.toFloat() / (page.trimWidth / 2f)

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

internal fun WebGpuViewer.applyFitModeAnchor(page: ImagePage.ImageSingle) {
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
internal fun WebGpuViewer.preloadPage(page: ViewerPage, prioritize: Boolean = false) {
    synchronized(lock) {
        val cachedPage = findInCache(pageKey(page)) ?: return
        if (cachedPage is ViewerReaderPage) {
            queueForDecode(cachedPage, prioritize)
        }
    }
}

internal fun WebGpuViewer.preloadPages(page: ViewerPage) {
    // Get the canonical page from cache to ensure we're working with current data
    val key = pageKey(page)
    val cachedPage = synchronized(lock) { findInCache(key) } ?: return

    // Priority order: current (highest), next1, next2, prev1, prev2 (lowest).
    // The worker takes from the back of the queue while queueForDecode appends
    // non-priority pages at the front, so iterate nearest-first: each addFirst
    // lands in front of the previous one and the worker reaches near pages
    // before far ones. (Reversed iteration decoded far pages first, so turning
    // back arrived at placeholders still waiting behind pages further out.)

    // Add prev pages (lowest priority)
    val prevPages = mutableListOf<ViewerPage>()
    var p: ViewerPage? = cachedPage
    for (i in 0 until preloadBehind) {
        p = p?.prev ?: break
        prevPages.add(p)
    }
    prevPages.forEach { preloadPage(it) }

    // Add next pages (medium priority)
    val nextPages = mutableListOf<ViewerPage>()
    p = cachedPage
    for (i in 0 until preloadAhead) {
        p = p?.next ?: break
        nextPages.add(p)
    }
    nextPages.forEach { preloadPage(it) }

    // Add current spread last with priority flag (highest priority in LIFO)
    // Also preload the paired page
    cachedPage.next?.let { preloadPage(it, prioritize = true) }
    preloadPage(cachedPage, prioritize = true)
}
// KMK <--
// Mihon <--
