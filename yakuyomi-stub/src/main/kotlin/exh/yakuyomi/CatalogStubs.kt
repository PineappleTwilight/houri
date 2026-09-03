package exh.yakuyomi

import android.content.Context
import okhttp3.OkHttpClient

/** Stub of [LocalLlmBackendType] for the no-MTL APK variant. */
enum class LocalLlmBackendType {
    MLC_GPU,
    EXECUTORCH_NPU,
    EXECUTORCH_CPU,
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
    val backends: Set<LocalLlmBackendType>,
    val mlcHfRepo: String? = null,
    val mlcModelLib: String? = null,
    val mlcLibRepo: String? = null,
    val requiresArtifacts: Boolean = false,
    val etHfRepo: String? = null,
    val etPteFile: String? = null,
    val etTokenizerFile: String? = null,
    val etSoC: String? = null,
    val contextLength: Int = 4096,
)

/** Stub of [LocalGenerateRequest] for the no-MTL APK variant. */
data class LocalGenerateRequest(
    val prompt: String,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.3f,
    val imageBytes: ByteArray? = null,
)

/** Stub of [DeviceMemory] for the no-MTL APK variant: no hardware feature is supported. */
object DeviceMemory {
    const val MTL_MIN_RAM_BYTES: Long = 3L * 1024 * 1024 * 1024
    const val WEBGPU_MIN_RAM_BYTES: Long = 3L * 1024 * 1024 * 1024

    fun totalRamBytes(context: Context): Long = 0L
    fun hasSufficientRam(context: Context, minBytes: Long): Boolean = false
    fun isMtlSupported(context: Context): Boolean = false
    fun isWebGpuSupported(context: Context): Boolean = false
    fun is64Bit(): Boolean = false
    fun socManufacturer(): String = "unknown"
    fun matchesSoc(soc: String, required: String): Boolean = false
}

/** Stub of [LocalLlmCatalog] for the no-MTL APK variant: no models available. */
object LocalLlmCatalog {
    val allModels: List<LocalLlmModel> = emptyList()

    fun byId(id: String): LocalLlmModel? = null
    fun fitForRam(models: List<LocalLlmModel> = allModels, totalRamBytes: Long): List<LocalLlmModel> = emptyList()
    fun isMlcRuntimeBundled(): Boolean = false
    fun isExecutorchBundled(): Boolean = false
    fun bestForDevice(
        totalRamBytes: Long,
        hasMlc: Boolean = isMlcRuntimeBundled(),
        hasExecutorch: Boolean = isExecutorchBundled(),
        soc: String = DeviceMemory.socManufacturer(),
    ): LocalLlmModel? = null
}

/** Stub of [ModelCatalog] for the no-MTL APK variant. */
object ModelCatalog {
    val fallbackModels: Map<String, List<String>> = emptyMap()

    suspend fun fetchModels(
        provider: String,
        apiKey: String,
        baseUrl: String,
        client: OkHttpClient,
    ): List<String> = emptyList()
}

/** No-op stub of [GeminiNanoTranslator] for the no-MTL APK variant. */
@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
@dev.zacsweers.metro.Inject
class GeminiNanoTranslator {
    fun statusCode(): Int? = null
    fun statusError(): String? = null
    suspend fun isAvailable(): Boolean = false
    suspend fun translate(queries: List<String>, pageBitmap: android.graphics.Bitmap?, sourceLang: String): List<String>? = null
}
