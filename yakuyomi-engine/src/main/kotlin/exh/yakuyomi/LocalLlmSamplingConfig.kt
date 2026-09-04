package exh.yakuyomi

import kotlinx.serialization.Serializable

/**
 * llama.cpp sampling/context configuration for the on-device LLM provider, stored per model.
 * Defaults are sensible for instruct models; [contextLength] is seeded from the model's own
 * context and [temperature] from whether it is a translation finetune. A [numThreads] of 0
 * means "auto" (cores - 2, minimum 2).
 */
@Serializable
data class LocalLlmSamplingConfig(
    val temperature: Float = 0.3f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,
    val contextLength: Int = 4096,
    val numThreads: Int = 0,
) {
    val resolvedThreads: Int
        get() = if (numThreads <= 0) {
            (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)
        } else {
            numThreads
        }
}
