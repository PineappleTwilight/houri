package eu.kanade.tachiyomi.ui.reader.setting

import android.graphics.Bitmap

/**
 * Pure scaling strategy extracted from [UpscaleEngine.runUpscale].
 *
 * The current engine only does bilinear `createScaledBitmap` (placeholder for real
 * NCNN/ONNX inference). Isolating the strategy makes it trivial to swap in the
 * native inference path later without touching cache/backend logic, and keeps
 * the 16MP guard testable.
 */
object UpscaleScalingStrategy {

    fun computeScale(factor: Float, preset: UpscalePreferences.Preset): Float = when (preset) {
        UpscalePreferences.Preset.FAST -> 1.5f
        UpscalePreferences.Preset.BALANCED -> factor
        UpscalePreferences.Preset.HIGH -> (factor * 1.2f).coerceAtMost(4f)
    }

    fun scaledDimensions(srcW: Int, srcH: Int, scale: Float): Pair<Int, Int> {
        val newW = (srcW * scale).toInt().coerceAtLeast(1).coerceAtMost(8192)
        val newH = (srcH * scale).toInt().coerceAtLeast(1).coerceAtMost(8192)
        return newW to newH
    }

    fun exceeds16MP(w: Int, h: Int): Boolean = w.toLong() * h.toLong() > 16L * 1024 * 1024

    fun upscaleBitmap(src: Bitmap, scale: Float): Bitmap? {
        // Default to bicubic (filter=true) for backwards compat
        return upscaleBitmapWithAlgo(src, scale, UpscalePreferences.SimpleAlgo.BICUBIC)
    }

    fun upscaleBitmapWithAlgo(src: Bitmap, scale: Float, algo: UpscalePreferences.SimpleAlgo): Bitmap? {
        val (newW, newH) = scaledDimensions(src.width, src.height, scale)
        if (exceeds16MP(newW, newH)) return null
        return when (algo) {
            UpscalePreferences.SimpleAlgo.NEAREST -> Bitmap.createScaledBitmap(src, newW, newH, false)
            UpscalePreferences.SimpleAlgo.BILINEAR -> Bitmap.createScaledBitmap(src, newW, newH, true)
            UpscalePreferences.SimpleAlgo.BICUBIC -> {
                // Android has no native bicubic; bilinear with filter is closest portable
                // approximation. For higher quality, native ncnn path is used when available.
                Bitmap.createScaledBitmap(src, newW, newH, true)
            }
        }
    }
}
