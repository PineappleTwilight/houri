package exh.yakuyomi

import android.util.Base64
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MLC-LLM GPU backend (OpenCL on Adreno/Mali). The mlc4j runtime is NOT on Maven Central — it is
 * built from the MLC-LLM source tree (`mlc_llm package`) and bundled into the app. To keep this
 * module compiling without that artifact, the `ai.mlc.mlcllm.JSONFFIEngine` Java class is driven
 * through reflection: when the runtime is absent, [isAvailable] returns false and the backend
 * simply reports "not bundled" instead of crashing.
 *
 * The request/response JSON follows MLC's OpenAI-compatible protocol (the same payloads
 * [ai.mlc.mlcllm.MLCEngine] serializes), so a bundled mlc4j runtime works unchanged.
 */
class MlcLlmBackend private constructor(
    private val model: LocalLlmModel,
    private val engine: Any,
    private val engineClass: Class<*>,
    private val modelDir: File,
    private val modelLibRef: String,
    private val callbacks: ConcurrentHashMap<String, (String) -> Unit>,
) : LocalLlmBackend {

    override val backendType: LocalLlmBackendType = LocalLlmBackendType.MLC_GPU

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val ENGINE_CLASS = "ai.mlc.mlcllm.JSONFFIEngine"

        private val json = Json { ignoreUnknownKeys = true }

        /** Whether the mlc4j runtime is bundled in this build. */
        fun isAvailable(): Boolean = runCatching {
            Class.forName(ENGINE_CLASS)
            true
        }.getOrDefault(false)

        /**
         * Creates the engine, registers the stream callback and starts the two background loops
         * (inference + stream-back), then loads the model. Returns null when the runtime is not
         * bundled or the model cannot be loaded.
         */
        fun create(
            model: LocalLlmModel,
            modelDir: File,
            libFile: File?,
            onError: (String) -> Unit = {},
        ): MlcLlmBackend? {
            if (!isAvailable()) return null
            return try {
                val engineClass = Class.forName(ENGINE_CLASS)
                val engine = engineClass.getDeclaredConstructor().newInstance()
                val callbacks = ConcurrentHashMap<String, (String) -> Unit>()

                val initMethod = engineClass.getMethod("initBackgroundEngine", engineClass.getDeclaredClasses().first { it.simpleName == "KotlinFunction" })
                val callbackProxy = Proxy.newProxyInstance(
                    engineClass.classLoader,
                    arrayOf(initMethod.parameterTypes[0]),
                ) { _, _, args ->
                    val payload = args[0] as? String
                    if (payload != null) dispatchStream(payload, callbacks)
                    null
                }
                initMethod.invoke(engine, callbackProxy)

                startLoop(engine, engineClass, "runBackgroundLoop")
                startLoop(engine, engineClass, "runBackgroundStreamBackLoop")

                val modelLibRef = when {
                    libFile != null && libFile.exists() -> libFile.absolutePath
                    else -> "system://${model.mlcModelLib}"
                }
                val config = buildJsonObject {
                    put("model", modelDir.absolutePath)
                    put("model_lib", modelLibRef)
                    put("mode", "interactive")
                }.toString()
                engineClass.getMethod("reload", String::class.java).invoke(engine, config)

                MlcLlmBackend(model, engine, engineClass, modelDir, modelLibRef, callbacks)
            } catch (e: Throwable) {
                xLogE("MLC backend init failed: ${e.message}", e)
                onError("MLC init failed: ${e.message}")
                null
            }
        }

        private fun startLoop(engine: Any, engineClass: Class<*>, method: String) {
            val m = engineClass.getMethod(method)
            Thread {
                try {
                    m.invoke(engine)
                } catch (_: Throwable) {
                }
            }.apply {
                isDaemon = true
                name = "mlc-$method"
                start()
            }
        }

        private fun dispatchStream(payload: String, callbacks: ConcurrentHashMap<String, (String) -> Unit>) {
            try {
                val responses = json.decodeFromString<List<MlcStreamResponse>>(payload)
                responses.forEach { res ->
                    callbacks[res.id]?.invoke(payload)
                }
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun generate(request: LocalGenerateRequest): String? = withContext(Dispatchers.Default) {
        val requestId = UUID.randomUUID().toString()
        val responseText = StringBuilder()
        val finished = AtomicBoolean(false)
        val completion = CompletableDeferred<String?>()

        callbacks[requestId] = { payload ->
            if (!finished.get()) {
                try {
                    val responses = json.decodeFromString<List<MlcStreamResponse>>(payload)
                    responses.forEach { res ->
                        res.choices.forEach { choice ->
                            choice.delta?.content?.let { responseText.append(it) }
                            if (choice.finish_reason != null || res.usage != null) {
                                finished.set(true)
                                if (!completion.isCompleted) completion.complete(responseText.toString())
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        try {
            withTimeout(request.maxTokens * 200L + 30_000L) {
                val body = buildRequest(request)
                engineClass.getMethod("chatCompletion", String::class.java, String::class.java)
                    .invoke(engine, body, requestId)
                suspendCancellableCoroutine<String?> { cont ->
                    cont.invokeOnCancellation { finished.set(true) }
                    scope.launch {
                        try {
                            cont.resume(completion.await())
                        } catch (e: CancellationException) {
                            cont.resumeWithException(e)
                        } catch (e: Exception) {
                            cont.resume(null)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Vision payloads may be rejected by text-only builds; retry without the image.
            if (request.imageBytes != null) {
                try {
                    val body = buildRequest(request.copy(imageBytes = null))
                    engineClass.getMethod("chatCompletion", String::class.java, String::class.java)
                        .invoke(engine, body, requestId)
                    suspendCancellableCoroutine<String?> { cont ->
                        cont.invokeOnCancellation { finished.set(true) }
                        scope.launch {
                            try {
                                cont.resume(completion.await())
                            } catch (e: CancellationException) {
                                cont.resumeWithException(e)
                            } catch (e: Exception) {
                                cont.resume(null)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    null
                }
            } else {
                logcat { "MLC generate failed: ${e.message}" }
                null
            }
        } finally {
            callbacks.remove(requestId)
        }
    }

    override suspend fun close() {
        runCatching {
            engineClass.getMethod("unload").invoke(engine)
        }
    }

    private fun buildRequest(request: LocalGenerateRequest): String = buildJsonObject {
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    if (request.imageBytes != null && model.supportsVision) {
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", request.prompt)
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put(
                                            "url",
                                            "data:image/jpeg;base64," + Base64.encodeToString(request.imageBytes, Base64.NO_WRAP),
                                        )
                                    }
                                },
                            )
                        }
                    } else {
                        put("content", request.prompt)
                    }
                },
            )
        }
        put("model", model.mlcModelLib ?: model.id)
        put("max_tokens", request.maxTokens)
        put("temperature", request.temperature)
        put("stream", true)
    }.toString()
}

@Serializable
internal data class MlcStreamResponse(
    val id: String = "",
    val choices: List<MlcChoice> = emptyList(),
    val usage: MlcUsage? = null,
)

@Serializable
internal data class MlcChoice(
    val delta: MlcDelta? = null,
    val finish_reason: String? = null,
)

@Serializable
internal data class MlcDelta(val content: String? = null)

@Serializable
internal data class MlcUsage(val total_tokens: Int = 0)
