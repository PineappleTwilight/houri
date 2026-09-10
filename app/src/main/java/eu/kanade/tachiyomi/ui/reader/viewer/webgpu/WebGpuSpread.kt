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
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Check if dual page mode is currently active based on config and view dimensions.
 * Dual page is never active for continuous (scrolling) viewers.
 */
fun WebGpuViewer.isDualPageMode(): Boolean {
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
internal fun WebGpuViewer.canFormSpread(page: ViewerReaderPage): Boolean {
    if (!isDualPageMode()) return false
    val anchorPosition = if (isReversed) SpreadPosition.RIGHT else SpreadPosition.LEFT
    val partnerPosition = if (isReversed) SpreadPosition.LEFT else SpreadPosition.RIGHT
    if (page.spreadPosition != anchorPosition) return false
    val next = page.next as? ViewerReaderPage ?: return false
    if (next.page.chapter != page.page.chapter) return false
    return next.spreadPosition == partnerPosition && canPairShapes(page, next)
}

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
        nextReaderPage.spreadPosition == partnerPosition &&
            canPairShapes(page, nextReaderPage)
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
        scope.launch {
            kotlinx.coroutines.delay(200)
            if (!isDestroyed && config.matchDoublePageHeights) {
                maybeScheduleSpreadHeightMatch(anchorPage, spread, nextReaderPage)
            }
        }
        return
    }
    if (leftImage.height == rightImage.height) return
    if (leftImage.height < 8 || rightImage.height < 8) return
    if (leftImage.width < 8 || rightImage.width < 8) return

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
        nextReaderPage != null && nextReaderPage !== anchorPage &&
            nextReaderPage.spreadBytes != null && !nextReaderPage.rescaleInFlight -> nextReaderPage
        anchorPage.spreadBytes != null && !anchorPage.rescaleInFlight -> anchorPage
        else -> {
            scope.launch {
                kotlinx.coroutines.delay(120)
                if (!isDestroyed && config.matchDoublePageHeights) {
                    maybeScheduleSpreadHeightMatch(anchorPage, spread, nextReaderPage)
                }
            }
            return
        }
    }

    // If we fell back to scaling the taller side, adjust target to the shorter height.
    val resolvedTarget = if (sourcePage === shorterPage) {
        targetHeight
    } else {
        minOf(leftImage.height, rightImage.height)
    }
    scheduleSpreadHeightMatch(sourcePage, resolvedTarget)
}

internal fun WebGpuViewer.scheduleSpreadHeightMatch(sourcePage: ViewerReaderPage, targetHeight: Int) {
    if (isDestroyed) return
    if (targetHeight < 8 || targetHeight > 8192) {
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
                val scaledSingle = ImagePage.ImageSingle(scaledImage)
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
