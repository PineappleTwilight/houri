package eu.kanade.tachiyomi.ui.reader.setting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.yakuyomi.TranslationPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val semaphore = Semaphore(1)

    fun isAvailable(): Boolean = if (prefs.isSimpleMode()) true else backendDetector.isAvailable()

    fun effectiveBackend(): UpscalePreferences.Backend =
        backendDetector.effectiveBackend(prefs.effectiveBackend())

    fun backendDiagnostics(): String = buildString {
        append("mode=${prefs.effectiveMode().name}")
        append(" avail=${isAvailable()}")
        append(" effBackend=${effectiveBackend().name}")
        append(" vulkan=${backendDetector.isVulkanAvailable()}")
        append(" npu=${backendDetector.isNpuAvailable()}")
    }

    suspend fun upscaleIfNeeded(mangaId: Long, bytes: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        if (bytes.isEmpty() || bytes.size > 30 * 1024 * 1024) return@withContext null
        if (!prefs.isEnabledForManga(mangaId)) return@withContext null
        val factorRaw = prefs.upscaleFactor().get()
        val factor = factorRaw.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 2f
        if (factor < 1.02f) return@withContext null

        if (prefs.isSimpleMode()) {
            val algo = prefs.effectiveSimpleAlgo()
            val cacheModel = "simple_${algo.name}"
            val cacheKey = cacheManager.cacheKeyDetailed(bytes, factor, cacheModel, algo.name, prefs.effectiveMode().name)
            if (prefs.cacheEnabled().get()) {
                cacheManager.getCached(cacheKey)?.let { return@withContext it }
            }
            val result = semaphore.withPermit {
                runCatching { runUpscaleSimple(bytes, factor, algo) }.getOrElse {
                    logcat(LogPriority.ERROR, it) { "Upscale simple failed factor=$factor algo=$algo" }
                    null
                }
            } ?: return@withContext null
            if (prefs.cacheEnabled().get()) {
                cacheManager.putCached(cacheKey, result)
            }
            return@withContext result
        }

        if (!translationPreferences.enabled().get()) return@withContext null
        val model = prefs.effectiveModel().name
        val preset = prefs.effectivePreset()
        val backend = effectiveBackend()
        val cacheKey = cacheManager.cacheKeyDetailed(bytes, factor, model, preset.name, backend.name)

        if (prefs.cacheEnabled().get()) {
            cacheManager.getCached(cacheKey)?.let { return@withContext it }
        }

        if (!isAvailable()) {
            logcat(LogPriority.WARN) { "UpscaleEngine no native backend, skipping backend=$backend model=$model" }
            return@withContext null
        }

        val result = semaphore.withPermit {
            runCatching { runUpscale(bytes, factor, preset, backend, model) }.getOrElse {
                logcat(LogPriority.ERROR, it) { "Upscale native failed factor=$factor preset=$preset backend=$backend model=$model" }
                null
            }
        } ?: return@withContext null

        if (prefs.cacheEnabled().get()) {
            cacheManager.putCached(cacheKey, result)
        }
        result
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: OutOfMemoryError) {
            System.gc()
            logcat(LogPriority.ERROR, e) { "Upscale OOM decode ${bytes.size} bytes" }
            null
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Upscale decode failed" }
            null
        }
    }

    private fun compressToWebp(bitmap: Bitmap): ByteArray? {
        return try {
            val out = java.io.ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
            if (!ok) return null
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: OutOfMemoryError) {
            System.gc()
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun runUpscale(bytes: ByteArray, factor: Float, preset: UpscalePreferences.Preset, backend: UpscalePreferences.Backend, model: String): ByteArray? {
        val bmp = decodeBitmap(bytes) ?: return null
        var scaled: Bitmap? = null
        try {
            val scale = UpscaleScalingStrategy.computeScale(factor, preset)
            if (UpscaleScalingStrategy.isNearIdentityScale(scale)) return null
            scaled = UpscaleScalingStrategy.upscaleBitmap(bmp, scale)
                ?: return null
            if (scaled == bmp) return compressToWebp(scaled)
            return compressToWebp(scaled)
        } finally {
            try {
                if (scaled != null && scaled !== bmp) scaled.recycle()
            } catch (_: Exception) {}
            try { bmp.recycle() } catch (_: Exception) {}
        }
    }

    private fun runUpscaleSimple(bytes: ByteArray, factor: Float, algo: UpscalePreferences.SimpleAlgo): ByteArray? {
        val bmp = decodeBitmap(bytes) ?: return null
        var scaled: Bitmap? = null
        try {
            if (UpscaleScalingStrategy.isNearIdentityScale(factor)) return null
            scaled = UpscaleScalingStrategy.upscaleBitmapWithAlgo(bmp, factor, algo)
                ?: return null
            if (scaled == bmp) return compressToWebp(scaled)
            return compressToWebp(scaled)
        } finally {
            try {
                if (scaled != null && scaled !== bmp) scaled.recycle()
            } catch (_: Exception) {}
            try { bmp.recycle() } catch (_: Exception) {}
        }
    }

    fun clearCache() = cacheManager.clear()

    fun cacheSizeBytes(): Long = cacheManager.sizeBytes()

    fun cacheFileCount(): Int = cacheManager.fileCount()

    fun pruneExpired() = cacheManager.pruneExpired()
}
