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

    private val cachedAvailable: Boolean by lazy {
        probeNativeAvailable()
    }

    fun isAvailable(): Boolean = cachedAvailable

    fun isVulkanAvailable(): Boolean = try {
        context.packageManager.hasSystemFeature("android.hardware.vulkan.version")
    } catch (_: Throwable) {
        false
    }

    fun isNpuAvailable(): Boolean = try {
        Class.forName("ai.onnxruntime.OrtEnvironment")
        true
    } catch (_: Throwable) {
        false
    }

    fun effectiveBackend(requested: UpscalePreferences.Backend): UpscalePreferences.Backend {
        if (requested != UpscalePreferences.Backend.AUTO) return requested
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
