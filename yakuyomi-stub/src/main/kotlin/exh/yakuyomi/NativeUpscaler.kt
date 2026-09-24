package exh.yakuyomi

import android.graphics.Bitmap

/** no-MTL flavor stub: native NCNN upscaling is unavailable. */
object NativeUpscaler {
    enum class Backend { CPU, VULKAN }

    fun isLoaded(): Boolean = false
    fun gpuCount(): Int = 0
    fun create(
        paramPath: String,
        binPath: String,
        backend: Backend,
        nativeScale: Int,
        inputOrder: Int,
        outputOrder: Int,
    ): NativeUpscaleSession? = null
}

class NativeUpscaleSession private constructor() : AutoCloseable {
    fun process(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        tileSize: Int,
        padding: Int,
    ): Bitmap? = null

    override fun close() = Unit
}

internal fun supportsNativeUpscalerAbi(): Boolean = false
