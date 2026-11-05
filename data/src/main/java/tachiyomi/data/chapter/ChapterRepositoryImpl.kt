package tachiyomi.data.chapter

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterRepositoryImpl(
    private val handler: DatabaseHandler,
) : ChapterRepository {

    override suspend fun addAll(chapters: List<Chapter>): List<Chapter> {
        return try {
            handler.await(inTransaction = true) {
                // KMK: (manga_id, remote_url) is unique now, so a chapter another sync of the
                // same entry already stored - or a second copy within this very batch - would
                // trip the constraint and fail the whole update. Everything already in the
                // table is collected first and the batch keeps only what is not in it, the
                // same pre-filter Mihon's #4022 put in front of its insert.
                val existing = chapters.map { it.mangaId }
                    .distinct()
                    .flatMap { mangaId ->
                        chapterQueries
                            .getChaptersByMangaId(
                                mangaId = mangaId,
                                applyScanlatorFilter = 0,
                                bookmarkUnmask = Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
                                bookmarkMask = Manga.CHAPTER_SHOW_BOOKMARKED,
                                mapper = ChapterMapper::mapChapter,
                            )
                            .executeAsList()
                            .map { mangaId to it.url }
                    }
                    .toMutableSet()
                chapters.filter { existing.add(it.mangaId to it.url) }.map { chapter ->
                    chapterQueries.insert(
                        mangaId = chapter.mangaId,
                        url = chapter.url,
                        name = chapter.name,
                        scanlator = chapter.scanlator,
                        read = chapter.read,
                        bookmark = chapter.bookmark,
                        // KMK -->
                        bookmarkColor = chapter.bookmarkColor.value.toLong(),
                        // KMK <--
                        lastPageRead = chapter.lastPageRead,
                        chapterNumber = chapter.chapterNumber,
                        sourceOrder = chapter.sourceOrder,
                        dateFetch = chapter.dateFetch,
                        dateUpload = chapter.dateUpload,
                        version = chapter.version,
                        // SY -->
                        lastModifiedAt = 0,
                        isSyncing = 0,
                        // SY <--
                        memo = chapter.memo,
                    )
                    val lastInsertId = chapterQueries.selectLastInsertedRowId().executeAsOne()
                    chapter.copy(id = lastInsertId)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    override suspend fun update(chapterUpdate: ChapterUpdate) {
        partialUpdate(chapterUpdate)
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
        partialUpdate(*chapterUpdates.toTypedArray())
    }

    private suspend fun partialUpdate(vararg chapterUpdates: ChapterUpdate) {
        handler.await(inTransaction = true) {
            chapterUpdates.forEach { chapterUpdate ->
                chapterQueries.update(
                    mangaId = chapterUpdate.mangaId,
                    url = chapterUpdate.url,
                    name = chapterUpdate.name,
                    scanlator = chapterUpdate.scanlator,
                    read = chapterUpdate.read,
                    bookmark = chapterUpdate.bookmark,
                    lastPageRead = chapterUpdate.lastPageRead,
                    chapterNumber = chapterUpdate.chapterNumber,
                    sourceOrder = chapterUpdate.sourceOrder,
                    dateFetch = chapterUpdate.dateFetch,
                    dateUpload = chapterUpdate.dateUpload,
                    chapterId = chapterUpdate.id,
                    version = chapterUpdate.version,
                    // SY -->
                    lastModifiedAt = null,
                    isSyncing = null,
                    // SY <--
                    memo = chapterUpdate.memo?.let(MemoColumnAdapter::encode),
                    // KMK -->
                    bookmarkColor = chapterUpdate.bookmarkColor?.value?.toLong(),
                    // KMK <--
                )
            }
        }
    }

    override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
        try {
            handler.await { chapterQueries.removeChaptersWithIds(chapterIds) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun getChapterByMangaId(mangaId: Long, applyFilter: Boolean): List<Chapter> {
        return handler.awaitList {
            chapterQueries.getChaptersByMangaId(
                mangaId,
                applyFilter.toLong(),
                // KMK -->
                Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
                Manga.CHAPTER_SHOW_BOOKMARKED,
                // KMK <--
                ChapterMapper::mapChapter,
            )
        }
    }

    override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> {
        return handler.awaitList {
            chapterQueries.getScanlatorsByMangaId(mangaId) { it.orEmpty() }
        }
    }

    override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> {
        return handler.subscribeToList {
            chapterQueries.getScanlatorsByMangaId(mangaId) { it.orEmpty() }
        }
    }

    override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> {
        return handler.awaitList {
            chapterQueries.getBookmarkedChaptersByMangaId(
                mangaId,
                ChapterMapper::mapChapter,
            )
        }
    }

    override suspend fun getChapterById(id: Long): Chapter? {
        return handler.awaitOneOrNull { chapterQueries.getChapterById(id, ChapterMapper::mapChapter) }
    }

    override suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyFilter: Boolean): Flow<List<Chapter>> {
        return handler.subscribeToList {
            chapterQueries.getChaptersByMangaId(
                mangaId,
                applyFilter.toLong(),
                // KMK -->
                Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
                Manga.CHAPTER_SHOW_BOOKMARKED,
                // KMK <--
                ChapterMapper::mapChapter,
            )
        }
    }

    override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? {
        return handler.awaitOneOrNull {
            chapterQueries.getChapterByUrlAndMangaId(
                mangaId = mangaId,
                remoteUrl = url,
                mapper = ChapterMapper::mapChapter,
            )
        }
    }

    // SY -->
    override suspend fun getChapterByUrl(url: String): List<Chapter> {
        return handler.awaitList { chapterQueries.getChapterByUrl(url, ChapterMapper::mapChapter) }
    }

    override suspend fun getMergedChapterByMangaId(mangaId: Long, applyFilter: Boolean): List<Chapter> {
        return handler.awaitList {
            chapterQueries.getMergedChaptersByMangaId(
                mangaId,
                applyFilter.toLong(),
                // KMK -->
                Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
                Manga.CHAPTER_SHOW_BOOKMARKED,
                // KMK <--
                ChapterMapper::mapChapter,
            )
        }
    }

    override suspend fun getMergedChapterByMangaIdAsFlow(
        mangaId: Long,
        applyFilter: Boolean,
    ): Flow<List<Chapter>> {
        return handler.subscribeToList {
            chapterQueries.getMergedChaptersByMangaId(
                mangaId,
                applyFilter.toLong(),
                // KMK -->
                Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
                Manga.CHAPTER_SHOW_BOOKMARKED,
                // KMK <--
                ChapterMapper::mapChapter,
            )
        }
    }

    override suspend fun getScanlatorsByMergeId(mangaId: Long): List<String> {
        return handler.awaitList {
            chapterQueries.getScanlatorsByMergeId(mangaId) { it.orEmpty() }
        }
    }

    override fun getScanlatorsByMergeIdAsFlow(mangaId: Long): Flow<List<String>> {
        return handler.subscribeToList {
            chapterQueries.getScanlatorsByMergeId(mangaId) { it.orEmpty() }
        }
    }
    // SY <--
}
