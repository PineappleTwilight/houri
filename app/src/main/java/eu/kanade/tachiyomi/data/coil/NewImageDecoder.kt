// Mihon -->
package eu.kanade.tachiyomi.data.coil

import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import ca.mpreg.imagedecoder.ImageDecoder
import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat

/**
 * A [Decoder] that uses [ImageDecoder] (libvips-based) to decode image formats not supported
 * by the Android system decoder (AVIF, JXL, HEIF, etc.).
 */
class NewImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    /**
     * Wraps a raw [ImageDecoder.DecodeResult] as a Coil [Image] for callers that want
     * direct access to the RGBA [java.nio.ByteBuffer] (e.g. the new-decoder path).
     */
    class DecodeResultImage(val res: ImageDecoder.DecodeResult) : Image {
        override val size: Long get() = res.image.capacity().toLong()
        override val width: Int get() = res.width
        override val height: Int get() = res.height
        override val shareable: Boolean get() = true
        override fun draw(canvas: Canvas) {}
    }

    override suspend fun decode(): DecodeResult {
        val decoder = resources.source().use {
            try {
                ImageDecoder.new(it.inputStream())
            } catch (e: ImageDecoder.DecodeException) {
                logcat(LogPriority.ERROR, e) { "ImageDecoder.new failed: ${e.message}" }
                null
            }
        }

        check(decoder != null && decoder.pages > 0) { "Failed to initialize decoder" }

        val res = decoder.decode()

        val srcWidth = res.width
        val srcHeight = res.height
        val isJxl = resources.source().peek().inputStream().use { ImageUtil.findImageType(it) } == ImageUtil.ImageType.JXL
        if (isJxl) {
            runCatching { mihon.app.di.globalAppGraph.achievementManager.onJxlDecoded() }
        }

        // newDecoder path: caller wants the raw DecodeResult (e.g. for custom rendering).
        // Hand it back as-is; sampling is the caller's responsibility.
        if (options.newDecoder) {
            return DecodeResult(
                image = DecodeResultImage(res),
                isSampled = false,
            )
        }

        // Normal path: produce a Bitmap scaled to the requested output size.
        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }
        var sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        )
        if (isJxl) {
            val ctx = try { mihon.app.di.globalAppGraph.context } catch (_: Exception) { null }
            if (ctx != null) {
                val cap = ImageUtil.lowRamJxlMaxDimension(ctx)
                val lowRamSample = ImageUtil.lowRamSampleSize(srcWidth, srcHeight, cap)
                sampleSize = maxOf(sampleSize, lowRamSample)
                val hardCap = 8192
                if (maxOf(srcWidth, srcHeight) / sampleSize > hardCap) {
                    sampleSize = maxOf(sampleSize, ImageUtil.lowRamSampleSize(srcWidth, srcHeight, hardCap))
                }
            }
        }

        // Copy RGBA pixels from the native buffer into a full-resolution bitmap.
        // We must do this while `res` (and its native memory) is still alive.
        val fullBitmap = createBitmap(srcWidth, srcHeight)
        res.image.rewind()
        fullBitmap.copyPixelsFromBuffer(res.image)

        // Downsample if needed. sampleSize is a power-of-two factor; the target
        // dimensions are src / sampleSize, matching BitmapFactory inSampleSize behaviour.
        var bitmap = if (sampleSize > 1) {
            val scaledWidth = (srcWidth / sampleSize).coerceAtLeast(1)
            val scaledHeight = (srcHeight / sampleSize).coerceAtLeast(1)
            val scaled = fullBitmap.scale(scaledWidth, scaledHeight)
            fullBitmap.recycle()
            scaled
        } else {
            fullBitmap
        }
        if (isJxl) {
            val ctx2 = try { mihon.app.di.globalAppGraph.context } catch (_: Exception) { null }
            if (ctx2 != null && eu.kanade.tachiyomi.util.system.DeviceUtil.isLowRamDevice(ctx2) && bitmap.config != android.graphics.Bitmap.Config.RGB_565) {
                val estBytes = bitmap.width.toLong() * bitmap.height * 4
                if (estBytes > 24L * 1024 * 1024) {
                    val rgb565 = bitmap.copy(android.graphics.Bitmap.Config.RGB_565, false)
                    if (rgb565 != null) {
                        bitmap.recycle()
                        bitmap = rgb565
                    }
                }
            }
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.newDecoder || options.customDecoder || isApplicable(result.source.source())) {
                NewImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().use {
                ImageUtil.findImageType(it)
            }
            return when (type) {
                ImageUtil.ImageType.AVIF,
                ImageUtil.ImageType.JXL,
                ImageUtil.ImageType.HEIF,
                ImageUtil.ImageType.JP2,
                -> true

                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }
}
// Mihon <--
