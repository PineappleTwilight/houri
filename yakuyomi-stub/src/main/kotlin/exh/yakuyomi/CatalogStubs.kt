package exh.yakuyomi

import android.content.Context

/** Stub of [LocalLlmBackendType] for the no-MTL APK variant. */
enum class LocalLlmBackendType {
    LLAMACPP,
}

/** Stub of [LocalLlmModel] for the no-MTL APK variant. */
data class LocalLlmModel(
    val id: String,
    val displayName: String,
    val description: String,
    val paramsB: String,
    val qualityTier: Int,
    val isTranslationFinetune: Boolean,
    val supportsVision: Boolean,
    val sizeBytes: Long,
    val minRamBytes: Long,
    val backends: Set<LocalLlmBackendType> = setOf(LocalLlmBackendType.LLAMACPP),
    val ggufRepo: String? = null,
    val ggufFile: String? = null,
    val mmprojRepo: String? = null,
    val mmprojFile: String? = null,
    val isCustom: Boolean = false,
    val contextLength: Int = 4096,
    val defaultSampling: LocalLlmSamplingConfig? = null,
)

/** Stub of [LocalLlmSamplingConfig] for the no-MTL APK variant. */
@kotlinx.serialization.Serializable
data class LocalLlmSamplingConfig(
    val temperature: Float = 0.3f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,
    val contextLength: Int = 4096,
    val numThreads: Int = 0,
    val gpuLayers: Int = -1,
) {
    val resolvedThreads: Int
        get() = if (numThreads <= 0) {
            (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)
        } else {
            numThreads
        }
}

/** Stub of [LocalGenerateRequest] for the no-MTL APK variant. */
data class LocalGenerateRequest(
    val prompt: String,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.3f,
    val imageBytes: ByteArray? = null,
)

/**
 * Stub of [DeviceMemory] for the no-MTL APK variant. RAM is a device property, not a variant
 * property: the WebGPU (high-quality) renderer is available in both variants, so the checks
 * behave exactly like the engine module's.
 */
object DeviceMemory {
    const val MTL_MIN_RAM_BYTES: Long = 3L * 1024 * 1024 * 1024
    const val WEBGPU_MIN_RAM_BYTES: Long = 3L * 1024 * 1024 * 1024

    fun totalRamBytes(context: Context): Long {
        return runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem
        }.getOrDefault(0L)
    }

    fun hasSufficientRam(context: Context, minBytes: Long): Boolean = totalRamBytes(context) >= minBytes
    fun isMtlSupported(context: Context): Boolean = hasSufficientRam(context, MTL_MIN_RAM_BYTES)
    fun isWebGpuSupported(context: Context): Boolean = hasSufficientRam(context, WEBGPU_MIN_RAM_BYTES)
    fun is64Bit(): Boolean = android.os.Build.SUPPORTED_ABIS.any { it.contains("64") }
    fun socManufacturer(): String = "unknown"
    fun matchesSoc(soc: String, required: String): Boolean =
        soc.equals(required, ignoreCase = true)
}

/** Stub of [LocalLlmCatalog] for the no-MTL APK variant: no models available. */
object LocalLlmCatalog {
    val allModels: List<LocalLlmModel> = emptyList()

    fun byId(id: String): LocalLlmModel? = null
    fun fitForRam(models: List<LocalLlmModel> = allModels, totalRamBytes: Long): List<LocalLlmModel> = emptyList()
    fun isRuntimeBundled(): Boolean = false
    fun bestForDevice(totalRamBytes: Long): LocalLlmModel? = null
}

/**
 * No-op stub of [GeminiNanoTranslator] for the no-MTL APK variant.
 * (ModelCatalog intentionally lives only in the app source set — see app/exh/yakuyomi/ModelCatalog.kt.)
 */
@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.Inject
class GeminiNanoTranslator {
    fun statusCode(): Int? = null
    fun statusError(): String? = null
    suspend fun isAvailable(): Boolean = false
    suspend fun translate(queries: List<String>, pageBitmap: android.graphics.Bitmap?, sourceLang: String): List<String>? = null
}
