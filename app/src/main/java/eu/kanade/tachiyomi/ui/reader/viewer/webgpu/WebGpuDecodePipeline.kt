// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.Image.Companion.invoke
import ca.mpreg.webgpuviewer.viewer.ImagePage
import de.stefan_oltmann.kim.Kim
import de.stefan_oltmann.kim.android.readMetadata
import de.stefan_oltmann.kim.format.tiff.constant.TiffTag
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

// The two halves of turning one `ViewerReaderPage` into a decoded `ImagePage.ImageSingle`:
// getting the bytes (`startPageLoad`) and building the GPU image (`decodeReaderPage`).
//
// Both are reached only from the viewer's single decode worker, one page at a time. Anything that
// decides *which* pages get here lives in `WebGpuDecodeQueue`; anything about keeping the working
// set bounded lives in `WebGpuPageCache`.

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
            // KMK --> Always clear LOADING, even for a page that left the cache. The old guard
            // returned early on !pageInCache, which is exactly the eviction case that stranded the
            // state: evictFarthestPage resets the shell it removes, but a shell evicted while this
            // coroutine was mid-flight kept LOADING, and getPage hands that same shell back on
            // re-entry. Resetting unconditionally costs nothing for an evicted page (nothing reads
            // it) and is the whole difference between recoverable and permanently wedged.
            // KMK <--
            synchronized(lock) {
                if (page.state == PageState.LOADING) {
                    page.state = PageState.IDLE
                }
                if (isDestroyed || !pageInCache(page)) return@synchronized
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

    val trace = activeDecode

    traceStage(trace, "stream")
    val stream = try {
        page.page.stream?.invoke()
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "page.stream failed index ${page.page.index}" }
        null
    } ?: run {
        synchronized(lock) { if (pageInCache(page)) page.state = PageState.IDLE }
        trace?.complete("no-stream")
        return
    }

    stream.use { input ->
        synchronized(lock) {
            if (isDestroyed || !pageInCache(page) || page.isDecoded) {
                if (pageInCache(page) && !isDestroyed) page.state = PageState.IDLE
                trace?.complete("abandoned")
                return
            }
        }

        val isLowRam = isLowRamDevice
        val maxPageBytes = if (isLowRam) 40 * 1024 * 1024 else 80 * 1024 * 1024
        // The read is the first unbounded blocking call on the worker's single thread, so it gets a
        // stage of its own rather than being folded into the surrounding work.
        traceStage(trace, "readBytes", "cap=${maxPageBytes / (1024 * 1024)}MB lowRam=$isLowRam")
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
        if (decodeBytes == null) {
            trace?.complete("no-bytes")
            throw Exception("Failed to read page bytes")
        }
        trace?.bytes = decodeBytes.size

        // KMK --> Refuse bytes no decoder should be trusted with, before any native work sees them.
        //
        // Placed here on purpose: this is the last point before the upscale hook and the native
        // decoder, both of which are known to misbehave on malformed input, and both of which run on
        // the single thread every other page queues behind. A fault this catches is recoverable -
        // the page is dropped and its source asked again - whereas the same bytes reaching the
        // decoder may not come back at all.
        traceStage(trace, "validate", "bytes=${decodeBytes.size}")
        val verdict = PageBytesValidator.inspect(decodeBytes)
        if (!verdict.isAcceptable) {
            val detail = "${verdict.fault} format=${verdict.format?.label ?: "?"} ${verdict.detail ?: ""}".trim()
            logcat(LogPriority.ERROR) {
                "Rejected page bytes ch=${page.page.chapter.chapter.id}/i=${page.page.index}: $detail"
            }
            requerySuspectPage(page, detail)
            trace?.complete("rejected:${verdict.fault}")
            throw Exception("Corrupt page data: $detail")
        }
        traceStage(trace, "upscale-check", "format=${verdict.format?.label} dims=${verdict.width}x${verdict.height}")
        // KMK <--
        // KMK -->
        // Display-time upscale; translation and spread matching below keep the originals.
        // Native inference, on the worker's only thread: the second unbounded call in this path.
        traceStage(trace, "upscale")
        // KMK -->
        // Display-time only: translation, EXIF and spread matching below all read decodeBytes, so
        // the chain's output never becomes the page's canonical bytes.
        val displayBytes = applyByteTransforms(
            WebGpuExtensions.active(),
            WebGpuPageRef(
                chapterId = page.page.chapter.chapter.id ?: -1L,
                pageIndex = page.page.index,
                mangaId = page.page.chapter.chapter.manga_id ?: -1L,
            ),
            decodeBytes,
        )
        trace?.transformed = (displayBytes !== decodeBytes)
        // KMK <--
        // KMK <--
        traceStage(trace, "jxl-sniff")
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
        traceStage(trace, "metadata")
        var exifOrientation = 1
        val spreadTag: SpreadPosition? = try {
            val metadata = Kim.readMetadata(decodeBytes.inputStream(), decodeBytes.size.toLong())
            if (metadata != null) {
                exifOrientation = metadata.findStringValue(TiffTag.TIFF_TAG_ORIENTATION)
                    ?.let { raw -> exifOrientationDigits.find(raw)?.value?.toIntOrNull() }
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

        traceStage(trace, "decoderInit", "bytes=${displayBytes.size}")
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
        trace?.format = runCatching { dec.format }.getOrNull()

        if (pageCount == 0) throw Exception("No frames decoded")

        val backgroundColor = if (config.automaticBackground) null else readerBackgroundColor()

        // The prime suspect for a wedged worker: a native decode of malformed input that loops
        // instead of failing. This is the stage a stall report will name if the bytes were plausible
        // enough to get past validation.
        traceStage(trace, "decodeNext", "frames=$pageCount fmt=${trace?.format}")
        val firstFrame = dec.decodeNext()
        trace?.dimensions = "${firstFrame.width}x${firstFrame.height}"
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
        // "Rotate to fit" from the paged reader settings. The pager rotates the encoded bytes
        // before decoding; this path has no encode step, so the quarter turn is applied to the
        // decoded pixels. Without it a landscape page paired into a spread is fitted to half the
        // screen width, leaving a black band above and below that only zooming clears.
//
// After EXIF, so "wide" means wide as displayed. Single-frame only, as above. A quarter turn is
// EXIF orientation 6 clockwise and 8 anticlockwise.
        if (pageCount == 1 && config.dualPageRotateToFit && frameWidth > frameHeight) {
            val quarterTurn = if (config.dualPageRotateToFitInvert) 8 else 6
            val turned = rotateRgbaForExif(framePixels, frameWidth, frameHeight, quarterTurn)
            framePixels = turned.buffer
            frameWidth = turned.width
            frameHeight = turned.height
        }
        // KMK <--

        val imagePage = if (pageCount == 1) {
            // KMK --> Nothing may be uploaded for a page nobody will draw. The metadata parse above
            // is the expensive part of a decode, and an eviction landing during it is routine - the
            // split of a tall page replaces a shell mid-decode, and a fast scroll evicts for the
            // same reason. Uploading here anyway allocated a full texture plus mip chain for a page
            // that was already out of the cache, and nothing downstream ever called cleanup() on it.
            if (!synchronized(lock) { pageInCache(page) }) {
                page.wantedByRender = false
                try {
                    dec.close()
                } catch (_: Exception) {}
                return
            }
            val isJxl = dec.format == "jxl"
            val trimColors = if (!isJxl && config.imageCropBorders && !isDualPageMode()) {
                listOf(
                    floatArrayOf(1f, 1f, 1f),
                    floatArrayOf(0f, 0f, 0f),
                )
            } else {
                null
            }

            traceStage(trace, "gpuUpload", "dims=${frameWidth}x$frameHeight mipmaps=true")
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
                    // The page keeps whatever state it has rather than being force-reset here -
                    // it is out of the cache, so a fresh shell will be built when it is next
                    // wanted, and fetchPage's ensureDecoding queues that one. Marking it wanted
                    // first is what makes that recovery certain if it is still cached.
                    if (!stillWanted) {
                        page.wantedByRender = false
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

        // KMK --> Adopt the finished page, or free it. `Image(...)` has already uploaded a texture
        // and its mip chain by this point, and the swap below is the only thing that will ever own
        // them. When the page left the cache while it was being built - an eviction, or a split
        // that superseded it - the old code simply fell through and dropped the reference, leaking
        // every uploaded texture of a decode nothing will ever show. That is ~47MB of GPU memory
        // per wasted split-segment decode, on a path that a split triggers by construction.
        traceStage(trace, "adopt")
        var adopted = false
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
                    applyZoomPolicy(decodedSingle)
                    // KMK <--
                }
                adopted = true
            } else if (pageInCache(page)) {
                page.state = PageState.IDLE
            }
        }
        if (!adopted) {
            page.wantedByRender = false
            imagePage.cleanup()
            trace?.complete("not-adopted")
            return
        }
        trace?.complete("decoded")
        pager.state.invalidate()
        // KMK --> Post-decode compartment. Translation is one extension in it; anything else that
        // needs to act on a freshly decoded page hooks here rather than at each of the sites that
        // decode, which is why this is called once here and not wired per feature.
        translationBytes?.let { bytes ->
            runPageDecodedExtensions(page, bytes)
        }
        // KMK <--
    }
}
