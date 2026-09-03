package exh.yakuyomi

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Device capability checks used to gate memory-hungry features.
 *
 * The AI translation pipeline loads three native models at once (DBNet detector, ONNX OCR and
 * AOT-GAN inpainter) plus large page bitmaps; on low-RAM devices the native allocation fails and
 * crashes the process with a SIGSEGV from `libyakuyomi_ncnn.so` that cannot be caught in Kotlin.
 * The WebGPU renderer shows the same class of native crash (`GPUTexture.createView` on a null
 * texture) when the device cannot allocate GPU-visible memory. Both features are gated behind a
 * total-RAM check so those devices degrade gracefully instead of dying.
 */
object DeviceMemory {

    /** Minimum total RAM required to run the on-device AI translation pipeline (3 GiB). */
    const val MTL_MIN_RAM_BYTES: Long = 3L * 1024 * 1024 * 1024

    /** Minimum total RAM required to run the WebGPU (high-quality) renderer (3 GiB). */
    const val WEBGPU_MIN_RAM_BYTES: Long = 3L * 1024 * 1024 * 1024

    /** Total physical RAM in bytes, or 0 when the system service is unavailable. */
    fun totalRamBytes(context: Context): Long {
        return runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem
        }.getOrDefault(0L)
    }

    fun hasSufficientRam(context: Context, minBytes: Long): Boolean {
        val total = totalRamBytes(context)
        // Unknown memory size (service missing): be conservative and deny the heavy features.
        return total >= minBytes
    }

    /** Whether this device has enough RAM for the on-device AI translation pipeline. */
    fun isMtlSupported(context: Context): Boolean = hasSufficientRam(context, MTL_MIN_RAM_BYTES)

    /** Whether this device has enough RAM for the WebGPU (high-quality) renderer. */
    fun isWebGpuSupported(context: Context): Boolean = hasSufficientRam(context, WEBGPU_MIN_RAM_BYTES)

    /** Whether the device runs a 64-bit ABI (32-bit processes have a much smaller address space). */
    fun is64Bit(): Boolean = Build.SUPPORTED_ABIS.any { it.contains("64") }

    /**
     * Normalized SoC manufacturer for NPU selection: "qualcomm" (Snapdragon), "mediatek"
     * (Dimensity/Helio), "samsung" (Exynos), "huawei" (Kirin), or "unknown".
     */
    fun socManufacturer(): String {
        val soc = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} ${Build.HARDWARE} ${Build.BOARD}".lowercase()
        return when {
            "qcom" in soc || "qualcomm" in soc || "snapdragon" in soc -> "qualcomm"
            "mtk" in soc || "mediatek" in soc || "dimensity" in soc || "helio" in soc -> "mediatek"
            "exynos" in soc || "samsung" in soc -> "samsung"
            "kirin" in soc || "hisilicon" in soc || "huawei" in soc -> "huawei"
            else -> "unknown"
        }
    }

    /** Whether [soc] matches a catalog [required] SoC family. */
    fun matchesSoc(soc: String, required: String): Boolean =
        soc.equals(required, ignoreCase = true)
}
