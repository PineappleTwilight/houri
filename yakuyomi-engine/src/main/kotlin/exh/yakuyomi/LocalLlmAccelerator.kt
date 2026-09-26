package exh.yakuyomi

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.llamatik.library.platform.LlamatikBuildInfo
import java.io.File

/**
 * Whether the on-device LLM runtime can actually offload model layers to the GPU.
 *
 * llama.cpp never fails when it cannot offload: with no GPU backend registered it logs
 * `compiled without support for GPU offload` and silently runs on the CPU. That makes a
 * `gpuLayers` setting meaningless unless the caller knows up front, which is what this
 * type is for.
 */
data class LocalLlmAcceleratorInfo(
    /** The native runtime was built with a ggml GPU backend compiled in. */
    val gpuBackendCompiledIn: Boolean,
    /** A GPU backend plugin ships next to the app's other native libs (dynamic-loader builds). */
    val gpuBackendPluginShipped: Boolean,
    /** The device advertises Vulkan compute support. */
    val vulkanCompute: Boolean,
) {
    val gpuBackendAvailable: Boolean get() = gpuBackendCompiledIn || gpuBackendPluginShipped

    val canOffloadToGpu: Boolean get() = gpuBackendAvailable && vulkanCompute
}

object LocalLlmAccelerator {

    private const val GPU_PLUGIN = "libggml-vulkan.so"

    private const val VULKAN_LEVEL_FEATURE = "android.hardware.vulkan.level"

    fun probe(context: Context): LocalLlmAcceleratorInfo {
        val packageManager = context.packageManager
        return LocalLlmAcceleratorInfo(
            gpuBackendCompiledIn = LlamatikBuildInfo.gpuOffloadCompiledIn,
            gpuBackendPluginShipped = File(context.applicationInfo.nativeLibraryDir, GPU_PLUGIN).exists(),
            vulkanCompute = hasVulkanCompute(packageManager),
        )
    }

    private fun hasVulkanCompute(packageManager: PackageManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE)
        } else {
            // FEATURE_VULKAN_HARDWARE_COMPUTE is API 31+, and FEATURE_VULKAN was removed
            // in API 36, so older devices are probed by their Vulkan level string. A driver
            // that cannot actually run the compute shaders is rejected later by ggml's own
            // device probe, which falls back to the CPU.
            packageManager.hasSystemFeature(VULKAN_LEVEL_FEATURE)
        }
}
