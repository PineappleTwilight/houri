package exh.yakuyomi

import android.graphics.Bitmap
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * No-op stub of [TranslationManager] for the no-MTL APK variant. Every method that app code
 * calls exists with the same signature; translation is permanently disabled (the global
 * preference also defaults off and the Settings entry is disabled on this variant).
 */
@SingleIn(AppScope::class)
@Inject
class TranslationManager {

    fun isEnabled(): Boolean = false

    fun isGated(): Boolean = false

    suspend fun shouldTranslate(): Boolean = false

    suspend fun shouldTranslateForManga(mangaId: Long): Boolean = false

    fun isPerMangaEnabled(mangaId: Long): Boolean = false

    fun setPerMangaEnabled(mangaId: Long, enabled: Boolean) = Unit

    fun setChapterTotalPages(mangaId: Long, chapterId: Long, totalPages: Int) = Unit

    fun friendlyError(raw: String?): String = "AI translation is not available in this build"

    suspend fun getTranslatedBytes(
        mangaId: Long,
        chapterId: Long,
        imageBytes: ByteArray,
        pageIndex: Int,
    ): ByteArray? = null

    suspend fun translatePage(
        mangaId: Long,
        chapterId: Long,
        imageBytes: ByteArray,
        pageIndex: Int,
        sourceLangHint: String = "JA",
    ): ByteArray? = null

    suspend fun translateMangaInfo(
        mangaId: Long,
        title: String,
        description: String?,
        sourceLangHint: String = "JA",
    ): MangaInfoTranslation? = null

    fun bitmapToWebP(bitmap: Bitmap): ByteArray = ByteArray(0)
}
