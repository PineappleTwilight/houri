package exh.yakuyomi

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
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Breadcrumb-aware LLM translator backed by OpenRouter / Gemini. This is the translation stage
 * injected into the library pipeline; it keeps Komikku's custom sliding-window breadcrumb prompt
 * (context notes about prior chapters) that the library's default [li.joye.yakuyomi.engine.LlmTranslator]
 * does not implement.
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
) : Translator {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun translate(queries: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (queries.isEmpty()) return@withContext emptyList()
        if (apiKey.isBlank()) {
            return@withContext fallback(queries)
        }
        val isEnFix = sourceLang.equals("EN", true) && targetLang.equals("EN", true)
        val prompt = buildPrompt(queries, sourceLang, targetLang, breadcrumb, isEnFix)
        val result = when (provider.lowercase()) {
            "gemini" -> callGemini(prompt, apiKey, model)
            else -> callOpenRouter(prompt, apiKey, model)
        }
        if (result == null) return@withContext fallback(queries)
        align(result, queries)
    }

    private fun fallback(queries: List<String>): List<String> {
        return if (offlineFallback) queries.map { it.trim() } else queries.map { it.trim() }
    }

    private fun align(result: List<String>, queries: List<String>): List<String> {
        return when {
            result.size == queries.size -> result
            result.size < queries.size -> result + queries.drop(result.size).map { it.trim() }
            else -> result.take(queries.size)
        }
    }

    private fun buildPrompt(texts: List<String>, sourceLang: String, targetLang: String, breadcrumb: String, isEnFix: Boolean): String {
        val joined = texts.joinToString("\n") { "- $it" }
        val breadcrumbSection = if (breadcrumb.isNotBlank()) "Context notes (sliding window):\n$breadcrumb\n\n" else ""
        return if (isEnFix) {
            "${breadcrumbSection}Fix grammar, preserve names, output only EN. Texts:\n$joined\n\nReturn each corrected line prefixed with '- ' exactly, one per input line, no extra commentary."
        } else {
            "${breadcrumbSection}Translate $sourceLang → $targetLang. Preserve names, honorifics, output only $targetLang. Texts:\n$joined\n\nReturn each translated line prefixed with '- ' exactly, one per input line, no extra commentary."
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
                    logcat { "OpenRouter error ${resp.code}: ${resp.message}" }
                    return@withContext null
                }
                val txt = resp.body.string() ?: return@withContext null
                parseOpenRouterResponse(txt)
            }
        } catch (e: Exception) {
            logcat { "OpenRouter call failed: ${e.message}" }
            null
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
                    logcat { "Gemini error ${resp.code}: ${resp.message}" }
                    return@withContext null
                }
                val txt = resp.body.string() ?: return@withContext null
                parseGeminiResponse(txt)
            }
        } catch (e: Exception) {
            logcat { "Gemini call failed: ${e.message}" }
            null
        }
    }

    private fun parseOpenRouterResponse(jsonStr: String): List<String>? {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val choices = root["choices"]?.jsonArray ?: return parseLinesFallback(jsonStr)
            val first = choices.firstOrNull()?.jsonObject ?: return parseLinesFallback(jsonStr)
            val message = first["message"]?.jsonObject ?: return parseLinesFallback(jsonStr)
            val content = message["content"]?.jsonPrimitive?.contentOrNull ?: return parseLinesFallback(jsonStr)
            parseLinesFromContent(content) ?: parseLinesFallback(jsonStr)
        } catch (_: Exception) {
            parseLinesFallback(jsonStr)
        }
    }

    private fun parseGeminiResponse(jsonStr: String): List<String>? {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: return parseLinesFallback(jsonStr)
            val first = candidates.firstOrNull()?.jsonObject ?: return parseLinesFallback(jsonStr)
            val content = first["content"]?.jsonObject ?: return parseLinesFallback(jsonStr)
            val parts = content["parts"]?.jsonArray ?: return parseLinesFallback(jsonStr)
            val text = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n")
            if (text.isBlank()) parseLinesFallback(jsonStr) else parseLinesFromContent(text)
        } catch (_: Exception) {
            parseLinesFallback(jsonStr)
        }
    }

    private fun parseLinesFromContent(content: String): List<String>? {
        val dashLines = content.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }.filter { it.isNotBlank() }
        if (dashLines.isNotEmpty()) return dashLines
        val numbered = content.lines().map { it.trim() }.mapNotNull { line ->
            Regex("""^\d+[.)]\s*(.+)""").find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        }
        if (numbered.isNotEmpty()) return numbered
        val split = content.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (split.isNotEmpty()) return split
        return null
    }

    private fun parseLinesFallback(jsonStr: String): List<String>? {
        return try {
            val lines = jsonStr.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }.filter { it.isNotBlank() }
            if (lines.isNotEmpty()) return lines
            val regex = Regex("\"content\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
            val match = regex.find(jsonStr)?.groupValues?.getOrNull(1) ?: return null
            val unescaped = match.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
            parseLinesFromContent(unescaped)
        } catch (_: Exception) {
            null
        }
    }
}
