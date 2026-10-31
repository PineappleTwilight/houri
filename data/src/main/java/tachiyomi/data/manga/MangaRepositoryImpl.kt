package tachiyomi.data.manga

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import logcat.LogPriority
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import java.time.LocalDate
import java.time.ZoneId

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MangaRepositoryImpl(
    private val handler: DatabaseHandler,
    // KMK --> libraryView carries its chapter counts on both bases - one per chapter number and
    // one per upload - and the queries pick between them with this. The switch is the same one the
    // details list, the reader and the library's continue button read, so with the merge off the
    // badge counts the uploads the reader can actually see rather than the numbers behind them.
    private val libraryPreferences: LibraryPreferences,
) : MangaRepository {

    override suspend fun getMangaById(id: Long): Manga {
        return handler.awaitOne { mangaQueries.getMangaById(id, MangaMapper::mapManga) }
    }

    override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> {
        return handler.subscribeToOne { mangaQueries.getMangaById(id, MangaMapper::mapManga) }
    }

    override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
        return handler.awaitOneOrNull {
            mangaQueries.getMangaByUrlAndSource(
                sourceId = sourceId,
                remoteUrl = url,
                mapper = MangaMapper::mapManga,
            )
        }
    }

    override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> {
        return handler.subscribeToOneOrNull {
            mangaQueries.getMangaByUrlAndSource(
                sourceId = sourceId,
                remoteUrl = url,
                mapper = MangaMapper::mapManga,
            )
        }
    }

    override suspend fun getFavorites(): List<Manga> {
        return handler.awaitList { mangaQueries.getFavorites(MangaMapper::mapManga) }
    }

    override suspend fun getReadMangaNotInLibrary(): List<Manga> {
        return handler.awaitList { mangaQueries.getReadMangaNotInLibrary(MangaMapper::mapManga) }
    }

    override suspend fun getLibraryManga(): List<LibraryManga> {
        return try {
            handler.awaitList {
                libraryViewQueries.library(
                    libraryPreferences.smartScanlatorMerge().get().toLong(),
                    MangaMapper::mapLibraryManga,
                )
            }
        } catch (e: OutOfMemoryError) {
            // KMK --> Degrade instead of blanking the library. The view's aggregate ran out of
            // memory before it could produce counts, but the favorites rows themselves are far
            // smaller than the aggregate it was building over - a library without per-series
            // counts is recoverable; an empty library screen on a 10k-entry library looks like
            // the user's data is gone. Entries land in the uncategorized group so the default
            // grouping still shows them; if even the plain favorites pass throws, give up and
            // keep the old empty guard.
            logcat(LogPriority.ERROR, e) { "OOM loading library aggregates, degrading to favorites without chapter counts" }
            runCatching { getFavorites().toLibraryMangaWithoutCounts() }
                .onFailure { error ->
                    logcat(LogPriority.ERROR, error) { "OOM fallback failed, returning empty guard" }
                }
                .getOrDefault(emptyList())
        }
    }

    // KMK -->
    private fun List<Manga>.toLibraryMangaWithoutCounts(): List<LibraryManga> = map { manga ->
        LibraryManga(
            manga = manga,
            categories = listOf(Category.UNCATEGORIZED_ID),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            bookmarkReadCount = 0,
            chapterFlags = manga.chapterFlags,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )
    }
    // KMK <--

    // KMK --> Counted in SQL rather than by measuring the list [getLibraryManga] returns.
    override suspend fun getLibraryCount(): Long {
        return handler.awaitOne { libraryViewQueries.libraryCount() }
    }

    override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> {
        // KMK --> Re-subscribed when the switch flips: the counts it feeds are only correct for one
        // basis or the other, and a query already in flight cannot change its parameters. Toggling
        // it re-runs the library query once, which is the same cost as the next chapter change.
        return libraryPreferences.smartScanlatorMerge().changes().flatMapLatest { merge ->
            handler.subscribeToList {
                libraryViewQueries.library(merge.toLong(), MangaMapper::mapLibraryManga)
            }
        }
    }

    override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> {
        return handler.subscribeToList { mangaQueries.getFavoriteBySourceId(sourceId, MangaMapper::mapManga) }
    }

    // KMK --> Light favorites projections for callers that don't need the libraryView aggregate.
    // See MangaRepository for what each answers and why these exist.
    override suspend fun getFavoriteTitles(): List<String> {
        return handler.awaitList {
            mangaQueries.getFavoriteTitles()
        }
    }

    override suspend fun getMangaIdByTitle(title: String): Long? {
        return handler.awaitOneOrNull {
            mangaQueries.getFavoriteMangaIdByTitle(title = title)
        }
    }

    override suspend fun getFavoriteCovers(): List<Pair<Long, String?>> {
        return handler.awaitList {
            mangaQueries.getFavoriteCovers { mangaId, thumbnailUrl -> mangaId to thumbnailUrl }
        }
    }

    override suspend fun getFavoriteCategoryCounts(): Map<Long, Long> {
        return handler.awaitList {
            mangaQueries.getFavoriteCategoryCounts { categoryId, mangaCount -> categoryId to mangaCount }
        }.toMap()
    }

    override fun getFavoriteCategoryCountsAsFlow(): Flow<Map<Long, Long>> {
        return handler.subscribeToList {
            mangaQueries.getFavoriteCategoryCounts { categoryId, mangaCount -> categoryId to mangaCount }
        }.map { it.toMap() }
    }

    override suspend fun getFavoriteCategoryPairs(): Map<Long, Set<Long>> {
        return handler.awaitList {
            mangaQueries.getFavoriteCategoryPairs { mangaId, categoryId -> mangaId to categoryId }
        }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .mapValues { it.value.toSet() }
    }
    // KMK <--

    override suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount> {
        return handler.awaitList {
            mangaQueries.getDuplicateLibraryManga(id, title, MangaMapper::mapMangaWithChapterCount)
        }
    }

    override suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>> {
        val epochMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
        return handler.subscribeToList {
            mangaQueries.getUpcomingManga(epochMillis, statuses, MangaMapper::mapManga)
        }
    }

    override suspend fun resetViewerFlags(): Boolean {
        return try {
            handler.await { mangaQueries.resetViewerFlags() }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    // KMK -->
    override suspend fun updateLibraryChapterFlags(chapterFlags: Long): Boolean {
        return try {
            handler.await(inTransaction = true) {
                mangaQueries.updateLibraryChapterFlags(chapterFlags)
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }
    // KMK <--

    override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            manga_categoryQueries.deleteMangaCategoryByMangaId(mangaId)
            categoryIds.map { categoryId ->
                manga_categoryQueries.insert(mangaId = mangaId, categoryId = categoryId)
            }
        }
    }

    override suspend fun update(update: MangaUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return try {
            partialUpdate(*mangaUpdates.toTypedArray())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun insertNetworkManga(
        manga: List<Manga>,
        // KMK -->
        updateInfo: Boolean,
        // KMK <--
    ): List<Manga> {
        return handler.await(inTransaction = true) {
            manga.map {
                mangaQueries.insertNetworkManga(
                    source = it.source,
                    url = it.url,
                    // SY -->
                    title = it.ogTitle,
                    artist = it.ogArtist,
                    author = it.ogAuthor,
                    thumbnailUrl = it.ogThumbnailUrl,
                    description = it.ogDescription,
                    genre = it.ogGenre,
                    status = it.ogStatus,
                    // SY <--
                    // favorite and dateAdded became one column upstream: the timestamp only
                    // exists while the entry is in the library
                    favoriteAt = if (it.favorite) it.dateAdded else null,
                    lastUpdate = it.lastUpdate,
                    nextUpdate = it.nextUpdate,
                    calculateInterval = it.fetchInterval.toLong(),
                    initialized = it.initialized,
                    viewerFlags = it.viewerFlags,
                    chapterFlags = it.chapterFlags,
                    coverLastModified = it.coverLastModified,
                    updateStrategy = it.updateStrategy,
                    version = it.version,
                    memo = it.memo,
                    // SY -->
                    updateTitle = it.ogTitle.isNotBlank(),
                    updateCover = !it.ogThumbnailUrl.isNullOrBlank(),
                    // SY <--
                    updateDetails = it.initialized,
                    // KMK -->
                    updateInfo = updateInfo,
                    // KMK <--
                    // KMK -->
                    scanlatorPriority = it.scanlatorPriority,
                    blacklistedChapters = it.blacklistedChapters,
                    rereadCount = it.rereadCount.toLong(),
                    rereading = it.rereading,
                    rereadStartedAt = it.rereadStartedAt,
                    scanlatorRangeRules = it.scanlatorRangeRules,
                    isLightNovel = it.isLightNovel,
                    // KMK <--
                    mapper = MangaMapper::mapManga,
                )
                    .executeAsOne()
            }
        }
    }

    private suspend fun partialUpdate(vararg mangaUpdates: MangaUpdate) {
        handler.await(inTransaction = true) {
            mangaUpdates.forEach { value ->
                mangaQueries.update(
                    source = value.source,
                    url = value.url,
                    title = value.title,
                    author = value.author,
                    artist = value.artist,
                    description = value.description,
                    genre = value.genre?.let(StringListColumnAdapter::encode),
                    status = value.status,
                    thumbnailUrl = value.thumbnailUrl,
                    // NULL is a real value for user_favorite_at (it means "not in the
                    // library"), so the query takes the favorite flag and the date separately
                    // and only writes the timestamp when the flag is being set. :favorite is
                    // only read when :favoriteSet is true, so a null flag folds into false.
                    favoriteSet = value.favorite != null,
                    favorite = value.favorite == true,
                    favoriteAt = value.dateAdded,
                    lastUpdate = value.lastUpdate,
                    nextUpdate = value.nextUpdate,
                    initialized = value.initialized,
                    viewer = value.viewerFlags,
                    chapterFlags = value.chapterFlags,
                    coverLastModified = value.coverLastModified,
                    updateStrategy = value.updateStrategy?.let(UpdateStrategyColumnAdapter::encode),
                    calculateInterval = value.fetchInterval?.toLong(),
                    version = value.version,
                    isSyncing = 0,
                    notes = value.notes,
                    memo = value.memo?.let(MemoColumnAdapter::encode),
                    // KMK -->
                    scanlatorPriority = value.scanlatorPriority?.let(StringListColumnAdapter::encode),
                    blacklistedChapters = value.blacklistedChapters?.let(StringListColumnAdapter::encode),
                    rereadCount = value.rereadCount?.toLong(),
                    rereading = value.rereading,
                    rereadStartedAt = value.rereadStartedAt,
                    scanlatorRangeRules = value.scanlatorRangeRules?.let(StringListColumnAdapter::encode),
                    isLightNovel = value.isLightNovel,
                    // KMK <--
                    mangaId = value.id,
                )
            }
        }
    }

    // SY -->
    override suspend fun getMangaBySourceId(sourceId: Long): List<Manga> {
        return handler.awaitList { mangaQueries.getBySource(sourceId, MangaMapper::mapManga) }
    }

    override suspend fun getAll(): List<Manga> {
        return handler.awaitList { mangaQueries.getAll(MangaMapper::mapManga) }
    }

    override suspend fun deleteManga(mangaId: Long) {
        handler.await { mangaQueries.deleteById(mangaId) }
    }

    override suspend fun deleteNonLibraryManga(sourceIds: List<Long>, keepReadManga: Boolean) {
        handler.await {
            mangaQueries.deleteNonLibraryManga(sourceIds, keepReadManga.toLong())
        }
    }

    override suspend fun getReadMangaNotInLibraryView(): List<LibraryManga> {
        return handler.awaitList {
            libraryViewQueries.readMangaNonLibrary(
                libraryPreferences.smartScanlatorMerge().get().toLong(),
                MangaMapper::mapLibraryManga,
            )
        }
    }
    // SY <--
}
