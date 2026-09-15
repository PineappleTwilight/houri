package eu.kanade.tachiyomi.ui.reader.setting

import android.graphics.Bitmap

/**
 * Pure scaling strategy extracted from [UpscaleEngine.runUpscale].
 *
 * The current engine only does bilinear `createScaledBitmap` (placeholder for real
 * NCNN/ONNX inference). Isolating the strategy makes it trivial to swap in the
 * native inference path later without touching cache/backend logic, and keeps
 * the 16MP guard testable.
 *
 * Quality notes:
 * - `BILINEAR` is a single `createScaledBitmap(..., filter = true)` pass.
 * - `BICUBIC` is a two-pass bilinear upscale (half-steps). It is still not a true
 *   bicubic kernel, but splitting a large scale factor into two smaller filtered
 *   passes measurably reduces aliasing versus one big jump. The enum name is kept
 *   stable because it is persisted in prefs (`pref_upscale_simple_algo`).
 * - `NEAREST` is a single unfiltered pass.
 *
 * All outputs are clamped to [MAX_DIM] per side and [MAX_PIXELS] total via
 * [scaledDimensions]; callers must still bounds-check *before* decoding (this
 * object only sees already-decoded bitmaps).
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
        // Uniform scale first so long strips keep their aspect ratio: clamping
        // each side independently would squash tall pages (e.g. 800x12000 @2x
        // became 1600x8192 instead of staying 1:15).
        var uniform = safeScale
        if (srcW * uniform > MAX_DIM) uniform = MAX_DIM.toFloat() / srcW
        if (srcH * uniform > MAX_DIM) uniform = (MAX_DIM.toFloat() / srcH).coerceAtMost(uniform)
        var newW = (srcW * uniform).toInt().coerceAtLeast(1).coerceAtMost(MAX_DIM)
        var newH = (srcH * uniform).toInt().coerceAtLeast(1).coerceAtMost(MAX_DIM)
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
        // Upscale-only contract: never downscale through this path. The engine
        // already coerces to >= 1f; clamp again so direct callers cannot shrink.
        val safeScale = scale.takeIf { it.isFinite() && it > 0f }?.coerceIn(1f, 4f) ?: return null
        if (isNearIdentityScale(safeScale)) return null
        val (newW, newH) = scaledDimensions(src.width, src.height, safeScale)
        if (newW == src.width && newH == src.height) return null
        // scaledDimensions clamps to MAX_PIXELS/MAX_DIM, so the output is already within budget.
        return try {
            when (algo) {
                UpscalePreferences.SimpleAlgo.NEAREST -> Bitmap.createScaledBitmap(src, newW, newH, false)
                UpscalePreferences.SimpleAlgo.BILINEAR -> Bitmap.createScaledBitmap(src, newW, newH, true)
                UpscalePreferences.SimpleAlgo.BICUBIC -> upscaleTwoPass(src, newW, newH, safeScale)
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

    /**
     * Two-pass filtered upscale used for [UpscalePreferences.SimpleAlgo.BICUBIC].
     * Splits [scale] into two smaller filtered jumps (via sqrt) which aliases
     * less than one large jump. Falls back to a single pass when the scale is
     * small or the intermediate step would be a no-op. Returns null on OOM.
     */
    private fun upscaleTwoPass(src: Bitmap, targetW: Int, targetH: Int, scale: Float): Bitmap? {
        val approxScale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
        if (!approxScale.isFinite() || approxScale < 1.5f) {
            return Bitmap.createScaledBitmap(src, targetW, targetH, true)
        }
        val midScale = kotlin.math.sqrt(approxScale.toDouble()).toFloat()
        if (!midScale.isFinite() || isNearIdentityScale(midScale)) {
            return Bitmap.createScaledBitmap(src, targetW, targetH, true)
        }
        val (midW, midH) = scaledDimensions(src.width, src.height, midScale)
        if ((midW == src.width && midH == src.height) || (midW == targetW && midH == targetH)) {
            return Bitmap.createScaledBitmap(src, targetW, targetH, true)
        }
        var mid: Bitmap? = null
        try {
            mid = Bitmap.createScaledBitmap(src, midW, midH, true)
            return Bitmap.createScaledBitmap(mid, targetW, targetH, true)
        } finally {
            try {
                mid?.recycle()
            } catch (_: Exception) {}
        }
    }
}
