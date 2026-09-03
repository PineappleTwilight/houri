package exh.yakuyomi

/**
 * Native backend families for the on-device ("local") LLM provider.
 *
 * - [MLC_GPU] — MLC-LLM, GPU inference via OpenCL (Adreno/Mali). Requires the mlc4j runtime
 *   to be bundled in the app (built from the MLC-LLM source tree with `mlc_llm package`).
 * - [EXECUTORCH_NPU] — ExecuTorch with a hardware NPU delegate (Qualcomm QNN / MediaTek
 *   NeuroPilot). Requires a per-SoC `.pte` model.
 * - [EXECUTORCH_CPU] — ExecuTorch with the XNNPACK CPU delegate; works on any device but
 *   slower than the GPU/NPU paths.
 */
enum class LocalLlmBackendType {
    MLC_GPU,
    EXECUTORCH_NPU,
    EXECUTORCH_CPU,
}

/**
 * A preset on-device LLM available for the "local" translation provider. Models cover a wide
 * quality range (basic 1B-class up to best-in-class translation finetunes) and are filtered by
 * the device's RAM; the best-fitting model is *presented* but never enforced — only the global
 * MTL RAM gate is mandatory.
 */
data class LocalLlmModel(
    val id: String,
    val displayName: String,
    val description: String,
    val paramsB: String,
    /** 1 = basic … 5 = best. Used for the "recommended for this device" hint only. */
    val qualityTier: Int,
    val isTranslationFinetune: Boolean,
    /** Whether the model accepts a page image as extra context (Gemma 3/4, Qwen-VL, …). */
    val supportsVision: Boolean,
    /** Approximate download size of the weight files. */
    val sizeBytes: Long,
    /** Minimum total device RAM recommended for this model. */
    val minRamBytes: Long,
    val backends: Set<LocalLlmBackendType>,
    /**
     * MLC-LLM backend: HuggingFace repo id holding the MLC-converted weights (e.g.
     * "mlc-ai/Qwen2.5-1.5B-Instruct-q4f16_1-MLC"). Repos under the project's own org are
     * produced by the model-conversion build pipeline (`mlc_llm convert_weight`).
     */
    val mlcHfRepo: String? = null,
    /** MLC compiled model library name (e.g. "Qwen2.5-1.5B-Instruct-q4f16_1-MLC"). */
    val mlcModelLib: String? = null,
    /** HuggingFace repo holding precompiled Android model libs (file: `<modelLib>-android-<abi>.so`). */
    val mlcLibRepo: String? = null,
    /** True when the weights/artifacts are not yet published and need the build pipeline. */
    val requiresArtifacts: Boolean = false,
    /**
     * ExecuTorch backend: HuggingFace repo id holding the `.pte` program + tokenizer, plus the
     * file names inside it. [etSoC] restricts the entry to a specific SoC family (null = any,
     * XNNPACK CPU delegate).
     */
    val etHfRepo: String? = null,
    val etPteFile: String? = null,
    val etTokenizerFile: String? = null,
    val etSoC: String? = null,
    val contextLength: Int = 4096,
)

/** A single on-device generation request. [imageBytes] carries the manga page for vision models. */
data class LocalGenerateRequest(
    val prompt: String,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.3f,
    val imageBytes: ByteArray? = null,
)
