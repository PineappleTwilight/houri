package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.model.ReadDurationByManga

interface HistoryRepository {

    fun getHistory(
        query: String,
        // KMK -->
        unfinishedManga: Boolean?,
        unfinishedChapter: Boolean?,
        nonLibraryEntries: Boolean?,
        // KMK <--
    ): Flow<List<HistoryWithRelations>>

    suspend fun getLastHistory(): HistoryWithRelations?

    suspend fun getTotalReadDuration(): Long

    // KMK -->
    /** Total read duration per manga (title + cover), ordered by duration descending. */
    suspend fun getTotalReadDurationByManga(): List<ReadDurationByManga>

    /** Total read duration for a single manga by its id. */
    suspend fun getReadDurationForManga(mangaId: Long): Long

    /** Total read duration for a single manga by its (trimmed, case-insensitive) title. */
    suspend fun getReadDurationForMangaByTitle(title: String): Long
    // KMK <--

    suspend fun getHistoryByMangaId(mangaId: Long): List<History>

    // KMK -->
    suspend fun resetHistory(historyIds: List<Long>)

    suspend fun resetHistoryByMangaIds(mangaIds: List<Long>)
    // KMK <--

    suspend fun deleteAllHistory(): Boolean

    suspend fun removeResettedHistory()

    suspend fun upsertHistory(historyUpdate: HistoryUpdate)

    // SY -->
    suspend fun upsertHistory(historyUpdates: List<HistoryUpdate>)
    // SY <--
}
