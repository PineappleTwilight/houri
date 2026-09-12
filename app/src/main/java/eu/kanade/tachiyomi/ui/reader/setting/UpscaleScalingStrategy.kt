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
    private const val MAX_DIM = 8192
    private const val MAX_PIXELS = 16L * 1024 * 1024
    private const val MIN_SCALE_DELTA = 0.02f

    fun computeScale(factor: Float, preset: UpscalePreferences.Preset): Float {
        val safeFactor = factor.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 2f
        return when (preset) {
            UpscalePreferences.Preset.FAST -> 1.5f
            UpscalePreferences.Preset.BALANCED -> safeFactor
            UpscalePreferences.Preset.HIGH -> (safeFactor * 1.2f).coerceAtMost(4f)
        }
    }

    fun scaledDimensions(srcW: Int, srcH: Int, scale: Float): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 1 to 1
        val safeScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        var newW = (srcW * safeScale).toInt().coerceAtLeast(1).coerceAtMost(MAX_DIM)
        var newH = (srcH * safeScale).toInt().coerceAtLeast(1).coerceAtMost(MAX_DIM)
        val pixels = newW.toLong() * newH.toLong()
        if (pixels > MAX_PIXELS) {
            val ratio = kotlin.math.sqrt(MAX_PIXELS.toDouble() / pixels.toDouble())
            newW = (newW * ratio).toInt().coerceAtLeast(1).coerceAtMost(MAX_DIM)
            newH = (newH * ratio).toInt().coerceAtLeast(1).coerceAtMost(MAX_DIM)
        }
        return newW to newH
    }

    fun exceeds16MP(w: Int, h: Int): Boolean = w.toLong() * h.toLong() > MAX_PIXELS

    fun isNearIdentityScale(scale: Float): Boolean = !scale.isFinite() || kotlin.math.abs(scale - 1f) < MIN_SCALE_DELTA

    fun upscaleBitmap(src: Bitmap, scale: Float): Bitmap? {
        return upscaleBitmapWithAlgo(src, scale, UpscalePreferences.SimpleAlgo.BICUBIC)
    }

    fun upscaleBitmapWithAlgo(src: Bitmap, scale: Float, algo: UpscalePreferences.SimpleAlgo): Bitmap? {
        if (src.isRecycled) return null
        if (src.width <= 0 || src.height <= 0) return null
        if (isNearIdentityScale(scale)) return null
        val safeScale = scale.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.5f, 4f) ?: return null
        val (newW, newH) = scaledDimensions(src.width, src.height, safeScale)
        if (newW == src.width && newH == src.height) return null
        if (exceeds16MP(newW, newH)) return null
        return try {
            when (algo) {
                UpscalePreferences.SimpleAlgo.NEAREST -> Bitmap.createScaledBitmap(src, newW, newH, false)
                UpscalePreferences.SimpleAlgo.BILINEAR -> Bitmap.createScaledBitmap(src, newW, newH, true)
                UpscalePreferences.SimpleAlgo.BICUBIC -> Bitmap.createScaledBitmap(src, newW, newH, true)
            }
        } catch (e: OutOfMemoryError) {
            System.gc()
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
