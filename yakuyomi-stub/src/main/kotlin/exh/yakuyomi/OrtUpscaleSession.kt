package exh.yakuyomi

import android.graphics.Bitmap

/** no-MTL flavor stub: ONNX Runtime upscaling is unavailable. */
class OrtUpscaleSession private constructor() : AutoCloseable {
    data class Config(
        val nativeScale: Int,
        val tileSize: Int = 128,
        val padding: Int = 16,
        val inputOrder: Int = 0,
        val outputOrder: Int = 0,
    )

    val usedNnapi: Boolean = false
    val lastError: String? = null

    fun process(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap? = null

    override fun close() = Unit

    companion object {
        fun isOrtRuntimeAvailable(): Boolean = false

        fun isRuntimeAvailable(): Boolean = false

        fun open(modelPath: String, config: Config, preferNnapi: Boolean): OrtUpscaleSession? = null
    }
}
