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
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.ZoomStartPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Queue a page for decoding if not already queued/loading/decoded.
 * If prioritize=true and page is already queued, moves it to front.
 * Must be called while holding lock.
 */
internal fun WebGpuViewer.queueForDecode(page: ViewerReaderPage, prioritize: Boolean = false) {
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

/**
 * Evicts the page farthest from reference. Must be called while holding lock.
 * Hardened: coerces cacheSize, handles null current via newest entry, and avoids
 * infinite loops when reference is not in cache.
 */
internal fun WebGpuViewer.evictFarthestPage(reference: ViewerPage? = null) {
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

        // Capture bytes once; keep full decode bytes while gating translation on 32MB cap.
        // Previous code nulled bytes on oversize and fell back to already-consumed input, breaking decode.
        val decodeBytes: ByteArray? = try {
            input.readBytes()
        } catch (e: OutOfMemoryError) {
            System.gc()
            null
        } catch (_: Exception) {
            null
        }
        if (decodeBytes == null) throw Exception("Failed to read page bytes")
        // Translation gate: only small-enough pages are sent to LLM/cache
        val translationBytes: ByteArray? = if (decodeBytes.size in 1..32 * 1024 * 1024) decodeBytes else null

        page.spreadPosition = run {
            val tag = try {
                Kim.readMetadata(decodeBytes.inputStream(), decodeBytes.size.toLong())
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
        }

        if (config.matchDoublePageHeights &&
            page.spreadPosition != SpreadPosition.SINGLE &&
            decodeBytes.size in 1..32 * 1024 * 1024
        ) {
            page.spreadBytes = decodeBytes
        } else {
            page.spreadBytes = null
        }

        val dec = try {
            ImageDecoder.new(decodeBytes.inputStream())
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
 * zoom animation then lands where it started. Pinch zoom sets scale directly and
 * never consults this value. The library sentinel -1f restores the computed default.
 */
internal fun WebGpuViewer.applyDoubleTapZoomPolicy(page: ImagePage.ImageSingle) {
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
// KMK <--
// Mihon <--
