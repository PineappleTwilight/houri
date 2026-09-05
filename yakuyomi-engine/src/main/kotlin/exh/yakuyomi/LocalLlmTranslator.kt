package exh.yakuyomi

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.Translator

/**
 * Breadcrumb-aware translation stage backed by the on-device ("local") LLM provider (MLC-LLM on
 * GPU, ExecuTorch on NPU). Same line-by-line protocol as the cloud [YakuyomiTranslator]; vision
 * models additionally receive the page bitmap as context. A failed local generation throws
 * [TranslationException] so the page is marked FAILED (retryable) instead of SKIPPED; when
 * [offlineFallback] is enabled the original lines are returned instead.
 */
class LocalLlmTranslator(
    private val manager: LocalLlmManager,
    private val sourceLang: String,
    private val targetLang: String,
    private val breadcrumb: String,
    private val mangaContext: String,
    private val pageBitmap: Bitmap?,
    private val offlineFallback: Boolean,
) : Translator {

    override suspend fun translate(queries: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (queries.isEmpty()) return@withContext emptyList()
        val isEnFix = sourceLang.equals("EN", true) && targetLang.equals("EN", true)
        val prompt = buildTranslationPrompt(queries, sourceLang, targetLang, breadcrumb, isEnFix, mangaContext)
        val imageBytes = pageBitmap?.let { bitmapToJpeg(it) }
        val result = try {
            manager.generate(prompt, imageBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (offlineFallback) return@withContext queries.map { it.trim() }
            throw TranslationException("Local LLM translation failed: ${e.message}")
        }
        if (result.isNullOrBlank()) {
            if (offlineFallback) return@withContext queries.map { it.trim() }
            throw TranslationException("Local LLM returned no usable translation (is the model downloaded?)")
        }
        alignTranslationLines(parseTranslationLines(result) ?: result.lines().map { it.trim() }.filter { it.isNotBlank() }, queries)
    }

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}
