// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.Image.Companion.invoke
import ca.mpreg.webgpuviewer.viewer.ImagePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap

// Dual-page spread height matching. Split out of the spread-pairing code because it is a
// self-contained state machine: decide whether a spread needs a pass, coalesce the retries while a
// side is still decoding, run exactly one rescale, and swap the result back in.
//
// Every decision here is pure and lives in `resolveSpreadHeightMatch`/`shouldAttemptSpreadRescale`;
// everything else is the retry bookkeeping and the rescale itself.

/**
 * One coalesced height-match retry per anchor page: a new request cancels the pending one,
 * so slow decodes pile up a single delayed pass instead of stacking fire-and-forget loops.
 * Weak keys so an evicted anchor cannot leak its viewer; entries remove themselves on fire.
 */
private val spreadHeightRetries: MutableMap<ViewerReaderPage, Job> =
    Collections.synchronizedMap(WeakHashMap())

/** Bounded attempts per anchor so an undecodable spread can never retry forever. */
private val spreadHeightAttempts: MutableMap<ViewerReaderPage, Int> =
    Collections.synchronizedMap(WeakHashMap())

internal const val MAX_SPREAD_HEIGHT_ATTEMPTS = 20

internal fun WebGpuViewer.retrySpreadHeightMatchSoon(
    anchorPage: ViewerReaderPage,
    spread: ImagePage.ImageSpread,
    nextReaderPage: ViewerReaderPage?,
    delayMs: Long = 150,
) {
    if (isDestroyed || !config.matchDoublePageHeights) return
    // Read-increment-write in one atomic block: two separate synchronized sections let
    // a concurrent caller observe a stale count and under/over-count attempts.
    val attempts = synchronized(spreadHeightAttempts) {
        val next = (spreadHeightAttempts[anchorPage] ?: 0) + 1
        spreadHeightAttempts[anchorPage] = next
        next
    }
    if (attempts > MAX_SPREAD_HEIGHT_ATTEMPTS) {
        cancelSpreadHeightRetry(anchorPage)
        return
    }
    val viewer = this
    val job = scope.launch {
        try {
            delay(delayMs)
        } catch (_: CancellationException) {
            return@launch
        }
        synchronized(spreadHeightRetries) { spreadHeightRetries.remove(anchorPage) }
        if (viewer.isDestroyed || !viewer.config.matchDoublePageHeights) return@launch
        // Evicted anchor: terminal noop. Retrying a dead spread was the persistent storm.
        val anchored = synchronized(viewer.lock) { viewer.pageInCache(anchorPage) }
        if (!anchored) return@launch
        viewer.maybeScheduleSpreadHeightMatch(anchorPage, spread, nextReaderPage)
    }
    synchronized(spreadHeightRetries) {
        spreadHeightRetries[anchorPage]?.cancel()
        spreadHeightRetries[anchorPage] = job
    }
    job.invokeOnCompletion {
        synchronized(spreadHeightRetries) {
            if (spreadHeightRetries[anchorPage] === job) spreadHeightRetries.remove(anchorPage)
        }
    }
}

internal fun WebGpuViewer.cancelSpreadHeightRetry(page: ViewerReaderPage) {
    synchronized(spreadHeightRetries) { spreadHeightRetries.remove(page)?.cancel() }
}

internal fun WebGpuViewer.resetSpreadHeightRetry(page: ViewerReaderPage) {
    cancelSpreadHeightRetry(page)
    synchronized(spreadHeightAttempts) { spreadHeightAttempts.remove(page) }
}

/**
 * Dual-page spread height matching: one deterministic pass rescaling the shorter side up
 * to the taller side's height. The rescaled image replaces that side's
 * [ImagePage.ImageSingle], and the spread recomposes from slot identity on the next fetch.
 * Never shrinks the taller side down (which produced tiny spreads on e-ink resume) and
 * never rescales a side to its own height. Transient states (partner not decoded yet,
 * sub-gralloc dims, position race) funnel into a single coalesced retry; terminal states
 * (zero dims, equal heights, evicted bytes, in-flight rescale) return without scheduling.
 */
internal fun WebGpuViewer.maybeScheduleSpreadHeightMatch(
    anchorPage: ViewerReaderPage,
    spread: ImagePage.ImageSpread,
    nextReaderPage: ViewerReaderPage?,
) {
    if (isDestroyed) return
    if (!config.matchDoublePageHeights) {
        resetSpreadHeightRetry(anchorPage)
        return
    }

    val leftSide = spread.left
    val rightSide = spread.right
    if (leftSide is ImagePage.Render || rightSide is ImagePage.Render) {
        resetSpreadHeightRetry(anchorPage)
        return
    }
    val leftImage = (leftSide as? ImagePage.ImageSingle)?.image
    val rightImage = (rightSide as? ImagePage.ImageSingle)?.image
    if (leftImage == null || rightImage == null) {
        retrySpreadHeightMatchSoon(anchorPage, spread, nextReaderPage)
        return
    }
    // Zero-guard: destroyed/placeholder dims are a hard noop, never a retry or divide-by-zero.
    if (leftImage.width <= 0 || leftImage.height <= 0 || rightImage.width <= 0 || rightImage.height <= 0) {
        resetSpreadHeightRetry(anchorPage)
        return
    }
    if (leftImage.height == rightImage.height) {
        resetSpreadHeightRetry(anchorPage)
        return
    }
    if (!isSpreadSideViable(leftImage.width, leftImage.height) ||
        !isSpreadSideViable(rightImage.width, rightImage.height)
    ) {
        retrySpreadHeightMatchSoon(anchorPage, spread, nextReaderPage)
        return
    }

    val plan = resolveSpreadHeightMatch(
        leftImage.width,
        leftImage.height,
        rightImage.width,
        rightImage.height,
    ) ?: run {
        resetSpreadHeightRetry(anchorPage)
        return
    }

    val shorterPage: ViewerReaderPage? = when {
        plan.shorterIsLeft && anchorPage.spreadPosition == SpreadPosition.LEFT -> anchorPage
        plan.shorterIsLeft && nextReaderPage?.spreadPosition == SpreadPosition.LEFT -> nextReaderPage
        !plan.shorterIsLeft && anchorPage.spreadPosition == SpreadPosition.RIGHT -> anchorPage
        !plan.shorterIsLeft && nextReaderPage?.spreadPosition == SpreadPosition.RIGHT -> nextReaderPage
        else -> null
    }

    if (shorterPage == null) {
        retrySpreadHeightMatchSoon(anchorPage, spread, nextReaderPage)
        return
    }
    // Both fields are volatile, and this runs once per spread per rendered frame - it is reached
    // from buildSpreadPage, which fetchPage(0) calls every frame. Taking the viewer's global lock
    // here put a shared monitor on the render path, contending with the decode worker's cleanup and
    // every getPage. Reading them plainly is enough: this only decides whether to *offer* the
    // rescale, and scheduleSpreadHeightMatch re-checks rescaleInFlight under the lock before
    // claiming the page, so a torn read here can at worst queue one redundant attempt that is then
    // dropped there.
    if (!shouldAttemptSpreadRescale(shorterPage.spreadBytes != null, shorterPage.rescaleInFlight, plan)) return
    scheduleSpreadHeightMatch(shorterPage, plan.targetHeight)
}

internal fun WebGpuViewer.scheduleSpreadHeightMatch(sourcePage: ViewerReaderPage, targetHeight: Int) {
    if (isDestroyed) return
    // Single 1..8192 clamp; below-gralloc heights stay a hard noop (never a 0-height upload).
    val safeTarget = targetHeight.coerceIn(1, SPREAD_MAX_DIM)
    if (safeTarget < SPREAD_MIN_SIDE_DIM) {
        synchronized(lock) { sourcePage.rescaleInFlight = false }
        return
    }
    synchronized(lock) {
        if (isDestroyed || sourcePage.rescaleInFlight) return
        sourcePage.rescaleInFlight = true
    }

    scope.launch(decodeDispatcher) {
        var scaledImage: Image? = null
        var translationSource: ByteArray? = null
        try {
            try {
                val bytes = synchronized(lock) { sourcePage.spreadBytes }
                if (bytes != null) {
                    translationSource = bytes
                    scaledImage = rescaleImageToHeight(bytes, safeTarget)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                logcat(LogPriority.ERROR) { "Spread height-match OOM target $safeTarget" }
                System.gc()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Spread height-match rescale failed" }
            }

            var swapped = false
            synchronized(lock) {
                if (scaledImage != null && pageInCache(sourcePage)) {
                    val scaledSingle = ImagePage.ImageSingle(scaledImage)
                    // Reapply the full decode-time zoom stack, not only the double-tap policy,
                    // so a swapped side keeps fit-mode/wide-zoom anchoring after e-ink resume.
                    applyZoomPolicy(scaledSingle)
                    val oldImagePage = sourcePage.imagePage
                    sourcePage.cleanupCompare()
                    sourcePage.imagePage = scaledSingle
                    sourcePage.spreadBytes = null
                    oldImagePage.cleanup()
                    swapped = true
                } else {
                    sourcePage.spreadBytes = null
                    scaledImage?.let { stale -> ImagePage.ImageSingle(stale).cleanup() }
                }
            }
            // Terminal state reached either way: drop any coalesced retry for this side. The
            // anchor-keyed retry, if any, self-terminates on equal heights at the next pass.
            resetSpreadHeightRetry(sourcePage)

            if (swapped) {
                pager.state.invalidate()
                translationSource?.let { runPageDecodedExtensions(sourcePage, it) }
            }
        } finally {
            // Cancellation skipped the reset above, because the catch rethrows before reaching it.
            // rescaleInFlight gates both scheduleSpreadHeightMatch and the plan check above, so a
            // flag stranded true cancels every future height-match for this page until it is evicted -
            // which is why this is a finally and not another line in the happy path.
            synchronized(lock) { sourcePage.rescaleInFlight = false }
        }
    }
}

internal suspend fun WebGpuViewer.rescaleImageToHeight(bytes: ByteArray, targetHeight: Int): Image {
    require(targetHeight in 8..8192) { "targetHeight out of range: $targetHeight (must be >=8 to avoid gralloc 0x3b)" }
    if (bytes.size > 32 * 1024 * 1024) throw IllegalArgumentException("bytes too large for rescale: ${bytes.size}")
    var dec: ImageDecoder? = null
    var fallbackBitmap: Bitmap? = null
    var srcWidth = 0
    var srcHeight = 0
    var frameImage: ByteBuffer? = null
    var frameToClose: ImageDecoder? = null
    try {
        dec = try {
            ImageDecoder.new(bytes.inputStream())
        } catch (e: Exception) {
            null
        }
        if (dec != null && dec.pages > 0) {
            val frame = try {
                dec.decodeNext()
            } catch (e: Exception) {
                null
            }
            if (frame != null && frame.width >= 8 && frame.height >= 8) {
                srcWidth = frame.width
                srcHeight = frame.height
                frameImage = frame.image
                frameToClose = dec
                dec = null
            } else {
                try {
                    dec.close()
                } catch (_: Exception) {}
                dec = null
            }
        }
        if (frameImage == null) {
            dec?.let {
                try {
                    it.close()
                } catch (_: Exception) {}
            }
            dec = null
            val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            fallbackBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (fallbackBitmap != null) {
                srcWidth = fallbackBitmap.width
                srcHeight = fallbackBitmap.height
            }
        }
        if (srcWidth < 8 || srcHeight < 8) {
            frameToClose?.let {
                try {
                    it.close()
                } catch (_: Exception) {}
            }
            fallbackBitmap?.recycle()
            throw IllegalArgumentException("src too small ${srcWidth}x$srcHeight (<8) to rescale, avoiding gralloc")
        }
        require(srcWidth in 1..8192 && srcHeight in 1..8192) { "src dimensions out of range: ${srcWidth}x$srcHeight" }
    } catch (e: Exception) {
        frameToClose?.let {
            try {
                it.close()
            } catch (_: Exception) {}
        }
        fallbackBitmap?.recycle()
        throw e
    }

    val srcBitmap: Bitmap = if (fallbackBitmap != null) {
        fallbackBitmap.also { fallbackBitmap = null }
    } else {
        val bmp = try {
            createBitmap(srcWidth, srcHeight)
        } catch (e: OutOfMemoryError) {
            frameToClose?.let {
                try {
                    it.close()
                } catch (_: Exception) {}
            }
            System.gc()
            throw e
        } catch (e: Exception) {
            frameToClose?.let {
                try {
                    it.close()
                } catch (_: Exception) {}
            }
            throw e
        }
        try {
            val image = frameImage ?: error("Missing decoded frame image")
            image.rewind()
            bmp.copyPixelsFromBuffer(image)
        } catch (e: Exception) {
            bmp.recycle()
            frameToClose?.let {
                try {
                    it.close()
                } catch (_: Exception) {}
            }
            throw e
        } finally {
            frameToClose?.let {
                try {
                    it.close()
                } catch (_: Exception) {}
            }
        }
        bmp
    }

    // The requires above already bound all three operands to 8..8192, so the helper's non-positive
    // guard cannot fire and its clamp is the same one the inline math applied.
    val scaledWidth = requireNotNull(scaledSpreadWidth(srcWidth, srcHeight, targetHeight)) {
        "unreachable for validated ${srcWidth}x$srcHeight -> $targetHeight"
    }

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
// Mihon <--
