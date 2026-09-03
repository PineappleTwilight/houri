package exh.yakuyomi

import android.content.Context
import exh.log.xLogE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * ExecuTorch backend (XNNPACK CPU or Qualcomm QNN / MediaTek NeuroPilot NPU). Directly depends
 * on the `org.pytorch:executorch-android` AAR from Maven Central. Generation is synchronous on
 * the calling thread, so it is executed on [Dispatchers.Default] with a timeout; the native
 * library is initialized through SoLoader (via reflection so the class split across soloader
 * artifact versions cannot break compilation).
 */
class ExecutorchLlmBackend private constructor(
    private val model: LocalLlmModel,
    private val module: LlmModule,
) : LocalLlmBackend {

    override val backendType: LocalLlmBackendType =
        if (model.backends.contains(LocalLlmBackendType.EXECUTORCH_NPU)) {
            LocalLlmBackendType.EXECUTORCH_NPU
        } else {
            LocalLlmBackendType.EXECUTORCH_CPU
        }

    companion object {
        /** Whether the executorch-android AAR is on the classpath (true with the Maven dependency). */
        fun isAvailable(): Boolean = runCatching {
            Class.forName("org.pytorch.executorch.extension.llm.LlmModule")
            true
        }.getOrDefault(false)

        fun create(
            context: Context,
            model: LocalLlmModel,
            modelDir: File,
            onError: (String) -> Unit = {},
        ): ExecutorchLlmBackend? {
            if (!isAvailable()) return null
            return try {
                initSoLoader(context)
                val pte = File(modelDir, model.etPteFile ?: return null)
                val tokenizer = File(modelDir, model.etTokenizerFile ?: return null)
                if (!pte.exists() || !tokenizer.exists()) {
                    onError("Model files missing for ${model.displayName}")
                    return null
                }
                val module = LlmModule(pte.absolutePath, tokenizer.absolutePath, 0.8f)
                module.load()
                ExecutorchLlmBackend(model, module)
            } catch (e: Throwable) {
                xLogE("ExecuTorch init failed: ${e.message}", e)
                onError("ExecuTorch init failed: ${e.message}")
                null
            }
        }

        private fun initSoLoader(context: Context) {
            runCatching {
                val soLoader = Class.forName("com.facebook.soloader.SoLoader")
                soLoader.getMethod("init", Context::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(null, context.applicationContext, false)
            }
        }
    }

    override suspend fun generate(request: LocalGenerateRequest): String? = withContext(Dispatchers.Default) {
        if (request.imageBytes != null) {
            logcat { "ExecuTorch vision input not supported yet; ignoring page image" }
        }
        val sb = StringBuilder()
        val resumed = AtomicBoolean(false)
        suspendCancellableCoroutine<String?> { cont ->
            cont.invokeOnCancellation {
                resumed.set(true)
                runCatching { module.stop() }
            }
            val callback = object : LlmCallback {
                override fun onResult(token: String) {
                    sb.append(token)
                }

                override fun onStats(statsJson: String) {
                    if (resumed.compareAndSet(false, true)) {
                        if (!cont.isCancelled) cont.resume(sb.toString().ifBlank { null })
                    }
                }

                override fun onError(errorCode: Int, message: String) {
                    logcat { "ExecuTorch error $errorCode: $message" }
                    if (resumed.compareAndSet(false, true)) {
                        if (!cont.isCancelled) cont.resume(sb.toString().ifBlank { null })
                    }
                }
            }
            Thread {
                try {
                    module.generate(request.prompt, callback)
                } catch (e: Throwable) {
                    logcat { "ExecuTorch generate threw: ${e.message}" }
                } finally {
                    // Generate returned without stats/error — treat accumulated output as final.
                    if (resumed.compareAndSet(false, true)) {
                        if (!cont.isCancelled) cont.resume(sb.toString().ifBlank { null })
                    }
                }
            }.apply {
                isDaemon = true
                name = "executorch-generate"
                start()
            }
        }
    }

    override suspend fun close() {
        runCatching { module.resetContext() }
    }
}
