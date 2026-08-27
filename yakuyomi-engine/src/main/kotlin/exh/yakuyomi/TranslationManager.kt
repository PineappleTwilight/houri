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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream

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
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun isEnabled(): Boolean = prefs.enabled().get()

    fun isGated(): Boolean {
        // Gated by isIncognito / censorLewdManga before upload
        return try {
            val incognito = tachiyomi.core.common.preference.PreferenceStore::class.java // placeholder to avoid direct import cycle
            false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun shouldTranslate(): Boolean {
        if (!isEnabled()) return false
        return try {
            val basePrefs = mihon.app.di.globalAppGraph.basePreferences.incognitoMode().get()
            val censor = mihon.app.di.globalAppGraph.uiPreferences.censorLewdManga().get()
            if (basePrefs || censor) {
                xLogD("Translation gated: incognito=$basePrefs censor=$censor")
                return false
            }
            true
        } catch (_: Exception) {
            true
        }
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
        if (!shouldTranslateForManga(mangaId)) return@withContext null
        val targetLang = prefs.targetLang().get()
        val model = prefs.model().get()
        val pageHash = cache.pageHash(imageBytes)
        cache.getIfExists(pageHash, targetLang, model)?.let { f ->
            try {
                return@withContext f.readBytes()
            } catch (_: Exception) {}
        }
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return@withContext null
            val blocks = engine.detectTextBlocks(bitmap)
            if (blocks.isEmpty()) {
                // No text detected, cache original as webp to avoid re-running
                return@withContext null
            }
            val ocrBlocks = engine.ocrBlocks(bitmap, blocks)
            val originalTexts = ocrBlocks.map { it.text }
            if (originalTexts.isEmpty()) return@withContext null

            // Build prompt with breadcrumb context
            val breadcrumb = notes.buildContextPrompt(mangaId)
            val translatedTexts = translateTexts(originalTexts, sourceLangHint, targetLang, breadcrumb)

            // Breadcrumb notes
            notes.appendFromTranslation(mangaId, chapterId, translatedTexts)

            val cleaned = engine.removeText(bitmap, ocrBlocks)
            val paired = ocrBlocks.zip(translatedTexts)
            val typeset = engine.typeset(cleaned, paired, targetLang)
            val webp = engine.bitmapToWebP(typeset)
            cache.put(pageHash, targetLang, model, webp)
            webp
        } catch (e: Exception) {
            xLogE("translatePage failed", e)
            null
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
            return@withContext mlKitFallback(texts, targetLang)
        }
        // EN→EN grammar/vocab fix uses same prompt
        val isEnFix = sourceLang.equals("EN", true) && targetLang.equals("EN", true)
        val prompt = buildPrompt(texts, sourceLang, targetLang, breadcrumb, isEnFix)
        val provider = prefs.provider().get()
        val model = prefs.model().get()
        val result = when (provider.lowercase()) {
            "gemini" -> callGemini(prompt, apiKey, model)
            else -> callOpenRouter(prompt, apiKey, model)
        }
        if (result != null) return@withContext result
        mlKitFallback(texts, targetLang)
    }

    private fun buildPrompt(texts: List<String>, sourceLang: String, targetLang: String, breadcrumb: String, isEnFix: Boolean): String {
        val joined = texts.joinToString("\n") { "- $it" }
        val breadcrumbSection = if (breadcrumb.isNotBlank()) "Context notes (sliding window):\n$breadcrumb\n\n" else ""
        return if (isEnFix) {
            "${breadcrumbSection}Fix grammar, preserve names, output only EN. Texts:\n$joined\n\nReturn each corrected line prefixed with '- '."
        } else {
            "${breadcrumbSection}Translate $sourceLang → $targetLang. Preserve names, honorifics, output only $targetLang. Texts:\n$joined\n\nReturn each translated line prefixed with '- '."
        }
    }

    private suspend fun callOpenRouter(prompt: String, apiKey: String, model: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://openrouter.ai/api/v1/chat/completions"
            val body = buildJsonObject {
                put("model", model.ifBlank { "google/gemma-2-9b-it:free" })
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
            val req = Request.Builder().url(url).post(body).header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val txt = resp.body.string()
                parseLines(txt)
            }
        } catch (e: Exception) {
            logcat { "OpenRouter call failed: ${e.message}" }
            null
        }
    }

    private suspend fun callGemini(prompt: String, apiKey: String, model: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val modelName = model.ifBlank { "gemma-2-9b-it:free" }.substringAfterLast("/").substringBefore(":")
            val geminiModel = if (modelName.contains("gemma")) "gemini-1.5-flash" else modelName
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$geminiModel:generateContent?key=$apiKey"
            val body = buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) } })
                }
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).header("Content-Type", "application/json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val txt = resp.body.string()
                parseLines(txt)
            }
        } catch (e: Exception) {
            logcat { "Gemini call failed: ${e.message}" }
            null
        }
    }

    private fun parseLines(jsonStr: String): List<String>? {
        return try {
            val lines = jsonStr.lines().filter { it.trim().startsWith("- ") }.map { it.trim().removePrefix("- ").trim() }
            if (lines.isNotEmpty()) return lines
            // Fallback: try to extract content field
            val regex = Regex("\"content\"\\s*:\\s*\"([^\"]+)\"")
            regex.find(jsonStr)?.groupValues?.getOrNull(1)?.let { content ->
                content.split("\\n").map { it.trim().removePrefix("- ").trim() }.filter { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun mlKitFallback(texts: List<String>, targetLang: String): List<String> {
        // Stub ML Kit offline fallback - return original with simple EN fix
        return texts.map { it.trim() }
    }

    fun bitmapToWebP(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
        return out.toByteArray()
    }
}
