package exh.yakuyomi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import li.joye.yakuyomi.engine.Translator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Breadcrumb-aware LLM translator backed by OpenRouter / Gemini / OpenAI-compatible providers.
 * This is the translation stage injected into the library pipeline; it keeps Komikku's custom
 * sliding-window breadcrumb prompt (context notes about prior chapters) that the library's
 * default [li.joye.yakuyomi.engine.LlmTranslator] does not implement.
 *
 * Failure handling: a failed provider call must NOT be silently reported as "translated". The
 * library pipeline treats `translatedText == sourceText` as "nothing to translate" and marks the
 * whole page SKIPPED (permanent, no retry). To make transient LLM/network failures visible and
 * retryable we throw [TranslationException]; the pipeline then reports the page as FAILED. When
 * [offlineFallback] is enabled the original text is returned unchanged so the page keeps its
 * original art (SKIPPED) instead of erroring.
 */
class YakuyomiTranslator(
    private val apiKey: String,
    private val sourceLang: String,
    private val targetLang: String,
    private val breadcrumb: String,
    private val provider: String,
    private val model: String,
    private val offlineFallback: Boolean,
    private val client: OkHttpClient,
    private val customBaseUrl: String = "",
    private val customHeaders: String = "",
) : Translator {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun translate(queries: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (queries.isEmpty()) return@withContext emptyList()
        if (apiKey.isBlank()) {
            if (offlineFallback) return@withContext queries.map { it.trim() }
            throw TranslationException("Yakuyomi API key not configured (set it in Settings → Translation)")
        }
        val isEnFix = sourceLang.equals("EN", true) && targetLang.equals("EN", true)
        val prompt = buildTranslationPrompt(queries, sourceLang, targetLang, breadcrumb, isEnFix)
        val result = try {
            when (provider.lowercase()) {
                "gemini" -> callGemini(prompt, apiKey, model)
                // OpenAI-compatible endpoints with a fixed base URL.
                "opencode_zen" -> callOpenAICompatible(prompt, apiKey, model, "https://opencode.ai/zen/v1", customHeaders)
                "nvidia_nim" -> callOpenAICompatible(prompt, apiKey, model, "https://integrate.api.nvidia.com/v1", customHeaders)
                // User-provided base URL.
                "custom_openai" -> callOpenAICompatible(prompt, apiKey, model, customBaseUrl, customHeaders)
                else -> callOpenRouter(prompt, apiKey, model)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            if (offlineFallback) return@withContext queries.map { it.trim() }
            throw e
        } catch (e: Exception) {
            if (offlineFallback) return@withContext queries.map { it.trim() }
            throw TranslationException("Yakuyomi $provider request failed: ${e.message}")
        }
        if (result == null) {
            if (offlineFallback) return@withContext queries.map { it.trim() }
            throw TranslationException("Yakuyomi $provider returned no usable translation")
        }
        alignTranslationLines(result, queries)
    }

    private suspend fun callOpenAICompatible(prompt: String, apiKey: String, model: String, baseUrl: String, customHeaders: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            if (baseUrl.isBlank()) throw TranslationException("Base URL not configured for this provider")
            val url = baseUrl.trimEnd('/') + "/chat/completions"
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", prompt)
                        },
                    )
                }
                put("max_tokens", 2048)
            }.toString().toRequestBody("application/json".toMediaType())
            val reqBuilder = Request.Builder()
                .url(url)
                .post(body)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
            parseCustomHeaders(customHeaders)?.forEach { (k, v) -> reqBuilder.header(k, v) }
            val req = reqBuilder.build()
            val callClient = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
            callClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw TranslationException("OpenAI-compatible HTTP ${resp.code}: ${resp.message}")
                }
                val txt = resp.body.string()
                parseOpenRouterResponse(txt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException("OpenAI-compatible call failed: ${e.message}", e)
        }
    }

    private fun parseCustomHeaders(raw: String): Map<String, String>? {
        if (raw.isBlank()) return emptyMap()
        return try {
            raw.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && it.contains(":") }
                .associate {
                    val idx = it.indexOf(":")
                    val k = it.substring(0, idx).trim()
                    val v = it.substring(idx + 1).trim()
                    k to v
                }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun callOpenRouter(prompt: String, apiKey: String, model: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://openrouter.ai/api/v1/chat/completions"
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", prompt)
                        },
                    )
                }
                put("max_tokens", 2048)
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(url)
                .post(body)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://github.com/komikku-app/komikku")
                .header("X-Title", "Komikku")
                .build()
            val callClient = client.newBuilder()
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
            callClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw TranslationException("OpenRouter HTTP ${resp.code}: ${resp.message}")
                }
                val txt = resp.body.string()
                parseOpenRouterResponse(txt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException("OpenRouter call failed: ${e.message}", e)
        }
    }

    private suspend fun callGemini(prompt: String, apiKey: String, model: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val raw = model.ifBlank { "gemini-1.5-flash" }
            val modelName = raw.substringAfterLast("/").substringBefore(":").trim()
            val geminiModel = when {
                modelName.isBlank() -> "gemini-1.5-flash"
                modelName.contains("gemma", ignoreCase = true) -> "gemini-1.5-flash"
                modelName.contains("gemini", ignoreCase = true) -> modelName
                else -> modelName
            }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$geminiModel:generateContent?key=$apiKey"
            val body = buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) } })
                }
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).header("Content-Type", "application/json").build()
            val callClient = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
            callClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw TranslationException("Gemini HTTP ${resp.code}: ${resp.message}")
                }
                val txt = resp.body.string()
                parseGeminiResponse(txt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException("Gemini call failed: ${e.message}", e)
        }
    }

    private fun parseOpenRouterResponse(jsonStr: String): List<String>? {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val choices = root["choices"]?.jsonArray ?: return parseTranslationLinesFromJson(jsonStr)
            val first = choices.firstOrNull()?.jsonObject ?: return parseTranslationLinesFromJson(jsonStr)
            val message = first["message"]?.jsonObject ?: return parseTranslationLinesFromJson(jsonStr)
            val content = message["content"]?.jsonPrimitive?.contentOrNull ?: return parseTranslationLinesFromJson(jsonStr)
            parseTranslationLines(content) ?: parseTranslationLinesFromJson(jsonStr)
        } catch (_: Exception) {
            parseTranslationLinesFromJson(jsonStr)
        }
    }

    private fun parseGeminiResponse(jsonStr: String): List<String>? {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: return parseTranslationLinesFromJson(jsonStr)
            val first = candidates.firstOrNull()?.jsonObject ?: return parseTranslationLinesFromJson(jsonStr)
            val content = first["content"]?.jsonObject ?: return parseTranslationLinesFromJson(jsonStr)
            val parts = content["parts"]?.jsonArray ?: return parseTranslationLinesFromJson(jsonStr)
            val text = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n")
            if (text.isBlank()) parseTranslationLinesFromJson(jsonStr) else parseTranslationLines(text)
        } catch (_: Exception) {
            parseTranslationLinesFromJson(jsonStr)
        }
    }
}

/**
 * Raised when the LLM provider cannot complete a translation request (missing API key, HTTP
 * error, unparseable/empty response). The library pipeline converts this into a FAILED page
 * so it can be retried instead of being permanently marked SKIPPED.
 */
class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)