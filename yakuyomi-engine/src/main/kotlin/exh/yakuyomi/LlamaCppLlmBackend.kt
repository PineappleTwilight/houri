package exh.yakuyomi

import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import com.llamatik.library.platform.MultimodalBridge
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
 *
 * Hardened for real models: sampling/context params are set explicitly (the native defaults
 * can produce empty output — "the model does nothing"), the model's chat template is applied
 * when it has one, and vision-capable models (with a downloaded mmproj) route image requests
 * through [MultimodalBridge]. Bridges are loaded lazily per use so a vision model doesn't hold
 * two copies of its weights in RAM.
 */
class LlamaCppLlmBackend private constructor(
    private val model: LocalLlmModel,
    private val modelFile: File,
    private val mmprojFile: File?,
    private val sampling: LocalLlmSamplingConfig,
) : LocalLlmBackend {

    override val backendType: LocalLlmBackendType = LocalLlmBackendType.LLAMACPP

    private val lock = Object()
    private val textReady = AtomicBoolean(false)
    private val visionReady = AtomicBoolean(false)

    companion object {
        /** Whether the Llamatik runtime is on the classpath (true with the Maven dependency). */
        fun isAvailable(): Boolean = runCatching {
            Class.forName("com.llamatik.library.platform.LlamaBridge")
            true
        }.getOrDefault(false)

        fun create(
            model: LocalLlmModel,
            modelDir: File,
            sampling: LocalLlmSamplingConfig = LocalLlmSamplingConfig(),
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
            val mmproj = when {
                model.isCustom -> model.mmprojFile?.let { File(it) }?.takeIf { it.exists() && it.length() > 1_000_000L }
                else ->
                    model.mmprojFile
                        ?.takeIf { !model.mmprojRepo.isNullOrBlank() }
                        ?.let { File(modelDir, it) }
                        ?.takeIf { it.exists() && it.length() > 1_000_000L }
            }
            return LlamaCppLlmBackend(model, file, mmproj, sampling)
        }
    }

    /** Explicit llama.cpp sampling/context config — the native defaults can be unusable. */
    private fun configureParams() {
        runCatching {
            LlamaBridge.updateGenerateParams(
                temperature = sampling.temperature,
                maxTokens = sampling.maxTokens.coerceIn(64, 8192),
                topP = sampling.topP.coerceIn(0f, 1f),
                topK = sampling.topK.coerceIn(1, 100),
                repeatPenalty = sampling.repeatPenalty.coerceIn(0.8f, 2f),
                contextLength = sampling.contextLength.coerceAtLeast(512),
                numThreads = sampling.resolvedThreads,
                useMmap = true,
                flashAttention = true,
                batchSize = 512,
                gpuLayers = sampling.gpuLayers,
            )
        }.onFailure { logcat { "llama.cpp updateGenerateParams failed: ${it.message}" } }
    }

    private fun ensureTextBridge(): Boolean {
        if (textReady.get()) return true
        return synchronized(lock) {
            if (textReady.get()) {
                true
            } else {
                // gpu_layers is read at MODEL LOAD, so params must be set before init; the
                // runtime retries with 0 layers itself when the offload cannot load.
                configureParams()
                val ok = runCatching {
                    LlamaBridge.initGenerateModel(modelFile.absolutePath)
                }.getOrDefault(false)
                if (ok) {
                    textReady.set(true)
                }
                ok
            }
        }
    }

    private fun ensureVisionBridge(): Boolean {
        if (visionReady.get()) return true
        val mmproj = mmprojFile ?: return false
        return synchronized(lock) {
            if (visionReady.get()) {
                true
            } else {
                val ok = runCatching {
                    MultimodalBridge.initModel(modelFile.absolutePath, mmproj.absolutePath)
                }.getOrDefault(false)
                if (ok) visionReady.set(true)
                ok
            }
        }
    }

    override suspend fun generate(request: LocalGenerateRequest): String? = withContext(Dispatchers.Default) {
        val resumed = AtomicBoolean(false)
        suspendCancellableCoroutine<String?> { cont ->
            cont.invokeOnCancellation { resumed.set(true) }
            Thread {
                try {
                    val text = synchronized(lock) {
                        if (request.imageBytes != null && mmprojFile != null && ensureVisionBridge()) {
                            // Vision: the multimodal bridge reads the page directly.
                            analyzeVision(request.imageBytes, request.prompt)
                        } else {
                            if (request.imageBytes != null) {
                                logcat { "llama.cpp vision requested but mmproj missing; using text-only" }
                            }
                            if (!ensureTextBridge()) {
                                logcat { "llama.cpp text bridge init failed" }
                                return@Thread
                            }
                            // Apply the model's chat template — instruct models can output
                            // nothing when the special tokens are missing.
                            val prompt: String = runCatching {
                                LlamaBridge.applyChatTemplate(listOf("user" to request.prompt), true)
                            }.getOrNull() ?: request.prompt
                            LlamaBridge.generate(prompt)
                        }
                    }?.let { cleanInstructArtifacts(it) }
                    if (resumed.compareAndSet(false, true) && !cont.isCancelled) {
                        cont.resume(text?.takeIf { it.isNotBlank() })
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

    /** Runs a multimodal generation, collecting the streamed answer. Must hold [lock]. */
    private fun analyzeVision(imageBytes: ByteArray, prompt: String): String? {
        val sb = StringBuilder()
        var failed: String? = null
        MultimodalBridge.analyzeImageBytesStream(
            imageBytes,
            prompt,
            object : GenStream {
                override fun onDelta(token: String) {
                    sb.append(token)
                }

                override fun onComplete() = Unit

                override fun onError(error: String) {
                    failed = error
                }
            },
        )
        if (failed != null) {
            logcat { "llama.cpp vision failed: $failed" }
            return null
        }
        return cleanInstructArtifacts(sb.toString()).takeIf { it.isNotBlank() }
    }

    /** Strips chat-template/special-token residue the model may echo back around its answer. */
    private fun cleanInstructArtifacts(raw: String): String {
        var s = raw
        // Trailing/leading template markers from gemma/llama chat formats.
        s = s.replace(Regex("(?i)(\\[end_of_turn\\]|<end_of_turn>|</s>|<\\|eot_id\\|>|<start_of_turn>model\\s*|assistant\\s*:?\\s*|<\\|start_header_id\\|>assistant<\\|end_header_id\\|>\\s*)"), "")
        // Image-token residue from vision models.
        s = s.replace(Regex("<start_of_image>|<image_soft_token>|\\[image\\]"), "")
        // Trim repeated blank lines and surrounding whitespace.
        s = s.replace(Regex("\\n{3,}"), "\n\n").trim()
        return s
    }

    override suspend fun close() {
        runCatching {
            if (visionReady.get()) MultimodalBridge.release()
        }
        runCatching {
            if (textReady.get()) LlamaBridge.shutdown()
        }
    }
}
