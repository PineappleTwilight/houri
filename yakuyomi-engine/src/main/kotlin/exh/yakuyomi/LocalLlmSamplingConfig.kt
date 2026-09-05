package exh.yakuyomi

import kotlinx.serialization.Serializable

/**
 * llama.cpp sampling/context configuration for the on-device LLM provider, stored per model.
 * Defaults are sensible for instruct models; [contextLength] is seeded from the model's own
 * context and [temperature] from whether it is a translation finetune. A [numThreads] of 0
 * means "auto" (cores - 2, minimum 2). [gpuLayers] of 0 keeps everything on the CPU; raise it
 * (or use -1 for all layers) to offload when the runtime bundles a GPU backend — the runtime
 * falls back to CPU automatically if the offload cannot load.
 */
@Serializable
data class LocalLlmSamplingConfig(
    val temperature: Float = 0.3f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 768,
    val contextLength: Int = 3072,
    val numThreads: Int = 0,
    val gpuLayers: Int = -1,
) {
    val resolvedThreads: Int
        get() = if (numThreads <= 0) {
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)
        } else {
            numThreads.coerceIn(1, 8)
        }

    fun validated(): LocalLlmSamplingConfig = copy(
        temperature = temperature.coerceIn(0f, 2f),
        topP = topP.coerceIn(0f, 1f),
        topK = topK.coerceIn(1, 100),
        repeatPenalty = repeatPenalty.coerceIn(0.8f, 2f),
        maxTokens = maxTokens.coerceIn(64, 4096),
        contextLength = contextLength.coerceIn(512, 16384),
        numThreads = numThreads.coerceIn(0, 8),
        gpuLayers = gpuLayers.coerceIn(-1, 100),
    )
}
