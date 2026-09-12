package eu.kanade.tachiyomi.ui.reader.setting

import android.content.Context

/**
 * Detects available upscaling backends. Single-responsibility extraction from
 * [UpscaleEngine] — previously backend checks were inline and `isAvailable()`
 * re-attempted `System.loadLibrary` on every page (B1).
 *
 * Caches the native-availability probe so repeated per-page calls are O(1).
 */
class UpscaleBackendDetector(private val context: Context) {

    @Volatile
    private var cachedAvailable: Boolean? = null
    private val probeLock = Any()

    @Volatile
    private var lastProbeMs: Long = 0L
    private val probeTtlMs = 30_000L

    fun isAvailable(): Boolean = getCachedAvailable()

    private fun getCachedAvailable(): Boolean {
        val cached = cachedAvailable
        val now = System.currentTimeMillis()
        if (cached != null && now - lastProbeMs < probeTtlMs) return cached
        synchronized(probeLock) {
            val c2 = cachedAvailable
            if (c2 != null && System.currentTimeMillis() - lastProbeMs < probeTtlMs) return c2
            val result = probeNativeAvailable()
            cachedAvailable = result
            lastProbeMs = System.currentTimeMillis()
            return result
        }
    }

    fun invalidateCache() {
        synchronized(probeLock) {
            cachedAvailable = null
            lastProbeMs = 0L
        }
    }

    fun isVulkanAvailable(): Boolean = try {
        val pm = context.packageManager
        if (!pm.hasSystemFeature("android.hardware.vulkan.version")) return false
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            val feat = pm.getSystemAvailableFeatures()?.any { it.name == "android.hardware.vulkan.version" } ?: false
            feat
        } else {
            true
        }
    } catch (_: Throwable) {
        false
    }

    fun isNpuAvailable(): Boolean = try {
        Class.forName("ai.onnxruntime.OrtEnvironment")
        true
    } catch (_: Throwable) {
        false
    }

    fun isBackendAvailable(backend: UpscalePreferences.Backend): Boolean = when (backend) {
        UpscalePreferences.Backend.AUTO -> true
        UpscalePreferences.Backend.VULKAN -> isVulkanAvailable()
        UpscalePreferences.Backend.NPU -> isNpuAvailable()
        UpscalePreferences.Backend.CPU -> isAvailable() || true
    }

    fun availableBackends(): List<UpscalePreferences.Backend> = buildList {
        add(UpscalePreferences.Backend.AUTO)
        if (isVulkanAvailable()) add(UpscalePreferences.Backend.VULKAN)
        if (isNpuAvailable()) add(UpscalePreferences.Backend.NPU)
        add(UpscalePreferences.Backend.CPU)
    }

    fun effectiveBackend(requested: UpscalePreferences.Backend): UpscalePreferences.Backend {
        if (requested != UpscalePreferences.Backend.AUTO) {
            if (isBackendAvailable(requested)) return requested
            return when {
                isVulkanAvailable() -> UpscalePreferences.Backend.VULKAN
                isNpuAvailable() -> UpscalePreferences.Backend.NPU
                else -> UpscalePreferences.Backend.CPU
            }
        }
        return when {
            isVulkanAvailable() -> UpscalePreferences.Backend.VULKAN
            isNpuAvailable() -> UpscalePreferences.Backend.NPU
            else -> UpscalePreferences.Backend.CPU
        }
    }

    private fun probeNativeAvailable(): Boolean {
        return try {
            System.loadLibrary("ncnn")
            true
        } catch (_: Throwable) {
            try {
                System.loadLibrary("onnxruntime")
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
