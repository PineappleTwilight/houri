package exh.yakuyomi

/**
 * A loaded on-device LLM runtime ready to generate text. Implementations wrap MLC-LLM (GPU,
 * reflection-based because the mlc4j runtime is built from source) and ExecuTorch (NPU/CPU,
 * direct dependency). Instances are heavyweight (hundreds of MB loaded into memory), so a
 * single instance is kept alive per selected model by [LocalLlmManager].
 */
interface LocalLlmBackend {
    val backendType: LocalLlmBackendType

    /** Generates a full text completion. Returns null when the runtime cannot produce output. */
    suspend fun generate(request: LocalGenerateRequest): String?

    /** Releases native resources. Safe to call multiple times. */
    suspend fun close()
}
