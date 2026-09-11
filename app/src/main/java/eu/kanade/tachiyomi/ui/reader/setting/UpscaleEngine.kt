package eu.kanade.tachiyomi.ui.reader.setting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import exh.yakuyomi.TranslationPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

@SingleIn(AppScope::class)
@Inject
class UpscaleEngine(
    private val context: android.content.Context,
    private val prefs: UpscalePreferences,
    private val translationPreferences: TranslationPreferences,
) {
    private val backendDetector by lazy { UpscaleBackendDetector(context) }
    private val cacheManager by lazy { UpscaleCacheManager(context.cacheDir) }

    fun isAvailable(): Boolean = if (prefs.isSimpleMode()) true else backendDetector.isAvailable()

    fun effectiveBackend(): UpscalePreferences.Backend =
        backendDetector.effectiveBackend(prefs.effectiveBackend())

    suspend fun upscaleIfNeeded(mangaId: Long, bytes: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        if (prefs.isSimpleMode()) {
            if (!prefs.isEnabledForManga(mangaId)) return@withContext null
            val factor = prefs.upscaleFactor().get().coerceIn(1f, 4f)
            val algo = prefs.effectiveSimpleAlgo()
            val cacheModel = "simple_${algo.name}"
            if (prefs.cacheEnabled().get()) {
                val key = cacheManager.cacheKey(bytes, factor, cacheModel)
                cacheManager.getCached(key)?.let { return@withContext it }
            }
            val result = runCatching { runUpscaleSimple(bytes, factor, algo) }.getOrElse {
                logcat(LogPriority.ERROR, it) { "Upscale failed" }
                null
            } ?: return@withContext null
            if (prefs.cacheEnabled().get()) {
                val key = cacheManager.cacheKey(bytes, factor, cacheModel)
                cacheManager.putCached(key, result)
            }
            return@withContext result
        }
        if (!translationPreferences.enabled().get()) return@withContext null
        if (!prefs.isEnabledForManga(mangaId)) return@withContext null
        val factor = prefs.upscaleFactor().get().coerceIn(1f, 4f)
        val model = prefs.effectiveModel().name
        val preset = prefs.effectivePreset()
        val backend = effectiveBackend()

        if (prefs.cacheEnabled().get()) {
            val key = cacheManager.cacheKey(bytes, factor, model)
            cacheManager.getCached(key)?.let { return@withContext it }
        }

        if (!isAvailable()) {
            logcat(LogPriority.WARN) { "UpscaleEngine no native backend, skipping" }
            return@withContext null
        }

        val result = runCatching { runUpscale(bytes, factor, preset, backend, model) }.getOrElse {
            logcat(LogPriority.ERROR, it) { "Upscale failed" }
            null
        } ?: return@withContext null

        if (prefs.cacheEnabled().get()) {
            val key = cacheManager.cacheKey(bytes, factor, model)
            cacheManager.putCached(key, result)
        }
        result
    }

    private fun runUpscale(bytes: ByteArray, factor: Float, preset: UpscalePreferences.Preset, backend: UpscalePreferences.Backend, model: String): ByteArray? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val scale = UpscaleScalingStrategy.computeScale(factor, preset)
        val scaled = UpscaleScalingStrategy.upscaleBitmap(bmp, scale)
        if (scaled == null) {
            bmp.recycle()
            return null
        }
        if (scaled != bmp) bmp.recycle()
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
        scaled.recycle()
        return out.toByteArray()
    }

    private fun runUpscaleSimple(bytes: ByteArray, factor: Float, algo: UpscalePreferences.SimpleAlgo): ByteArray? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val scaled = UpscaleScalingStrategy.upscaleBitmapWithAlgo(bmp, factor, algo)
        if (scaled == null) {
            bmp.recycle()
            return null
        }
        if (scaled != bmp) bmp.recycle()
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
        scaled.recycle()
        return out.toByteArray()
    }

    fun clearCache() = cacheManager.clear()

    fun cacheSizeBytes(): Long = cacheManager.sizeBytes()
}
