package exh.yakuyomi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches the list of available models from each provider's models endpoint so the settings
 * model selector can be populated automatically. Falls back to a curated default list when the
 * endpoint is unreachable (offline, missing API key, etc).
 */
object ModelCatalog {

    private val json = Json { ignoreUnknownKeys = true }

    /** Curated per-provider model list used when the live endpoint can't be reached. */
    val fallbackModels: Map<String, List<String>> = mapOf(
        "openrouter" to listOf(
            "google/gemma-2-9b-it:free",
            "meta-llama/llama-3.1-8b-instruct:free",
            "mistralai/mistral-7b-instruct:free",
            "google/gemini-2.0-flash-exp:free",
            "openai/gpt-4o-mini",
            "anthropic/claude-3.5-sonnet",
        ),
        "gemini" to listOf(
            "gemini-1.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-pro",
            "gemini-2.5-flash",
        ),
        "opencode_zen" to listOf(
            "deepseek-v4-flash",
            "nemotron-3-ultra-free",
            "nemotron-3.5-lightning-free",
            "mimo-v2.5-free",
            "ling-3.0-flash-fin-free",
            "minimax-m3",
            "kimi-k3",
        ),
        "nvidia_nim" to listOf(
            "deepseek-ai/deepseek-r1",
            "meta/llama-3.3-70b-instruct",
            "mistralai/mistral-nemo",
            "nvidia/nemotron-3-8b",
        ),
        "custom_openai" to emptyList(),
    )

    fun modelsEndpoint(provider: String, baseUrl: String): String? = when (provider.lowercase()) {
        "openrouter" -> "https://openrouter.ai/api/v1/models"
        "gemini" -> "https://generativelanguage.googleapis.com/v1beta/models"
        "opencode_zen" -> "https://opencode.ai/zen/v1/models"
        "nvidia_nim" -> "https://integrate.api.nvidia.com/v1/models"
        "custom_openai" -> if (baseUrl.isBlank()) null else baseUrl.trimEnd('/') + "/models"
        else -> null
    }

    suspend fun fetchModels(
        provider: String,
        apiKey: String,
        baseUrl: String,
        client: OkHttpClient,
    ): List<String> {
        val endpoint = modelsEndpoint(provider, baseUrl) ?: return emptyList()
        val isGemini = provider.equals("gemini", ignoreCase = true)
        val url = if (isGemini && apiKey.isNotBlank()) "$endpoint?key=$apiKey" else endpoint
        val reqBuilder = Request.Builder().url(url).get()
        if (apiKey.isNotBlank() && !isGemini) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        val req = reqBuilder.build()
        val callClient = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        return try {
            callClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val text = resp.body?.string() ?: return@use emptyList()
                parseModelIds(text, isGemini)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Parses OpenAI-compatible `data[].id` or Gemini `models[].name` payloads. */
    private fun parseModelIds(text: String, gemini: Boolean): List<String> {
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            val raw = if (gemini) {
                root["models"]?.jsonArray?.mapNotNull {
                    it.jsonObject["name"]?.jsonPrimitive?.content?.removePrefix("models/")
                }
            } else {
                root["data"]?.jsonArray?.mapNotNull {
                    it.jsonObject["id"]?.jsonPrimitive?.content
                }
            }
            raw.orEmpty()
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }
}