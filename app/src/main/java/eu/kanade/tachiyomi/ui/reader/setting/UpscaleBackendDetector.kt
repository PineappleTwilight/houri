package eu.kanade.tachiyomi.ui.reader.setting

import android.content.Context
import android.os.Build
import exh.yakuyomi.NativeUpscaler
import exh.yakuyomi.OrtUpscaleSession

/**
 * Runtime capability probe for the upscaler. Feature flags alone are not treated as
 * proof of a working accelerator: Vulkan also requires an NCNN device, and NNAPI
 * requires the ORT provider to be present on API 29+.
 */
class UpscaleBackendDetector(private val context: Context) {
    private data class Snapshot(
        val ncnnLoaded: Boolean,
        val ortRuntimeAvailable: Boolean,
        val vulkanDevices: Int,
        val nnapiAvailable: Boolean,
        val computedAtMs: Long,
    )

    @Volatile
    private var cached: Snapshot? = null
    private val lock = Any()
    private val ttlMs = 30_000L

    fun isAvailable(): Boolean {
        val value = snapshot()
        return value.ncnnLoaded || value.ortRuntimeAvailable || value.nnapiAvailable
    }

    fun isVulkanAvailable(): Boolean = snapshot().vulkanDevices > 0

    fun isNpuAvailable(): Boolean = snapshot().nnapiAvailable

    fun isOrtRuntimeAvailable(): Boolean = snapshot().ortRuntimeAvailable || snapshot().nnapiAvailable

    fun isBackendAvailable(backend: UpscalePreferences.Backend): Boolean = when (backend) {
        UpscalePreferences.Backend.AUTO -> true
        UpscalePreferences.Backend.VULKAN -> isVulkanAvailable()
        UpscalePreferences.Backend.NPU -> isNpuAvailable()
        UpscalePreferences.Backend.CPU -> isNcnnCpuAvailable() || isOrtRuntimeAvailable()
    }

    fun availableBackends(): List<UpscalePreferences.Backend> = buildList {
        add(UpscalePreferences.Backend.AUTO)
        if (isVulkanAvailable()) add(UpscalePreferences.Backend.VULKAN)
        if (isNpuAvailable()) add(UpscalePreferences.Backend.NPU)
        if (isBackendAvailable(UpscalePreferences.Backend.CPU)) add(UpscalePreferences.Backend.CPU)
    }

    fun effectiveBackend(requested: UpscalePreferences.Backend): UpscalePreferences.Backend {
        if (requested != UpscalePreferences.Backend.AUTO && isBackendAvailable(requested)) return requested
        return when {
            isVulkanAvailable() -> UpscalePreferences.Backend.VULKAN
            isNpuAvailable() -> UpscalePreferences.Backend.NPU
            else -> UpscalePreferences.Backend.CPU
        }
    }

    fun invalidateCache() {
        synchronized(lock) { cached = null }
    }

    fun diagnostics(): String {
        val value = snapshot()
        return "ncnn=${value.ncnnLoaded} ort=${value.ortRuntimeAvailable} vulkan=${value.vulkanDevices} nnapi=${value.nnapiAvailable} abi=${Build.SUPPORTED_ABIS.joinToString()}"
    }

    private fun isNcnnCpuAvailable(): Boolean = snapshot().ncnnLoaded

    private fun snapshot(): Snapshot {
        val current = cached
        val now = System.currentTimeMillis()
        if (current != null && now - current.computedAtMs < ttlMs) return current
        return synchronized(lock) {
            val second = cached
            if (second != null && System.currentTimeMillis() - second.computedAtMs < ttlMs) return@synchronized second
            val loaded = runCatching { NativeUpscaler.isLoaded() }.getOrDefault(false)
            val gpuCount = if (loaded && hasVulkanFeature()) {
                runCatching { NativeUpscaler.gpuCount() }.getOrDefault(0)
            } else {
                0
            }
            val ortRuntime = runCatching { OrtUpscaleSession.isOrtRuntimeAvailable() }.getOrDefault(false)
            val nnapi = ortRuntime && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
                OrtUpscaleSession.isRuntimeAvailable()
            }.getOrDefault(false)
            Snapshot(loaded, ortRuntime, gpuCount.coerceAtLeast(0), nnapi, System.currentTimeMillis()).also { cached = it }
        }
    }

    private fun hasVulkanFeature(): Boolean = runCatching {
        context.packageManager.hasSystemFeature("android.hardware.vulkan.version")
    }.getOrDefault(false)
}
