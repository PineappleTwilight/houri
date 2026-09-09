package eu.kanade.tachiyomi.ui.reader.setting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority

@SingleIn(AppScope::class)
@Inject
class UpscaleEngine(
    private val context: Context,
    private val prefs: UpscalePreferences,
) {
    private val cacheDir by lazy { File(context.cacheDir, "upscale_cache").apply { mkdirs() } }
    private val maxCacheBytes = 200L * 1024 * 1024

    fun isAvailable(): Boolean {
        return try {
            System.loadLibrary("ncnn") ; true
        } catch (_: Throwable) {
            try { System.loadLibrary("onnxruntime"); true } catch (_: Throwable) { false }
        }
    }

    fun effectiveBackend(): UpscalePreferences.Backend {
        val requested = prefs.effectiveBackend()
        if (requested != UpscalePreferences.Backend.AUTO) return requested
        return when {
            isVulkanAvailable() -> UpscalePreferences.Backend.VULKAN
            isNpuAvailable() -> UpscalePreferences.Backend.NPU
            else -> UpscalePreferences.Backend.CPU
        }
    }

    private fun isVulkanAvailable(): Boolean = try {
        val pm = context.packageManager
        pm.hasSystemFeature("android.hardware.vulkan.version")
    } catch (_: Throwable) { false }

    private fun isNpuAvailable(): Boolean = try {
        Class.forName("ai.onnxruntime.OrtEnvironment"); true
    } catch (_: Throwable) { false }

    private fun cacheKey(bytes: ByteArray, factor: Float, model: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes + "$factor|$model".toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun pruneCacheIfNeeded() {
        try {
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= maxCacheBytes) break
                total -= f.length()
                f.delete()
            }
        } catch (_: Exception) {}
    }

    suspend fun upscaleIfNeeded(mangaId: Long, bytes: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        if (!prefs.isEnabledForManga(mangaId)) return@withContext null
        val factor = prefs.upscaleFactor().get().coerceIn(1f, 4f)
        val model = prefs.effectiveModel().name
        val preset = prefs.effectivePreset()
        val backend = effectiveBackend()

        if (prefs.cacheEnabled().get()) {
            val key = cacheKey(bytes, factor, model)
            val cached = File(cacheDir, "$key.webp")
            if (cached.exists() && cached.length() > 0) {
                return@withContext cached.readBytes()
            }
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
            try {
                val key = cacheKey(bytes, factor, model)
                File(cacheDir, "$key.webp").writeBytes(result)
                pruneCacheIfNeeded()
            } catch (_: Exception) {}
        }
        result
    }

    private fun runUpscale(bytes: ByteArray, factor: Float, preset: UpscalePreferences.Preset, backend: UpscalePreferences.Backend, model: String): ByteArray? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val scale = when (preset) {
            UpscalePreferences.Preset.FAST -> 1.5f
            UpscalePreferences.Preset.BALANCED -> factor
            UpscalePreferences.Preset.HIGH -> (factor * 1.2f).coerceAtMost(4f)
        }
        val newW = (bmp.width * scale).toInt().coerceAtLeast(1).coerceAtMost(8192)
        val newH = (bmp.height * scale).toInt().coerceAtLeast(1).coerceAtMost(8192)
        if (newW * newH > 16 * 1024 * 1024) {
            bmp.recycle()
            return null
        }
        val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)
        if (scaled != bmp) bmp.recycle()
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
        scaled.recycle()
        return out.toByteArray()
    }

    fun clearCache() {
        try { cacheDir.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }

    fun cacheSizeBytes(): Long = try { cacheDir.listFiles()?.sumOf { it.length() } ?: 0 } catch (_: Exception) { 0 }
}
