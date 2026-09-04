package exh.yakuyomi

/**
 * Native backend family for the on-device ("local") LLM provider.
 *
 * - [LLAMACPP] — llama.cpp via the Llamatik runtime (`com.llamatik:library`, prebuilt native
 *   libs from Maven Central). Loads any GGUF file directly — no per-model compilation — so
 *   users can also load their own GGUF models from device storage.
 */
enum class LocalLlmBackendType {
    LLAMACPP,
}

/**
 * A preset on-device LLM available for the "local" translation provider. Models cover a wide
 * quality range (basic 1B-class up to Gemma 4 / translation finetunes) and are filtered by the
 * device's RAM; the best-fitting model is *presented* but never enforced — only the global
 * MTL RAM gate is mandatory. All entries are GGUF files downloaded from HuggingFace; a custom
 * user-supplied GGUF is represented with [isCustom] and a local [LocalLlmModel.ggufFile] path.
 */
data class LocalLlmModel(
    val id: String,
    val displayName: String,
    val description: String,
    val paramsB: String,
    /** 1 = basic … 5 = best. Used for the "recommended for this device" hint only. */
    val qualityTier: Int,
    val isTranslationFinetune: Boolean,
    /** Whether the model accepts a page image as extra context (vision-capable archs). */
    val supportsVision: Boolean,
    /** Approximate download size of the GGUF file (display + fallback for the downloader). */
    val sizeBytes: Long,
    /** Minimum total device RAM recommended for this model. */
    val minRamBytes: Long,
    val backends: Set<LocalLlmBackendType> = setOf(LocalLlmBackendType.LLAMACPP),
    /** HuggingFace repo id holding the GGUF (e.g. "unsloth/gemma-4-E4B-it-GGUF"). */
    val ggufRepo: String? = null,
    /** File name of the GGUF inside [ggufRepo] (or an absolute local path when [isCustom]). */
    val ggufFile: String? = null,
    /** HuggingFace repo id holding the multimodal projector (vision models only). */
    val mmprojRepo: String? = null,
    /** File name of the mmproj inside [mmprojRepo] (downloaded next to the GGUF). */
    val mmprojFile: String? = null,
    /** True for a user-supplied GGUF loaded from device storage (no download). */
    val isCustom: Boolean = false,
    val contextLength: Int = 4096,
)

/** A single on-device generation request. [imageBytes] carries the manga page for vision models. */
data class LocalGenerateRequest(
    val prompt: String,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.3f,
    val imageBytes: ByteArray? = null,
)
