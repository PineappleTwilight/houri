package exh.yakuyomi

/**
 * Preset on-device LLM families for the "local" translation provider, spanning a wide quality
 * range: Gemma 4 and translation finetunes at the top, down to small 1B-class generics at the
 * bottom. Every entry is a GGUF file on HuggingFace (imatrix K-quants, e.g. Q5_K_M, preferred),
 * loadable directly by llama.cpp with no per-model compilation. Only the RAM gate is enforced.
 */
object LocalLlmCatalog {

    val allModels: List<LocalLlmModel> = listOf(
        // -- Gemma 4 (featured, translation-oriented) --------------------------------------
        LocalLlmModel(
            id = "gemma-4-e4b-it",
            displayName = "Gemma 4 E4B IT",
            description = "Google's Gemma 4 efficient 4B; the community-recommended size for manga translation. Imatrix Q5_K_M from unsloth.",
            paramsB = "4B",
            qualityTier = 5,
            isTranslationFinetune = false,
            supportsVision = true,
            sizeBytes = 3_100_000_000L,
            minRamBytes = 4L * 1024 * 1024 * 1024,
            ggufRepo = "unsloth/gemma-4-E4B-it-GGUF",
            ggufFile = "gemma-4-E4B-it-Q5_K_M.gguf",
            mmprojRepo = "unsloth/gemma-4-E4B-it-GGUF",
            mmprojFile = "mmproj-F16.gguf",
            contextLength = 4096,
            defaultSampling = LocalLlmSamplingConfig(
                temperature = 0.2f,
                repeatPenalty = 1.0f,
                contextLength = 4096,
            ),
        ),
        LocalLlmModel(
            id = "gemma-4-e4b-it-qat",
            displayName = "Gemma 4 E4B IT (QAT)",
            description = "Google's official QAT-quantized Gemma 4 4B — first-party weights, slightly smaller than the unsloth Q5_K_M.",
            paramsB = "4B",
            qualityTier = 4,
            isTranslationFinetune = false,
            supportsVision = true,
            sizeBytes = 2_800_000_000L,
            minRamBytes = 4L * 1024 * 1024 * 1024,
            ggufRepo = "google/gemma-4-E4B-it-qat-q4_0-gguf",
            ggufFile = "gemma-4-E4B_q4_0-it.gguf",
            mmprojRepo = "google/gemma-4-E4B-it-qat-q4_0-gguf",
            mmprojFile = "gemma-4-E4B-it-mmproj.gguf",
            contextLength = 4096,
            defaultSampling = LocalLlmSamplingConfig(
                temperature = 0.2f,
                repeatPenalty = 1.0f,
                contextLength = 4096,
            ),
        ),
        LocalLlmModel(
            id = "gemma-4-e2b-it",
            displayName = "Gemma 4 E2B IT",
            description = "Google's Gemma 4 efficient 2B — good quality, lighter for weaker devices.",
            paramsB = "2B",
            qualityTier = 3,
            isTranslationFinetune = false,
            supportsVision = true,
            sizeBytes = 1_800_000_000L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            ggufRepo = "unsloth/gemma-4-E2B-it-GGUF",
            ggufFile = "gemma-4-E2B-it-Q5_K_M.gguf",
            mmprojRepo = "unsloth/gemma-4-E2B-it-GGUF",
            mmprojFile = "mmproj-F16.gguf",
            contextLength = 4096,
            defaultSampling = LocalLlmSamplingConfig(
                temperature = 0.2f,
                repeatPenalty = 1.0f,
                contextLength = 4096,
            ),
        ),
        LocalLlmModel(
            id = "gemma-4-e2b-it-qat",
            displayName = "Gemma 4 E2B IT (QAT)",
            description = "Google's official QAT-quantized Gemma 4 2B.",
            paramsB = "2B",
            qualityTier = 3,
            isTranslationFinetune = false,
            supportsVision = true,
            sizeBytes = 1_600_000_000L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            ggufRepo = "google/gemma-4-E2B-it-qat-q4_0-gguf",
            ggufFile = "gemma-4-E2B_q4_0-it.gguf",
            mmprojRepo = "google/gemma-4-E2B-it-qat-q4_0-gguf",
            mmprojFile = "gemma-4-E2B-it-mmproj.gguf",
            contextLength = 4096,
            defaultSampling = LocalLlmSamplingConfig(
                temperature = 0.2f,
                repeatPenalty = 1.0f,
                contextLength = 4096,
            ),
        ),
        // -- Translation finetunes ----------------------------------------------------------
        LocalLlmModel(
            id = "translategemma-4b",
            displayName = "TranslateGemma 4B",
            description = "Google's state-of-the-art open translation model (Gemma 3 based, 55 languages). Imatrix Q5_K_M GGUF.",
            paramsB = "4B",
            qualityTier = 5,
            isTranslationFinetune = true,
            supportsVision = true,
            sizeBytes = 3_000_000_000L,
            minRamBytes = 4L * 1024 * 1024 * 1024,
            ggufRepo = "Qwe1325/translategemma-4b-it-GGUF",
            ggufFile = "translategemma-4b-it-q5_k_m.gguf",
            mmprojRepo = "Qwe1325/translategemma-4b-it-GGUF",
            mmprojFile = "mmproj-translategemma-4b-it-F16.gguf",
            contextLength = 8192,
            defaultSampling = LocalLlmSamplingConfig(
                temperature = 0.0f,
                repeatPenalty = 1.0f,
                contextLength = 8192,
            ),
        ),
        // -- Small generics (published GGUF, usable today) -----------------------------------
        LocalLlmModel(
            id = "llama-3.2-1b-instruct",
            displayName = "Llama 3.2 1B Instruct",
            description = "Smallest usable option; basic quality, fastest on weak devices.",
            paramsB = "1B",
            qualityTier = 1,
            isTranslationFinetune = false,
            supportsVision = false,
            sizeBytes = 900_000_000L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            ggufRepo = "unsloth/Llama-3.2-1B-Instruct-GGUF",
            ggufFile = "Llama-3.2-1B-Instruct-Q5_K_M.gguf",
        ),
    )

    fun byId(id: String): LocalLlmModel? = allModels.find { it.id == id }

    /** Models that fit the given total RAM (the only enforced filter). */
    fun fitForRam(models: List<LocalLlmModel> = allModels, totalRamBytes: Long): List<LocalLlmModel> =
        models.filter { it.minRamBytes <= totalRamBytes }

    /** Whether the llama.cpp runtime is bundled in this build (true with the Maven dependency). */
    fun isRuntimeBundled(): Boolean = LlamaCppLlmBackend.isAvailable()

    /** Best model for this device — presented as a default, never enforced. */
    fun bestForDevice(totalRamBytes: Long): LocalLlmModel? {
        val pool = fitForRam(totalRamBytes = totalRamBytes)
        return pool.sortedWith(
            compareByDescending<LocalLlmModel> { it.qualityTier }
                .thenByDescending { it.sizeBytes },
        ).firstOrNull()
    }
}
