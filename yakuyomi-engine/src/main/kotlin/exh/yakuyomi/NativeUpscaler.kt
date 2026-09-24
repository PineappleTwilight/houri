package exh.yakuyomi

import android.graphics.Bitmap
import android.os.Build

/** Thin lifecycle wrapper around the root-module NCNN upscaler JNI library. */
object NativeUpscaler {
    private const val LIBRARY_NAME = "upscale_ncnn"

    @Volatile
    private var loaded = false
    private val loadLock = Any()

    fun isLoaded(): Boolean {
        if (loaded) return true
        return synchronized(loadLock) {
            if (loaded) return@synchronized true
            loaded = runCatching {
                System.loadLibrary(LIBRARY_NAME)
                true
            }.getOrDefault(false)
            loaded
        }
    }

    fun gpuCount(): Int {
        if (!isLoaded()) return 0
        return runCatching { nativeGpuCount() }.getOrDefault(0)
    }

    fun create(
        paramPath: String,
        binPath: String,
        backend: Backend,
        nativeScale: Int,
        inputOrder: Int,
        outputOrder: Int,
    ): NativeUpscaleSession? {
        if (!isLoaded()) return null
        val handle = runCatching {
            nativeCreate(
                paramPath = paramPath,
                binPath = binPath,
                backend = backend.nativeId,
                nativeScale = nativeScale,
                inputOrder = inputOrder,
                outputOrder = outputOrder,
                threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8),
            )
        }.getOrDefault(0L)
        return if (handle == 0L) null else NativeUpscaleSession(handle)
    }

    enum class Backend(val nativeId: Int) {
        CPU(0),
        VULKAN(1),
    }

    private external fun nativeGpuCount(): Int

    private external fun nativeCreate(
        paramPath: String,
        binPath: String,
        backend: Int,
        nativeScale: Int,
        inputOrder: Int,
        outputOrder: Int,
        threads: Int,
    ): Long

    internal external fun nativeProcess(
        handle: Long,
        inputPixels: IntArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
        tileSize: Int,
        padding: Int,
        outputPixels: IntArray,
    ): Int

    internal external fun nativeDestroy(handle: Long)
}

class NativeUpscaleSession internal constructor(
    private var handle: Long,
) : AutoCloseable {
    fun process(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        tileSize: Int,
        padding: Int,
    ): Bitmap? {
        if (handle == 0L || bitmap.isRecycled || targetWidth <= 0 || targetHeight <= 0) return null
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        } ?: return null
        return try {
            val input = IntArray(source.width * source.height)
            source.getPixels(input, 0, source.width, 0, 0, source.width, source.height)
            val output = IntArray(targetWidth * targetHeight)
            val result = NativeUpscaler.nativeProcess(
                handle = handle,
                inputPixels = input,
                width = source.width,
                height = source.height,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                tileSize = tileSize,
                padding = padding,
                outputPixels = output,
            )
            if (result != 0) {
                null
            } else {
                Bitmap.createBitmap(output, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            }
        } catch (_: Throwable) {
            null
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }

    override fun close() {
        val old = handle
        handle = 0L
        if (old != 0L) runCatching { NativeUpscaler.nativeDestroy(old) }
    }
}

/** ARM/Vulkan is optional; x86 keeps the bitmap fallback available. */
internal fun supportsNativeUpscalerAbi(): Boolean =
    Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" }
