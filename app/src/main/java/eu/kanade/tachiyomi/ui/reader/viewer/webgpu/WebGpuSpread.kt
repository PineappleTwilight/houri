// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.renderer.Image.Companion.invoke
import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

// KMK -->
/** Floor for a spread side upload: below this the GPU rejects the texture (gralloc 0x3b). */
internal const val SPREAD_MIN_SIDE_DIM = 8

/** Hard ceiling for any rescaled spread dimension. */
internal const val SPREAD_MAX_DIM = 8192

/**
 * Scale factor bringing the shorter side up to the taller height, or null when there is
 * nothing to do: equal heights, non-positive (destroyed/placeholder) dims, or an inverted
 * call that would shrink the taller side. Pure math, no Android dependency.
 */
internal fun spreadHeightMatchFactor(shorterHeight: Int, tallerHeight: Int): Float? {
    if (shorterHeight <= 0 || tallerHeight <= 0) return null
    if (shorterHeight >= tallerHeight) return null
    return tallerHeight.toFloat() / shorterHeight
}

/** Clamp any spread dimension into the uploadable 1..[SPREAD_MAX_DIM] range. Pure math. */
internal fun clampSpreadDim(value: Int): Int = value.coerceIn(1, SPREAD_MAX_DIM)

/**
 * Width preserving aspect when scaling [srcWidth]x[srcHeight] to [targetHeight], clamped to
 * 1..[SPREAD_MAX_DIM]; null on non-positive dims (hard noop, never divides by zero).
 * Pure math, no Android dependency.
 */
internal fun scaledSpreadWidth(srcWidth: Int, srcHeight: Int, targetHeight: Int): Int? {
    if (srcWidth <= 0 || srcHeight <= 0 || targetHeight <= 0) return null
    return (srcWidth.toFloat() * targetHeight / srcHeight).roundToInt().coerceIn(1, SPREAD_MAX_DIM)
}

/** A resolved height-match: which side is shorter and the clamped taller height. */
internal data class SpreadHeightMatchPlan(val shorterIsLeft: Boolean, val targetHeight: Int)

/**
 * Resolve one deterministic shorter-to-taller pass, or null when it is a noop (zero dims,
 * already equal heights). The target is the clamped taller height, so the taller side is
 * never shrunk (which collapsed spreads to tiny on e-ink resume). Pure math.
 */
internal fun resolveSpreadHeightMatch(
    leftWidth: Int,
    leftHeight: Int,
    rightWidth: Int,
    rightHeight: Int,
): SpreadHeightMatchPlan? {
    if (leftWidth <= 0 || leftHeight <= 0 || rightWidth <= 0 || rightHeight <= 0) return null
    if (leftHeight == rightHeight) return null
    return SpreadHeightMatchPlan(
        shorterIsLeft = leftHeight < rightHeight,
        targetHeight = clampSpreadDim(maxOf(leftHeight, rightHeight)),
    )
}

/** True when a decoded side is big enough to rescale or rescale toward. Pure math. */
internal fun isSpreadSideViable(width: Int, height: Int): Boolean =
    width >= SPREAD_MIN_SIDE_DIM && height >= SPREAD_MIN_SIDE_DIM

/**
 * Gate for firing a rescale: needs a plan, retained source bytes, and no rescale already
 * running. Evicted bytes (freed on eviction) are terminal for the pass - the next decode
 * re-arms via fresh bytes - so a dead spread can never spin a retry storm. Pure math.
 */
internal fun shouldAttemptSpreadRescale(
    hasBytes: Boolean,
    rescaleInFlight: Boolean,
    plan: SpreadHeightMatchPlan?,
): Boolean = plan != null && hasBytes && !rescaleInFlight
// KMK <--

/**
 * Check if dual page mode is currently active based on config and view dimensions.
 * Dual page is never active for continuous (scrolling) viewers.
 */
fun WebGpuViewer.isDualPageMode(): Boolean {
    if (isContinuous) return false
    // KMK --> The Mihon-ported dualPageView row was removed from reader settings as
    // redundant, leaving the pref stuck at NEVER. Honor the legacy dual-page
    // split toggle that the settings UI actually exposes.
    if (config.dualPageSplit) return true
    // KMK <--
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
internal fun WebGpuViewer.canFormSpread(page: ViewerReaderPage): Boolean {
    return spreadPartner(page) != null
}

// KMK -->
/** Who [page] pairs with, or null. One verdict for [buildSpreadPage] and progress reporting. */
internal fun WebGpuViewer.spreadPartner(page: ViewerReaderPage): ViewerReaderPage? {
    if (!isDualPageMode()) return null
    val anchorPosition = if (isReversed) SpreadPosition.RIGHT else SpreadPosition.LEFT
    val partnerPosition = if (isReversed) SpreadPosition.LEFT else SpreadPosition.RIGHT
    if (page.spreadPosition != anchorPosition) return null
    val next = (page.next as? ViewerReaderPage)?.takeIf { it.page.chapter == page.page.chapter } ?: return null
    return next.takeIf { it.spreadPosition == partnerPosition && canPairShapes(page, it) }
}

/** Page to report progress for - the spread's lastmost page, not the anchor. */
internal fun WebGpuViewer.progressPage(page: ViewerPage): ViewerReaderPage? {
    val readerPage = page as? ViewerReaderPage ?: return null
    return spreadPartner(readerPage) ?: readerPage
}
// KMK <--

/**
 * Get the anchor page for a spread.
 * RTL: anchor is RIGHT, for LEFT page returns previous RIGHT
 * LTR: anchor is LEFT, for RIGHT page returns previous LEFT
 */
internal fun WebGpuViewer.getSpreadAnchor(page: ViewerPage): ViewerPage {
    if (!isDualPageMode()) return page
    if (page !is ViewerReaderPage) return page

    val anchorPosition = if (isReversed) SpreadPosition.RIGHT else SpreadPosition.LEFT
    val partnerPosition = if (isReversed) SpreadPosition.LEFT else SpreadPosition.RIGHT

    // If this is a partner page, check if previous is anchor
    if (page.spreadPosition == partnerPosition) {
        val prev = page.prev as? ViewerReaderPage ?: return page
        if (prev.page.chapter == page.page.chapter && prev.spreadPosition == anchorPosition &&
            canPairShapes(prev, page)
        ) {
            return prev
        }
    }

    // This page is the anchor or standalone
    return page
}

internal fun WebGpuViewer.buildSpreadPage(page: ViewerPage): ImagePage {
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

    val nextReaderPage = spreadPartner(page)
    val partnerImagePage = nextReaderPage?.imagePage

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
 * One coalesced height-match retry per anchor page: a new request cancels the pending one,
 * so slow decodes pile up a single delayed pass instead of stacking fire-and-forget loops.
 * Weak keys so an evicted anchor cannot leak its viewer; entries remove themselves on fire.
 */
private val spreadHeightRetries: MutableMap<ViewerReaderPage, Job> =
    Collections.synchronizedMap(WeakHashMap())

internal fun WebGpuViewer.retrySpreadHeightMatchSoon(
    anchorPage: ViewerReaderPage,
    spread: ImagePage.ImageSpread,
    nextReaderPage: ViewerReaderPage?,
    delayMs: Long = 150,
) {
    if (isDestroyed || !config.matchDoublePageHeights) return
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
    if (!config.matchDoublePageHeights) return

    val leftImage = (spread.left as? ImagePage.ImageSingle)?.image
    val rightImage = (spread.right as? ImagePage.ImageSingle)?.image
    if (leftImage == null || rightImage == null) {
        retrySpreadHeightMatchSoon(anchorPage, spread, nextReaderPage)
        return
    }
    // Zero-guard: destroyed/placeholder dims are a hard noop, never a retry or divide-by-zero.
    if (leftImage.width <= 0 || leftImage.height <= 0 || rightImage.width <= 0 || rightImage.height <= 0) {
        return
    }
    if (leftImage.height == rightImage.height) {
        cancelSpreadHeightRetry(anchorPage)
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
    ) ?: return

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
    val hasBytes: Boolean
    val inFlight: Boolean
    synchronized(lock) {
        hasBytes = shorterPage.spreadBytes != null
        inFlight = shorterPage.rescaleInFlight
    }
    if (!shouldAttemptSpreadRescale(hasBytes, inFlight, plan)) return
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
        try {
            val bytes = synchronized(lock) { sourcePage.spreadBytes }
            if (bytes != null) {
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
            sourcePage.rescaleInFlight = false
            if (scaledImage != null && pageInCache(sourcePage)) {
                val scaledSingle = ImagePage.ImageSingle(scaledImage)
                // Reapply the full decode-time zoom stack, not only the double-tap policy,
                // so a swapped side keeps fit-mode/wide-zoom anchoring after e-ink resume.
                if (!isDualPageMode()) {
                    if (!applyWideZoomIfNeeded(scaledSingle)) {
                        applyFitModeAnchor(scaledSingle)
                    }
                }
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
        // Terminal state reached either way: drop any coalesced retry for this side. The
        // anchor-keyed retry, if any, self-terminates on equal heights at the next pass.
        cancelSpreadHeightRetry(sourcePage)

        if (swapped) pager.state.invalidate()
    }
}

private suspend fun WebGpuViewer.rescaleImageToHeight(bytes: ByteArray, targetHeight: Int): Image {
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
            frameImage!!.rewind()
            bmp.copyPixelsFromBuffer(frameImage!!)
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
// Mihon <--
