package exh.yakuyomi

import com.llamatik.library.platform.LlamaBridge
import exh.log.xLogE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * llama.cpp backend (Llamatik runtime). Loads any GGUF file from a local path and generates
 * text synchronously on the native side, so calls are serialized on a background thread.
 * Vision input is not supported by the text-only llama.cpp path; the page image is ignored
 * (vision-capable catalog entries degrade to text-only prompting).
 */
class LlamaCppLlmBackend private constructor(
    private val model: LocalLlmModel,
    private val modelFile: File,
) : LocalLlmBackend {

    override val backendType: LocalLlmBackendType = LocalLlmBackendType.LLAMACPP

    private val lock = Object()

    companion object {
        /** Whether the Llamatik runtime is on the classpath (true with the Maven dependency). */
        fun isAvailable(): Boolean = runCatching {
            Class.forName("com.llamatik.library.platform.LlamaBridge")
            true
        }.getOrDefault(false)

        fun create(
            model: LocalLlmModel,
            modelDir: File,
            onError: (String) -> Unit = {},
        ): LlamaCppLlmBackend? {
            if (!isAvailable()) return null
            val file = when {
                model.isCustom -> model.ggufFile?.let { File(it) }
                else -> model.ggufFile?.let { File(modelDir, it) }
            } ?: return null
            if (!file.exists() || file.length() < 1_000_000L) {
                onError("Model file not found: ${file.path}")
                return null
            }
            return try {
                val loaded = LlamaBridge.initGenerateModel(file.absolutePath)
                if (!loaded) {
                    onError("llama.cpp failed to load ${model.displayName}")
                    return null
                }
                LlamaCppLlmBackend(model, file)
            } catch (e: Throwable) {
                xLogE("llama.cpp init failed: ${e.message}", e)
                onError("llama.cpp init failed: ${e.message}")
                null
            }
        }
    }

    override suspend fun generate(request: LocalGenerateRequest): String? = withContext(Dispatchers.Default) {
        if (request.imageBytes != null) {
            logcat { "llama.cpp vision input not supported; ignoring page image" }
        }
        val resumed = AtomicBoolean(false)
        suspendCancellableCoroutine<String?> { cont ->
            cont.invokeOnCancellation { resumed.set(true) }
            Thread {
                try {
                    val text = synchronized(lock) {
                        LlamaBridge.generate(request.prompt)
                    }
                    if (resumed.compareAndSet(false, true) && !cont.isCancelled) {
                        cont.resume(text.takeIf { it.isNotBlank() })
                    }
                } catch (e: Throwable) {
                    logcat { "llama.cpp generate threw: ${e.message}" }
                    if (resumed.compareAndSet(false, true) && !cont.isCancelled) {
                        cont.resume(null)
                    }
                }
            }.apply {
                isDaemon = true
                name = "llamacpp-generate"
                start()
            }
        }
    }

    override suspend fun close() {
        // Llamatik keeps a single loaded model; the next create() replaces it.
    }
}
