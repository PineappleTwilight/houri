package tachiyomi.domain.updates.model

import tachiyomi.domain.manga.model.CustomMangaInfoLookup
import tachiyomi.domain.manga.model.MangaCover

data class UpdatesWithRelations(
    val mangaId: Long,
    // SY -->
    val ogMangaTitle: String,
    // SY <--
    val chapterId: Long,
    val chapterName: String,
    val scanlator: String?,
    val chapterUrl: String,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val sourceId: Long,
    val dateFetch: Long,
    val coverData: MangaCover,
) {
    // SY -->
    val mangaTitle: String = CustomMangaInfoLookup.resolve?.invoke(mangaId)?.title ?: ogMangaTitle
    // SY <--
}
