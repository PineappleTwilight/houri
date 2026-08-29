package exh.yakuyomi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.log.xLogD
import exh.log.xLogE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@SingleIn(AppScope::class)
@Inject
class TranslationManager(
    private val context: Context,
    private val prefs: TranslationPreferences,
    private val cache: TranslationCache,
    private val engine: YakuyomiEngine,
    private val notes: BreadcrumbNotes,
    private val client: OkHttpClient,
    private val perMangaStore: TranslateMangaStore,
    private val preferenceStore: PreferenceStore,
    private val status: TranslationStatus,
    private val models: ModelManager,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun isEnabled(): Boolean = prefs.enabled().get()

    fun isGated(): Boolean {
        val incognito = preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false).get()
        val censor = preferenceStore.getBoolean("pref_censor_lewd_manga", false).get()
        return incognito || censor
    }

    suspend fun shouldTranslate(): Boolean {
        if (!isEnabled()) return false
        if (isGated()) {
            xLogD("Translation gated: incognito/censor")
            return false
        }
        if (!models.isReady()) {
            xLogD("Translation gated: AI models not installed")
            return false
        }
        return true
    }

    suspend fun shouldTranslateForManga(mangaId: Long): Boolean {
        if (!shouldTranslate()) return false
        return perMangaStore.isEnabled(mangaId)
    }

    fun isPerMangaEnabled(mangaId: Long): Boolean = perMangaStore.isEnabled(mangaId)

    fun setPerMangaEnabled(mangaId: Long, enabled: Boolean) = perMangaStore.setEnabled(mangaId, enabled)

    suspend fun translatePage(
        mangaId: Long,
        chapterId: Long,
        imageBytes: ByteArray,
        pageIndex: Int,
        sourceLangHint: String = "JA",
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!prefs.enabled().get() || isGated() || !perMangaStore.isEnabled(mangaId)) return@withContext null
        val targetLang = prefs.targetLang().get().ifBlank { "EN" }
        val model = prefs.model().get().ifBlank { "google/gemma-2-9b-it:free" }
        val cacheEnabled = prefs.cacheEnabled().get()
        val pageHash = cache.pageHash(imageBytes)
        if (cacheEnabled) {
            cache.getIfExists(pageHash, targetLang, model)?.let { f ->
                try {
                    val bytes = f.readBytes()
                    if (bytes.isNotEmpty()) {
                        status.pageCached(mangaId, chapterId, pageIndex)
                        return@withContext bytes
                    }
                } catch (_: Exception) {}
            }
        }
        if (!models.isReady()) {
            xLogD("Translation gated: AI models not installed")
            return@withContext null
        }
        status.pageTranslating(mangaId, chapterId, pageIndex)
        var bitmap: Bitmap? = null
        var cleaned: Bitmap? = null
        var typeset: Bitmap? = null
        try {
            // Decode with bounds check to avoid OOM on huge images (e.g., long strip)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                status.pageError(mangaId, chapterId, pageIndex, "Unable to decode image bounds")
                return@withContext null
            }
            // If image is > 4096 on either side, downsample for detection (keep original for final typeset)
            val needsSample = opts.outWidth > 4096 || opts.outHeight > 4096
            val sampleOpts = if (needsSample) BitmapFactory.Options().apply { inSampleSize = 2 } else null
            bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, sampleOpts)
            if (bitmap == null) {
                status.pageError(mangaId, chapterId, pageIndex, "Unable to decode image")
                return@withContext null
            }

            val blocks = engine.detectTextBlocks(bitmap)
            if (blocks.isEmpty()) {
                // No text detected; page passes through unchanged.
                status.pageSkipped(mangaId, chapterId, pageIndex)
                return@withContext null
            }
            val ocrBlocks = engine.ocrBlocks(bitmap, blocks)
            val originalTexts = ocrBlocks.mapNotNull { it.text.trim().takeIf { t -> t.isNotEmpty() } }
            if (originalTexts.isEmpty()) {
                status.pageSkipped(mangaId, chapterId, pageIndex)
                return@withContext null
            }
            // Ensure ocrBlocks and translated alignment: filter ocrBlocks to those with non-empty text
            val filteredOcr = ocrBlocks.filter { it.text.trim().isNotEmpty() }
            if (filteredOcr.isEmpty()) {
                status.pageSkipped(mangaId, chapterId, pageIndex)
                return@withContext null
            }

            // Build prompt with breadcrumb context (capped)
            val breadcrumb = notes.buildContextPrompt(mangaId)
            val translatedTexts = translateTexts(originalTexts, sourceLangHint, targetLang, breadcrumb)
            // Ensure we have same count; if mismatch, zip will truncate, so fallback to original count check
            if (translatedTexts.isEmpty()) {
                status.pageError(mangaId, chapterId, pageIndex, "No translated text produced")
                return@withContext null
            }
            val pairedCount = minOf(filteredOcr.size, translatedTexts.size)
            if (pairedCount == 0) {
                status.pageError(mangaId, chapterId, pageIndex, "Translation produced no usable lines")
                return@withContext null
            }
            val paired = filteredOcr.take(pairedCount).zip(translatedTexts.take(pairedCount))

            cleaned = engine.removeText(bitmap, filteredOcr)
            typeset = engine.typeset(cleaned, paired, targetLang)
            val webp = engine.bitmapToWebP(typeset, quality = 85)
            if (cacheEnabled) {
                cache.put(pageHash, targetLang, model, webp)
            }
            // Append breadcrumb only after successful translation
            try {
                notes.appendFromTranslation(mangaId, chapterId, translatedTexts)
            } catch (_: Exception) {}
            status.pageDone(mangaId, chapterId, pageIndex)
            webp
        } catch (e: Exception) {
            xLogE("translatePage failed", e)
            status.pageError(mangaId, chapterId, pageIndex, e.message ?: "Unknown translation error")
            null
        } finally {
            // Recycle transient bitmaps to reduce peak memory; bitmap is the sampled decode,
            // cleaned/typeset are copies. Do not recycle if they are same instance.
            try {
                if (cleaned != null && cleaned !== bitmap) cleaned.recycle()
            } catch (_: Exception) {}
            try {
                if (typeset != null && typeset !== bitmap && typeset !== cleaned) typeset.recycle()
            } catch (_: Exception) {}
            // Note: bitmap itself is not recycled here because caller may still hold reference?
            // In this flow bitmap is local, safe to recycle if not same as typeset/cleaned
            try {
                if (bitmap != null && bitmap !== cleaned && bitmap !== typeset) bitmap.recycle()
            } catch (_: Exception) {}
        }
    }

    private suspend fun translateTexts(
        texts: List<String>,
        sourceLang: String,
        targetLang: String,
        breadcrumb: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val apiKey = prefs.apiKey().get()
        if (apiKey.isBlank()) {
            if (prefs.offlineFallback().get()) return@withContext mlKitFallback(texts, targetLang)
            return@withContext texts.map { it.trim() }
        }
        // EN→EN grammar/vocab fix uses same prompt
        val isEnFix = sourceLang.equals("EN", true) && targetLang.equals("EN", true)
        val prompt = buildPrompt(texts, sourceLang, targetLang, breadcrumb, isEnFix)
        val provider = prefs.provider().get().lowercase()
        val model = prefs.model().get().ifBlank { "google/gemma-2-9b-it:free" }
        val result = when (provider) {
            "gemini" -> callGemini(prompt, apiKey, model)
            else -> callOpenRouter(prompt, apiKey, model)
        }
        if (result != null && result.size == texts.size) return@withContext result
        if (result != null && result.isNotEmpty()) {
            // Pad or trim to match original size to keep pairing stable
            return@withContext when {
                result.size < texts.size -> result + texts.drop(result.size).map { it.trim() }
                else -> result.take(texts.size)
            }
        }
        if (prefs.offlineFallback().get()) mlKitFallback(texts, targetLang) else texts.map { it.trim() }
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
            // Use short timeout for translation (avoid hanging reader)
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
            // Normalize model name for Gemini API: strip provider prefix and version tags
            // Examples: "google/gemma-2-9b-it:free" -> gemini-1.5-flash (fallback), "gemini-1.5-pro" -> gemini-1.5-pro
            val modelName = raw.substringAfterLast("/").substringBefore(":").trim()
            val geminiModel = when {
                modelName.isBlank() -> "gemini-1.5-flash"
                modelName.contains("gemma", ignoreCase = true) -> "gemini-1.5-flash"
                modelName.contains("gemini", ignoreCase = true) -> modelName
                else -> modelName // Allow custom Gemini models like gemini-2.0-flash
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
        // Primary: lines prefixed with "- "
        val dashLines = content.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }.filter { it.isNotBlank() }
        if (dashLines.isNotEmpty()) return dashLines
        // Fallback: numbered lines "1. " or "1) "
        val numbered = content.lines().map { it.trim() }.mapNotNull { line ->
            Regex("""^\d+[.)]\s*(.+)""").find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        }
        if (numbered.isNotEmpty()) return numbered
        // Fallback: split on newlines, filter blanks
        val split = content.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (split.isNotEmpty()) return split
        return null
    }

    private fun parseLinesFallback(jsonStr: String): List<String>? {
        return try {
            // Last resort: try to extract any "- " lines from raw json
            val lines = jsonStr.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }.filter { it.isNotBlank() }
            if (lines.isNotEmpty()) return lines
            // Try content regex
            val regex = Regex("\"content\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
            val match = regex.find(jsonStr)?.groupValues?.getOrNull(1) ?: return null
            // Unescape JSON string
            val unescaped = match.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
            parseLinesFromContent(unescaped)
        } catch (_: Exception) {
            null
        }
    }

    // Kept for backwards compat/testing; delegates to new parsers
    private fun parseLines(jsonStr: String): List<String>? = parseOpenRouterResponse(jsonStr) ?: parseGeminiResponse(jsonStr) ?: parseLinesFallback(jsonStr)

    private suspend fun mlKitFallback(texts: List<String>, targetLang: String): List<String> {
        // Stub ML Kit offline fallback - return original with simple trim; respects offlineFallback pref already checked
        return texts.map { it.trim() }
    }

    fun bitmapToWebP(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
        return out.toByteArray()
    }
}
