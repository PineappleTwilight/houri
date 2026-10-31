package com.llamatik.library.platform

/**
 * llama.cpp text generation, vendored from the Llamatik fork.
 *
 * ## Why this exists
 *
 * The published `com.llamatik:library` AAR ships a **CPU-only** llama.cpp build: its
 * `jni/<abi>/` directory contains only `libggml-cpu.so` and no GPU backend plugin, so
 * its `gpuLayers` parameter offloads nothing. The same JNI layer is therefore rebuilt
 * from source in `src/main/cpp` (see that `CMakeLists.txt`), optionally with the ggml
 * Vulkan backend compiled in.
 *
 * ## Contract with the native side
 *
 * This file is a deliberately trimmed copy of the fork's
 * `library/src/androidMain/kotlin/.../LlamaBridge.android.kt` with the `actual`
 * modifiers removed. Every `external` declaration here must have a matching
 * `Java_com_llamatik_library_platform_LlamaBridge_*` symbol in
 * `external/llamatik/library/src/commonMain/cpp/llama_jni.cpp`; adding a function
 * without adding the C++ counterpart fails at runtime with `UnsatisfiedLinkError`.
 *
 * The package name is load-bearing — JNI mangles it into the symbol names.
 */
object LlamaBridge {

    init {
        System.loadLibrary("llama_jni")
    }

    /** Loads [modelPath] as the generation model. Must be preceded by [updateGenerateParams]. */
    external fun initGenerateModel(modelPath: String): Boolean

    /** One-shot completion for an already-formatted [prompt]. */
    external fun generate(prompt: String): String

    /** The chat template embedded in the loaded GGUF, or null when unavailable. */
    external fun getModelChatTemplate(): String?

    /** The `general.finetune` GGUF metadata key, e.g. `instruct` / `chat`. */
    external fun getModelFinetuneType(): String?

    /** Frees the native context. */
    external fun shutdown()

    external fun nativeCancelGenerate()

    private external fun nativeApplyChatTemplate(
        template: String?,
        roles: Array<String>,
        contents: Array<String>,
        addAssistantPrefix: Boolean,
    ): String?

    /**
     * Renders [messages] with the model's own embedded chat template.
     * Pass `addAssistantPrefix = true` when starting a new generation.
     * Returns null when the model is not loaded or has no template.
     */
    fun applyChatTemplate(messages: List<Pair<String, String>>, addAssistantPrefix: Boolean): String? {
        val roles = messages.map { it.first }.toTypedArray()
        val contents = messages.map { it.second }.toTypedArray()
        return nativeApplyChatTemplate(getModelChatTemplate(), roles, contents, addAssistantPrefix)
    }

    /**
     * Applies llama.cpp sampling/context configuration.
     *
     * [contextLength], [numThreads], [useMmap], [flashAttention], [batchSize] and
     * [gpuLayers] are only read at model load, so they must be set **before**
     * [initGenerateModel] to have any effect.
     *
     * [gpuLayers] is `-1` for all layers, `0` for CPU-only, `N` for exactly `N`
     * layers. When the build has no GPU backend compiled in, llama.cpp logs
     * `compiled without support for GPU offload` and silently runs on the CPU — use
     * `exh.yakuyomi.LocalLlmAccelerator` to find out whether offload is real before
     * relying on it.
     */
    private external fun nativeUpdateGenerationParams(
        temperature: Float,
        maxTokens: Int,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        contextLength: Int,
        numThreads: Int,
        useMmap: Boolean,
        flashAttention: Boolean,
        batchSize: Int,
        gpuLayers: Int,
    )

    fun updateGenerateParams(
        temperature: Float,
        maxTokens: Int,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        contextLength: Int,
        numThreads: Int,
        useMmap: Boolean,
        flashAttention: Boolean,
        batchSize: Int,
        gpuLayers: Int = 0,
    ) {
        nativeUpdateGenerationParams(
            temperature,
            maxTokens,
            topP,
            topK,
            repeatPenalty,
            contextLength,
            numThreads,
            useMmap,
            flashAttention,
            batchSize,
            gpuLayers,
        )
    }
}
