package exh.yakuyomi

import android.graphics.Bitmap
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.log.xLogD
import exh.log.xLogE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.io.ByteArrayOutputStream

@SingleIn(AppScope::class)
@Inject
class TranslationManager(
    private val prefs: TranslationPreferences,
    private val cache: TranslationCache,
    private val pageStore: TranslatedPageStore,
    private val notes: BreadcrumbNotes,
    private val status: TranslationStatus,
    private val mangaTranslator: MangaTranslatorService,
    private val preferenceStore: PreferenceStore,
    private val perMangaStore: TranslateMangaStore,
) {
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isEnabled(): Boolean = prefs.enabled().get()

    private val incognitoPref by lazy { preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false) }
    private val censorPref by lazy { preferenceStore.getBoolean("pref_censor_lewd_manga", false) }

    fun isGated(): Boolean = incognitoPref.get() || censorPref.get()

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

    fun setChapterTotalPages(mangaId: Long, chapterId: Long, totalPages: Int) =
        status.setTotalPages(mangaId, chapterId, totalPages)

    fun friendlyError(raw: String?): String = TranslationErrorMapper.toUserMessage(raw)

    suspend fun getTranslatedBytes(
        mangaId: Long,
        chapterId: Long,
        imageBytes: ByteArray,
        pageIndex: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!prefs.enabled().get() || isGated() || !perMangaStore.isEnabled(mangaId)) return@withContext null
        val targetLang = prefs.targetLang().get().ifBlank { "en" }
        val model = effectiveModel()
        if (prefs.saveTranslatedPages().get() || prefs.mangaTranslatorCachePermanent().get()) {
            pageStore.loadIfExists(mangaId, chapterId, pageIndex)?.let { if (it.isNotEmpty()) return@withContext it }
        }
        if (prefs.cacheEnabled().get()) {
            val pageHash = cache.pageHash(imageBytes)
            cache.getIfExists(pageHash, targetLang, model)?.let { f ->
                try {
                    val bytes = f.readBytes()
                    if (bytes.isNotEmpty()) return@withContext bytes
                } catch (_: Exception) {}
            }
        }
        null
    }

    suspend fun translatePage(
        mangaId: Long,
        chapterId: Long,
        imageBytes: ByteArray,
        pageIndex: Int,
        sourceLangHint: String = "JA",
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!prefs.enabled().get() || isGated() || !perMangaStore.isEnabled(mangaId)) return@withContext null
        val targetLang = prefs.targetLang().get().ifBlank { "en" }
        val model = effectiveModel()
        val cacheEnabled = prefs.cacheEnabled().get()
        if (prefs.saveTranslatedPages().get() || prefs.mangaTranslatorCachePermanent().get()) {
            pageStore.loadIfExists(mangaId, chapterId, pageIndex)?.let { bytes ->
                if (bytes.isNotEmpty()) {
                    status.pageCached(mangaId, chapterId, pageIndex)
                    return@withContext bytes
                }
            }
        }
        val pageHash = cache.pageHash(imageBytes)
        if (cacheEnabled) {
            cache.getIfExists(pageHash, targetLang, model)?.let { f ->
                try {
                    val bytes = f.readBytes()
                    if (bytes.isNotEmpty()) {
                        if (prefs.saveTranslatedPages().get() || prefs.mangaTranslatorCachePermanent().get()) {
                            pageStore.save(mangaId, chapterId, pageIndex, bytes)
                        }
                        status.pageCached(mangaId, chapterId, pageIndex)
                        return@withContext bytes
                    }
                } catch (_: Exception) {}
            }
        }
        if (imageBytes.size > 30 * 1024 * 1024 || imageBytes.size < 1024) {
            status.pageError(mangaId, chapterId, pageIndex, friendlyError("Invalid image size"))
            return@withContext null
        }
        status.pageTranslating(mangaId, chapterId, pageIndex)
        val useMangaTranslator = prefs.mangaTranslatorEnabled().get() || prefs.provider().get().equals("mangatranslator", true)
        if (useMangaTranslator) {
            try {
                val webp = mangaTranslator.translateImageToWebP(imageBytes, targetLang, prefs.effectiveModel().takeIf { it.isNotBlank() })
                if (webp != null && webp.isNotEmpty()) {
                    if (cacheEnabled) {
                        try {
                            cache.put(pageHash, targetLang, model, webp)
                        } catch (_: Exception) {}
                    }
                    try {
                        if (prefs.mangaTranslatorCachePermanent().get() || prefs.saveTranslatedPages().get()) {
                            pageStore.save(mangaId, chapterId, pageIndex, webp)
                        }
                    } catch (_: Exception) {}
                    status.pageDone(mangaId, chapterId, pageIndex)
                    return@withContext webp
                } else {
                    status.pageError(mangaId, chapterId, pageIndex, friendlyError("MangaTranslator returned empty result"))
                    return@withContext null
                }
            } catch (e: TranslationException) {
                xLogE("MangaTranslator failed", e)
                status.pageError(mangaId, chapterId, pageIndex, friendlyError(e.message ?: "MangaTranslator error"))
                return@withContext null
            } catch (e: Exception) {
                xLogE("MangaTranslator failed", e)
                status.pageError(mangaId, chapterId, pageIndex, friendlyError(e.message ?: "MangaTranslator error"))
                return@withContext null
            }
        }
        status.pageError(mangaId, chapterId, pageIndex, friendlyError("On-device translation unavailable in no-MTL build — enable MangaTranslator"))
        null
    }

    suspend fun translateMangaInfo(
        mangaId: Long,
        title: String,
        description: String?,
        sourceLangHint: String = "JA",
    ): MangaInfoTranslation? = null

    fun bitmapToWebP(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
        return out.toByteArray()
    }

    fun cancelChapter(mangaId: Long, chapterId: Long) {
        status.resetChapter(mangaId, chapterId)
    }
    fun pauseChapter(mangaId: Long, chapterId: Long) = Unit
    fun resumeChapter(mangaId: Long, chapterId: Long) = Unit
    fun retryChapter(mangaId: Long, chapterId: Long) {
        val st = status.chapterStatus(mangaId, chapterId) ?: return
        val failedPages = st.pages.filter { it.value.state == TranslationStatus.PageState.ERROR }.keys
        if (failedPages.isEmpty()) return
        status.updateForRetry(mangaId, chapterId, failedPages)
    }
    fun clearAllChapters() = clearAll()
    fun clearAll() = status.clearAll()

    private fun effectiveModel(): String {
        if (prefs.mangaTranslatorEnabled().get() || prefs.provider().get().equals("mangatranslator", true)) return "mangatranslator"
        return prefs.effectiveModel().ifBlank { "google/gemma-2-9b-it:free" }
    }
}
