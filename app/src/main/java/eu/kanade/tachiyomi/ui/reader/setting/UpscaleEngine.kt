package eu.kanade.tachiyomi.ui.reader.setting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.yakuyomi.TranslationPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.achievement.service.AchievementManager
import tachiyomi.domain.achievement.service.AchievementPreferences
import tachiyomi.domain.achievement.service.RotatingAchievementPool

@SingleIn(AppScope::class)
@Inject
class UpscaleEngine(
    private val context: android.content.Context,
    private val prefs: UpscalePreferences,
    private val translationPreferences: TranslationPreferences,
    private val achievementManager: AchievementManager,
    private val achievementPrefs: AchievementPreferences,
    private val rotatingPool: RotatingAchievementPool,
) {
    private val backendDetector by lazy { UpscaleBackendDetector(context) }
    private val cacheManager by lazy { UpscaleCacheManager(context.cacheDir) }
    private val semaphore = Semaphore(1)

    private companion object {
        const val MAX_INPUT_BYTES = 30 * 1024 * 1024
        const val MAX_INPUT_PIXELS = 16L * 1024 * 1024
        const val SECRET_SAME_PAGE_THRESHOLD = 5
        // PNG above this falls back to high-quality lossy WEBP: a 2x long
        // strip as PNG can exceed the cache's per-file cap and would silently
        // disable upscaling for exactly the pages that need it most.
        const val MAX_PNG_BYTES = 12 * 1024 * 1024
    }

    fun isAvailable(): Boolean = if (prefs.isSimpleMode()) true else backendDetector.isAvailable()

    fun effectiveBackend(): UpscalePreferences.Backend =
        backendDetector.effectiveBackend(prefs.effectiveBackend())

    fun availableBackends(): List<UpscalePreferences.Backend> = backendDetector.availableBackends()

    fun isNativeAvailable(): Boolean = backendDetector.isAvailable()

    fun backendDiagnostics(): String = buildString {
        append("mode=${prefs.effectiveMode().name}")
        append(" avail=${isAvailable()}")
        append(" effBackend=${effectiveBackend().name}")
        append(" vulkan=${backendDetector.isVulkanAvailable()}")
        append(" npu=${backendDetector.isNpuAvailable()}")
    }

    // Served covers executed upscales and cache hits: the user saw an upscaled page either way.
    private fun reportUpscaleServed(
        mangaId: Long,
        backend: UpscalePreferences.Backend?,
        cacheKey: String,
    ) {
        if (mangaId <= 0 || cacheKey.isBlank()) return
        try {
            achievementManager.tryUnlockDirect("upscale_first")
            val total = achievementPrefs.incrementUpscalesServed()
            achievementManager.onUpscaled(total)
            if (backend == UpscalePreferences.Backend.VULKAN) {
                achievementManager.tryUnlockDirect("upscale_vulkan")
            } else if (backend == UpscalePreferences.Backend.NPU) {
                achievementManager.tryUnlockDirect("upscale_npu")
            }
            rotatingPool.markProgress("rotating_weekly_upscale_20")
            val pageCount = achievementPrefs.incrementUpscalePageCount(cacheKey)
            if (pageCount >= SECRET_SAME_PAGE_THRESHOLD) {
                achievementManager.tryUnlockDirect("secret_upscale_4k")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun upscaleIfNeeded(mangaId: Long, bytes: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        if (bytes.isEmpty() || bytes.size > MAX_INPUT_BYTES) return@withContext null
        if (!prefs.isEnabledForManga(mangaId)) return@withContext null
        val factorRaw = prefs.upscaleFactor().get()
        val factor = factorRaw.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 2f
        if (factor < 1.02f) return@withContext null

        if (prefs.isSimpleMode()) {
            val algo = prefs.effectiveSimpleAlgo()
            val cacheModel = "simple_${algo.name}"
            val cacheKey = cacheManager.cacheKeyDetailed(bytes, factor, cacheModel, algo.name, prefs.effectiveMode().name)
            if (prefs.cacheEnabled().get()) {
                withContext(Dispatchers.IO) { cacheManager.getCached(cacheKey) }?.let {
                    reportUpscaleServed(mangaId, null, cacheKey)
                    return@withContext it
                }
            }
            val result = semaphore.withPermit {
                runCatching { runUpscaleSimple(bytes, factor, algo) }.getOrElse {
                    logcat(LogPriority.ERROR, it) { "Upscale simple failed factor=$factor algo=$algo" }
                    null
                }
            } ?: return@withContext null
            if (prefs.cacheEnabled().get()) {
                withContext(Dispatchers.IO) { cacheManager.putCached(cacheKey, result) }
            }
            reportUpscaleServed(mangaId, null, cacheKey)
            return@withContext result
        }

        if (!translationPreferences.enabled().get()) return@withContext null
        val model = prefs.effectiveModel().name
        val preset = prefs.effectivePreset()
        val backend = effectiveBackend()
        val cacheKey = cacheManager.cacheKeyDetailed(bytes, factor, model, preset.name, backend.name)

        if (prefs.cacheEnabled().get()) {
            withContext(Dispatchers.IO) { cacheManager.getCached(cacheKey) }?.let {
                reportUpscaleServed(mangaId, backend, cacheKey)
                return@withContext it
            }
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
            withContext(Dispatchers.IO) { cacheManager.putCached(cacheKey, result) }
        }
        reportUpscaleServed(mangaId, backend, cacheKey)
        result
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) null else w to h
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val (w, h) = decodeBounds(bytes) ?: return null
        if (w.toLong() * h.toLong() > MAX_INPUT_PIXELS) {
            logcat(LogPriority.WARN) { "Upscale input too large ${w}x$h, skipping" }
            return null
        }
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

    private fun compressForCache(bitmap: Bitmap): ByteArray? {
        return try {
            // PNG first: pixel-exact through the Bitmap encode / libvips decode
            // round trip. WEBP_LOSSY came back uniformly darker on the WebGPU
            // path, so it is only the oversize fallback now.
            val pngOut = java.io.ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOut)) return null
            val png = pngOut.toByteArray().takeIf { it.isNotEmpty() }
            if (png != null && png.size <= MAX_PNG_BYTES) return png
            val webpOut = java.io.ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 95, webpOut)
            if (!ok) return null
            webpOut.toByteArray().takeIf { it.isNotEmpty() }
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
            return compressForCache(scaled)
        } finally {
            try {
                if (scaled != null && scaled !== bmp) scaled.recycle()
            } catch (_: Exception) {}
            try {
                bmp.recycle()
            } catch (_: Exception) {}
        }
    }

    private fun runUpscaleSimple(bytes: ByteArray, factor: Float, algo: UpscalePreferences.SimpleAlgo): ByteArray? {
        val bmp = decodeBitmap(bytes) ?: return null
        var scaled: Bitmap? = null
        try {
            if (UpscaleScalingStrategy.isNearIdentityScale(factor)) return null
            scaled = UpscaleScalingStrategy.upscaleBitmapWithAlgo(bmp, factor, algo)
                ?: return null
            return compressForCache(scaled)
        } finally {
            try {
                if (scaled != null && scaled !== bmp) scaled.recycle()
            } catch (_: Exception) {}
            try {
                bmp.recycle()
            } catch (_: Exception) {}
        }
    }

    fun clearCache() = cacheManager.clear()

    fun cacheSizeBytes(): Long = cacheManager.sizeBytes()

    fun cacheFileCount(): Int = cacheManager.fileCount()

    fun pruneExpired() = cacheManager.pruneExpired()
}
