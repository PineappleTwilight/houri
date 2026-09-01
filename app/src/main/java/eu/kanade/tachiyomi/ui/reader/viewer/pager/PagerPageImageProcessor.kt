package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import kotlin.math.max

/**
 * Image-processing pipeline for a [PagerPageHolder]: rotating wide dual pages to fit,
 * splitting double-page images in half, merging two pages into a spread, adding center
 * margins, and decoding side images for the merge. Each function mutates the holder's
 * [PagerPageHolder.page]/[PagerPageHolder.extraPage] flags ([ReaderPage.fullPage],
 * [ReaderPage.isolatedPage], [ReaderPage.shiftedPage]) as the legacy double-page
 * machinery requires, and routes split decisions back through [PagerViewer.onPageSplit].
 */

internal fun PagerPageHolder.process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
    if (viewer.config.dualPageRotateToFit) {
        return rotateDualPage(imageSource)
    }

    if (!viewer.config.dualPageSplit) {
        return imageSource
    }

    if (page is InsertPage) {
        return splitInHalf(imageSource)
    }

    val isDoublePage = ImageUtil.isWideImage(imageSource)
    if (!isDoublePage) {
        return imageSource
    }

    onPageSplit(page)

    return splitInHalf(imageSource)
}

internal fun PagerPageHolder.rotateDualPage(imageSource: BufferedSource): BufferedSource {
    val isDoublePage = ImageUtil.isWideImage(imageSource)
    return if (isDoublePage) {
        val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
        ImageUtil.rotateImage(imageSource, rotation)
    } else {
        imageSource
    }
}

internal fun PagerPageHolder.mergePages(
    imageSource: BufferedSource,
    imageSource2: BufferedSource?,
): BufferedSource {
    // Handle adding a center margin to wide images if requested
    if (imageSource2 == null) {
        return handleWideImage(imageSource)
    }

    if (page.fullPage) return imageSource
    if (ImageUtil.isAnimatedAndSupported(imageSource)) {
        page.fullPage = true
        splitDoublePages()
        return imageSource
    } else if (ImageUtil.isAnimatedAndSupported(imageSource2)) {
        page.isolatedPage = true
        extraPage?.fullPage = true
        splitDoublePages()
        return imageSource
    }

    val imageBitmap = decodeImage(imageSource)
    if (imageBitmap == null) {
        imageSource2.close()
        page.fullPage = true
        splitDoublePages()
        logcat(LogPriority.ERROR) { "Cannot combine pages" }
        return imageSource
    }

    scope.launch { progressIndicator?.setProgress(96) }
    if (imageBitmap.height < imageBitmap.width) {
        imageSource2.close()
        page.fullPage = true
        splitDoublePages()
        return imageSource
    }

    val imageBitmap2 = decodeImage(imageSource2)
    if (imageBitmap2 == null) {
        imageSource2.close()
        extraPage?.fullPage = true
        page.isolatedPage = true
        splitDoublePages()
        logcat(LogPriority.ERROR) { "Cannot combine pages" }
        return imageSource
    }

    scope.launch { progressIndicator?.setProgress(97) }
    if (imageBitmap2.height < imageBitmap2.width) {
        imageSource2.close()
        extraPage?.fullPage = true
        page.isolatedPage = true
        splitDoublePages()
        return imageSource
    }

    val isLTR = (viewer !is R2LPagerViewer) xor viewer.config.invertDoublePages
    val centerMargin = calculateCenterMargin(imageBitmap.height, imageBitmap2.height)

    imageSource.close()
    imageSource2.close()

    return ImageUtil.mergeBitmaps(
        imageBitmap,
        imageBitmap2,
        isLTR,
        centerMargin,
        viewer.config.pageCanvasColor,
        matchHeights = viewer.config.matchDoublePageHeights,
    ) {
        updateProgress(it)
    }
}

internal fun PagerPageHolder.handleWideImage(imageSource: BufferedSource): BufferedSource {
    return if (
        !ImageUtil.isAnimatedAndSupported(imageSource) &&
        ImageUtil.isWideImage(imageSource) &&
        viewer.config.centerMarginType and PagerConfig.CenterMarginType.WIDE_PAGE_CENTER_MARGIN > 0 &&
        !viewer.config.imageCropBorders
    ) {
        ImageUtil.addHorizontalCenterMargin(imageSource, height, context)
    } else {
        imageSource
    }
}

internal fun PagerPageHolder.decodeImage(imageSource: BufferedSource): Bitmap? {
    return try {
        ImageDecoder.newInstance(imageSource.inputStream())?.decode()
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Cannot decode image" }
        null
    }
}

internal fun PagerPageHolder.calculateCenterMargin(height: Int, height2: Int): Int {
    return if (viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN > 0 &&
        !viewer.config.imageCropBorders
    ) {
        96 / (this.height.coerceAtLeast(1) / max(height, height2).coerceAtLeast(1)).coerceAtLeast(1)
    } else {
        0
    }
}

internal fun PagerPageHolder.updateProgress(progress: Int) {
    scope.launch {
        if (progress == 100) {
            progressIndicator?.hide()
        } else {
            progressIndicator?.setProgress(progress)
        }
    }
}

internal fun PagerPageHolder.splitDoublePages() {
    scope.launch {
        delay(100)
        viewer.splitDoublePages(page)
        if (extraPage?.fullPage == true || page.fullPage) {
            extraPage = null
        }
    }
}

internal fun PagerPageHolder.splitInHalf(imageSource: BufferedSource): BufferedSource {
    var side = when {
        viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
        viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
        viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
        viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
        else -> error("We should choose a side!")
    }

    if (viewer.config.dualPageInvert) {
        side = when (side) {
            ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
            ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
        }
    }

    val sideMargin = if ((viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN) >
        0 &&
        viewer.config.doublePages &&
        !viewer.config.imageCropBorders
    ) {
        48
    } else {
        0
    }

    return ImageUtil.splitInHalf(imageSource, side, sideMargin)
}

internal fun PagerPageHolder.onPageSplit(page: ReaderPage) {
    val newPage = InsertPage(page)
    viewer.onPageSplit(page, newPage)
}
