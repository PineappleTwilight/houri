package tachiyomi.data.history

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.model.ReadDurationByManga
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HistoryRepositoryImpl(
    private val handler: DatabaseHandler,
    private val sourceManager: SourceManager,
) : HistoryRepository {

    override fun getHistory(
        query: String,
        // KMK -->
        unfinishedManga: Boolean?,
        unfinishedChapter: Boolean?,
        nonLibraryEntries: Boolean?,
        // KMK <--
    ): Flow<List<HistoryWithRelations>> {
        return handler.subscribeToList {
            historyViewQueries.history(
                // KMK -->
                Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
                Manga.CHAPTER_SHOW_BOOKMARKED,
                unfinishedManga?.toLong(),
                unfinishedChapter,
                nonLibraryEntries,
                // KMK <--
                query,
                HistoryMapper::mapHistoryWithRelations,
            )
        }
    }

    override suspend fun getLastHistory(): HistoryWithRelations? {
        return handler.awaitOneOrNull {
            historyViewQueries.getLatestHistory(
                Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
                Manga.CHAPTER_SHOW_BOOKMARKED,
                HistoryMapper::mapHistoryWithRelations,
            )
        }
    }

    override suspend fun getTotalReadDuration(): Long {
        return handler.awaitOne { historyQueries.getReadDuration() }
    }

    // KMK -->
    override suspend fun getTotalReadDurationByManga(): List<ReadDurationByManga> {
        val raw = handler.awaitList {
            historyQueries.getReadDurationByManga { manga_id, title, total_time_read, source_id, is_favorite, thumbnail_url, cover_last_modified ->
                ReadDurationByManga(
                    mangaId = manga_id,
                    title = title,
                    totalTimeRead = total_time_read,
                    cover = MangaCover(
                        mangaId = manga_id,
                        sourceId = source_id,
                        isMangaFavorite = is_favorite,
                        ogUrl = thumbnail_url,
                        lastModified = cover_last_modified,
                    ),
                )
            }
        }
        // Merge duplicate entries (e.g. after a source migration) by normalized title: pick the
        // entry with the most complete cover as representative and sum all read times.
        return raw
            .groupBy { it.title.trim().lowercase() }
            .map { (_, group) ->
                val representative = group.maxWithOrNull(
                    compareBy(
                        { sourceManager.get(it.cover.sourceId) != null },
                        { it.cover.isMangaFavorite },
                        { it.cover.url != null },
                        { it.totalTimeRead },
                    ),
                )!!
                representative.copy(totalTimeRead = group.sumOf { it.totalTimeRead })
            }
            .sortedByDescending { it.totalTimeRead }
            .take(30)
    }

    override suspend fun getReadDurationForManga(mangaId: Long): Long {
        return handler.awaitOne {
            historyQueries.getReadDurationForManga(mangaId)
        }
    }

    override suspend fun getReadDurationForMangaByTitle(title: String): Long {
        return handler.awaitOne {
            historyQueries.getReadDurationForMangaByTitle(title)
        }
    }
    // KMK <--

    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> {
        return handler.awaitList { historyQueries.getHistoryByMangaId(mangaId, HistoryMapper::mapHistory) }
    }

    // KMK -->
    override suspend fun resetHistory(historyIds: List<Long>) {
        try {
            handler.await { historyQueries.resetHistoryByIds(historyIds) }
            // KMK <--
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    // KMK -->
    override suspend fun resetHistoryByMangaIds(mangaIds: List<Long>) {
        try {
            handler.await { historyQueries.resetHistoryByMangaIds(mangaIds) }
            // KMK <--
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAllHistory(): Boolean {
        return try {
            handler.await { historyQueries.removeAllHistory() }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
    }

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) {
        try {
            handler.await {
                historyQueries.upsert(
                    historyUpdate.chapterId,
                    historyUpdate.readAt,
                    historyUpdate.sessionReadDuration,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    // SY -->
    override suspend fun upsertHistory(historyUpdates: List<HistoryUpdate>) {
        try {
            handler.await(true) {
                historyUpdates.forEach { historyUpdate ->
                    historyQueries.upsert(
                        historyUpdate.chapterId,
                        historyUpdate.readAt,
                        historyUpdate.sessionReadDuration,
                    )
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun removeResettedHistory() {
        handler.await { historyQueries.removeResettedHistory() }
    }
    // SY <--
}
