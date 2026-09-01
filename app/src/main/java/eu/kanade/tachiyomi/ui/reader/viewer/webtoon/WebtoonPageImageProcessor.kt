package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Image-processing pipeline for a [WebtoonPageHolder]: rotating wide dual pages to fit
 * and splitting double-page images (optionally merged back into a single tall strip).
 */
internal fun WebtoonPageHolder.process(imageSource: BufferedSource): BufferedSource {
    if (viewer.config.dualPageRotateToFit) {
        return rotateDualPage(imageSource)
    }

    if (viewer.config.dualPageSplit) {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (isDoublePage) {
            val upperSide = if (viewer.config.dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
            return ImageUtil.splitAndMerge(imageSource, upperSide)
        }
    }

    return imageSource
}

internal fun WebtoonPageHolder.rotateDualPage(imageSource: BufferedSource): BufferedSource {
    val isDoublePage = ImageUtil.isWideImage(imageSource)
    return if (isDoublePage) {
        val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
        ImageUtil.rotateImage(imageSource, rotation)
    } else {
        imageSource
    }
}
