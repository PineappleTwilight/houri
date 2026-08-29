package exh.yakuyomi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.log.xLogD
import exh.log.xLogE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.PageResult
import okhttp3.OkHttpClient
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.io.ByteArrayOutputStream

@SingleIn(AppScope::class)
@Inject
class TranslationManager(
    private val prefs: TranslationPreferences,
    private val cache: TranslationCache,
    private val engine: YakuyomiEngine,
    private val notes: BreadcrumbNotes,
    private val client: OkHttpClient,
    private val perMangaStore: TranslateMangaStore,
    private val preferenceStore: PreferenceStore,
    private val status: TranslationStatus,
) {
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
        status.pageTranslating(mangaId, chapterId, pageIndex)
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                status.pageError(mangaId, chapterId, pageIndex, "Unable to decode image bounds")
                return@withContext null
            }
            val needsSample = bounds.outWidth > 4096 || bounds.outHeight > 4096
            val sampleOpts = if (needsSample) BitmapFactory.Options().apply { inSampleSize = 2 } else null
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, sampleOpts)
            if (bitmap == null) {
                status.pageError(mangaId, chapterId, pageIndex, "Unable to decode image")
                return@withContext null
            }

            val breadcrumb = notes.buildContextPrompt(mangaId)
            val translator = YakuyomiTranslator(
                apiKey = prefs.apiKey().get(),
                sourceLang = sourceLangHint,
                targetLang = targetLang,
                breadcrumb = breadcrumb,
                provider = prefs.provider().get().lowercase(),
                model = model,
                offlineFallback = prefs.offlineFallback().get(),
                client = client,
            )

            when (val result = engine.translatePage(bitmap, translator)) {
                is PageResult.Translated -> {
                    val webp = engine.bitmapToWebP(result.page, quality = 85)
                    if (cacheEnabled) {
                        cache.put(pageHash, targetLang, model, webp)
                    }
                    val translatedTexts = result.analysis?.regions?.map { it.translatedText } ?: emptyList()
                    try {
                        notes.appendFromTranslation(mangaId, chapterId, translatedTexts)
                    } catch (_: Exception) {}
                    status.pageDone(mangaId, chapterId, pageIndex)
                    webp
                }
                is PageResult.Skipped -> {
                    status.pageSkipped(mangaId, chapterId, pageIndex)
                    null
                }
                is PageResult.Failed -> {
                    status.pageError(mangaId, chapterId, pageIndex, result.reason)
                    null
                }
            }
        } catch (e: Exception) {
            xLogE("translatePage failed", e)
            status.pageError(mangaId, chapterId, pageIndex, e.message ?: "Unknown translation error")
            null
        }
    }

    fun bitmapToWebP(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
        return out.toByteArray()
    }
}
