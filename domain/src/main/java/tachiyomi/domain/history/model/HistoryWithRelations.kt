package tachiyomi.domain.history.model

import tachiyomi.domain.manga.model.CustomMangaInfoLookup
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

data class HistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    // SY -->
    val ogTitle: String,
    // SY <--
    val chapterNumber: Double,
    // KMK -->
    val read: Boolean,
    val lastPageRead: Long,
    val totalCountCalculated: Long,
    val readCountCalculated: Long,
    // KMK <--
    val readAt: Date?,
    val readDuration: Long,
    val coverData: MangaCover,
) {
    // SY -->
    val title: String = CustomMangaInfoLookup.resolve?.invoke(mangaId)?.title ?: ogTitle
    // SY <--

    // KMK -->
    val unreadCount
        get() = totalCountCalculated - readCountCalculated
    // KMK <--
}
