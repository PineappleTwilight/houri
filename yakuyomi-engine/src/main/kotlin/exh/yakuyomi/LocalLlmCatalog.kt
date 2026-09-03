package exh.yakuyomi

/**
 * Preset on-device LLM families for the "local" translation provider, spanning a wide quality
 * range: best-in-class translation finetunes and the Gemma 4 family at the top, down to small
 * 1B-class generics at the bottom. Entries with [LocalLlmModel.requiresArtifacts] need the
 * model-conversion build pipeline to publish their MLC weights / compiled model lib (see the
 * module README); entries without it point at already-published `mlc-ai/\*-MLC` weight repos.
 */
object LocalLlmCatalog {

    val allModels: List<LocalLlmModel> = listOf(
        // -- Gemma 4 (featured, vision-capable, translation-oriented) ---------------------
        LocalLlmModel(
            id = "gemma-4-e4b-it",
            displayName = "Gemma 4 E4B IT",
            description = "Google's Gemma 4 efficient 4B. Vision-capable, strong multilingual + translation quality; the community-recommended size for manga translation.",
            paramsB = "4B",
            qualityTier = 5,
            isTranslationFinetune = false,
            supportsVision = true,
            sizeBytes = 3_100_000_000L,
            minRamBytes = 4L * 1024 * 1024 * 1024,
            backends = setOf(LocalLlmBackendType.MLC_GPU),
            mlcHfRepo = "houri-app/gemma-4-E4B-it-q4f16_1-MLC",
            mlcModelLib = "gemma-4-E4B-it-q4f16_1-MLC",
            requiresArtifacts = true,
        ),
        LocalLlmModel(
            id = "gemma-4-e2b-it",
            displayName = "Gemma 4 E2B IT",
            description = "Google's Gemma 4 efficient 2B. Vision-capable, good quality, lighter than E4B for weaker GPUs.",
            paramsB = "2B",
            qualityTier = 3,
            isTranslationFinetune = false,
            supportsVision = true,
            sizeBytes = 1_900_000_000L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            backends = setOf(LocalLlmBackendType.MLC_GPU),
            mlcHfRepo = "houri-app/gemma-4-E2B-it-q4f16_1-MLC",
            mlcModelLib = "gemma-4-E2B-it-q4f16_1-MLC",
            requiresArtifacts = true,
        ),
        // -- Translation finetunes ----------------------------------------------------------
        LocalLlmModel(
            id = "translategemma-4b",
            displayName = "TranslateGemma 4B",
            description = "Google's state-of-the-art open translation model (Gemma 3 based, 55 languages). Image input supported via its chat template.",
            paramsB = "4B",
            qualityTier = 5,
            isTranslationFinetune = true,
            supportsVision = true,
            sizeBytes = 2_900_000_000L,
            minRamBytes = 4L * 1024 * 1024 * 1024,
            backends = setOf(LocalLlmBackendType.MLC_GPU),
            mlcHfRepo = "houri-app/TranslateGemma-4B-q4f16_1-MLC",
            mlcModelLib = "TranslateGemma-4B-q4f16_1-MLC",
            requiresArtifacts = true,
        ),
        LocalLlmModel(
            id = "qwen3.5-4b-vntl",
            displayName = "Qwen3.5 4B VNTL",
            description = "Manga-dialogue translation finetune (JA/KO/ZH -> EN, LoRA on Qwen3.5-4B-Base). Deterministic at temperature 0.",
            paramsB = "4B",
            qualityTier = 5,
            isTranslationFinetune = true,
            supportsVision = false,
            sizeBytes = 3_000_000_000L,
            minRamBytes = 4L * 1024 * 1024 * 1024,
            backends = setOf(LocalLlmBackendType.MLC_GPU),
            mlcHfRepo = "houri-app/Qwen3.5-4B-VNTL-q4f16_1-MLC",
            mlcModelLib = "Qwen3.5-4B-VNTL-q4f16_1-MLC",
            requiresArtifacts = true,
        ),
        // -- Published MLC weights (usable today) --------------------------------------------
        LocalLlmModel(
            id = "gemma-3-4b-it",
            displayName = "Gemma 3 4B IT",
            description = "Vision-capable multimodal model, strong quality, pre-converted weights published by the MLC team.",
            paramsB = "4B",
            qualityTier = 4,
            isTranslationFinetune = false,
            supportsVision = true,
            sizeBytes = 2_800_000_000L,
            minRamBytes = 4L * 1024 * 1024 * 1024,
            backends = setOf(LocalLlmBackendType.MLC_GPU),
            mlcHfRepo = "mlc-ai/gemma-3-4b-it-q4f16_1-MLC",
            mlcModelLib = "gemma-3-4b-it-q4f16_1-MLC",
        ),
        LocalLlmModel(
            id = "qwen2.5-3b-instruct",
            displayName = "Qwen2.5 3B Instruct",
            description = "Good multilingual quality and strong Japanese/Korean handling.",
            paramsB = "3B",
            qualityTier = 3,
            isTranslationFinetune = false,
            supportsVision = false,
            sizeBytes = 2_000_000_000L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            backends = setOf(LocalLlmBackendType.MLC_GPU),
            mlcHfRepo = "mlc-ai/Qwen2.5-3B-Instruct-q4f16_1-MLC",
            mlcModelLib = "Qwen2.5-3B-Instruct-q4f16_1-MLC",
        ),
        LocalLlmModel(
            id = "gemma-2-2b-it",
            displayName = "Gemma 2 2B IT",
            description = "Compact Gemma 2, decent quality for its size.",
            paramsB = "2B",
            qualityTier = 3,
            isTranslationFinetune = false,
            supportsVision = false,
            sizeBytes = 1_600_000_000L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            backends = setOf(LocalLlmBackendType.MLC_GPU),
            mlcHfRepo = "mlc-ai/gemma-2-2b-it-q4f16_1-MLC",
            mlcModelLib = "gemma-2-2b-it-q4f16_1-MLC",
        ),
        LocalLlmModel(
            id = "qwen2.5-1.5b-instruct",
            displayName = "Qwen2.5 1.5B Instruct",
            description = "Small and fast, decent multilingual output.",
            paramsB = "1.5B",
            qualityTier = 2,
            isTranslationFinetune = false,
            supportsVision = false,
            sizeBytes = 1_000_000_000L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            backends = setOf(LocalLlmBackendType.MLC_GPU),
            mlcHfRepo = "mlc-ai/Qwen2.5-1.5B-Instruct-q4f16_1-MLC",
            mlcModelLib = "Qwen2.5-1.5B-Instruct-q4f16_1-MLC",
        ),
        LocalLlmModel(
            id = "llama-3.2-1b-instruct",
            displayName = "Llama 3.2 1B Instruct",
            description = "Smallest usable option; basic quality, fastest on weak GPUs.",
            paramsB = "1B",
            qualityTier = 1,
            isTranslationFinetune = false,
            supportsVision = false,
            sizeBytes = 700_000_000L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            backends = setOf(LocalLlmBackendType.MLC_GPU),
            mlcHfRepo = "mlc-ai/Llama-3.2-1B-Instruct-q4f16_1-MLC",
            mlcModelLib = "Llama-3.2-1B-Instruct-q4f16_1-MLC",
        ),
        // -- ExecuTorch (NPU / CPU) ------------------------------------------------------------
        LocalLlmModel(
            id = "llama-3.2-3b-xnnpack",
            displayName = "Llama 3.2 3B (CPU)",
            description = "ExecuTorch XNNPACK CPU build; works on any device, slower than GPU/NPU.",
            paramsB = "3B",
            qualityTier = 3,
            isTranslationFinetune = false,
            supportsVision = false,
            sizeBytes = 2_300_000_000L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            backends = setOf(LocalLlmBackendType.EXECUTORCH_CPU),
            etHfRepo = "houri-app/executorch-llama-3.2-3b",
            etPteFile = "llama-3.2-3b-xnnpack.pte",
            etTokenizerFile = "tokenizer.model",
            requiresArtifacts = true,
        ),
        LocalLlmModel(
            id = "llama-3.2-1b-qnn",
            displayName = "Llama 3.2 1B (NPU)",
            description = "ExecuTorch Qualcomm QNN build for Snapdragon NPUs; fastest option on supported devices.",
            paramsB = "1B",
            qualityTier = 2,
            isTranslationFinetune = false,
            supportsVision = false,
            sizeBytes = 800_000_000L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            backends = setOf(LocalLlmBackendType.EXECUTORCH_NPU),
            etHfRepo = "houri-app/executorch-llama-3.2-1b",
            etPteFile = "llama-3.2-1b-qnn.pte",
            etTokenizerFile = "tokenizer.model",
            etSoC = "qualcomm",
            requiresArtifacts = true,
        ),
    )

    fun byId(id: String): LocalLlmModel? = allModels.find { it.id == id }

    /** Models that fit the given total RAM (the only enforced filter). */
    fun fitForRam(models: List<LocalLlmModel> = allModels, totalRamBytes: Long): List<LocalLlmModel> =
        models.filter { it.minRamBytes <= totalRamBytes }

    /** Whether the MLC-LLM runtime is bundled in this build (mlc4j classes present). */
    fun isMlcRuntimeBundled(): Boolean = runCatching {
        Class.forName("ai.mlc.mlcllm.JSONFFIEngine")
        true
    }.getOrDefault(false)

    /** Whether the ExecuTorch runtime is bundled in this build (always true when the AAR is a dependency). */
    fun isExecutorchBundled(): Boolean = runCatching {
        Class.forName("org.pytorch.executorch.extension.llm.LlmModule")
        true
    }.getOrDefault(false)

    /** Best model for this device — presented as a default, never enforced. */
    fun bestForDevice(
        totalRamBytes: Long,
        hasMlc: Boolean = isMlcRuntimeBundled(),
        hasExecutorch: Boolean = isExecutorchBundled(),
        soc: String = DeviceMemory.socManufacturer(),
    ): LocalLlmModel? {
        val fitting = fitForRam(totalRamBytes = totalRamBytes)
        val usable = fitting.filter { model ->
            when {
                LocalLlmBackendType.MLC_GPU in model.backends -> hasMlc
                model.backends.any { it == LocalLlmBackendType.EXECUTORCH_NPU } -> hasExecutorch && model.etSoC?.let { DeviceMemory.matchesSoc(soc, it) } ?: hasExecutorch
                else -> hasExecutorch
            }
        }
        val pool = if (usable.isNotEmpty()) usable else fitting
        return pool.sortedWith(
            compareByDescending<LocalLlmModel> { it.qualityTier }
                .thenBy { if (it.requiresArtifacts) 1 else 0 }
                .thenByDescending { it.sizeBytes },
        ).firstOrNull()
    }
}
