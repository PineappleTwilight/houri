package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount

interface MangaRepository {

    suspend fun getMangaById(id: Long): Manga

    suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga>

    suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga?

    fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?>

    suspend fun getFavorites(): List<Manga>

    suspend fun getReadMangaNotInLibrary(): List<Manga>

    suspend fun getLibraryManga(): List<LibraryManga>

    /**
     * How many series are in the library, without building them.
     *
     * [getLibraryManga] runs the full `libraryView` query - chapter counts, category aggregation
     * and all - and allocates a [LibraryManga] per series. That is the right call to *read* the
     * library and the wrong one to answer "how many are there", which is what the restore asked
     * after writing thousands of entries.
     */
    suspend fun getLibraryCount(): Long

    fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>>

    fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>>

    suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount>

    // KMK -->
    /**
     * Cheap projections of the favorites table, for callers that don't need the libraryView
     * aggregate. [getLibraryManga] re-runs the whole chapter-counting view per query, which is
     * the right price to *read* the library and far too high a price to answer "which titles /
     * covers / category counts do the favorites have".
     *
     * [getFavoriteTitles] and [getFavoriteCovers] return the effective (custom-edited) values,
     * matching what `Manga.title` / `Manga.thumbnailUrl` would show.
     */
    suspend fun getFavoriteTitles(): List<String>

    /** The id of the library entry whose effective title matches [title], case-insensitively. */
    suspend fun getMangaIdByTitle(title: String): Long?

    suspend fun getFavoriteCovers(): List<Pair<Long, String?>>

    suspend fun getFavoriteCategoryCounts(): Map<Long, Long>

    fun getFavoriteCategoryCountsAsFlow(): Flow<Map<Long, Long>>

    /** Each library entry's category memberships as mangaId -> category ids, for callers
     * that scope a whole-library operation (the CSV export) by category without paying the
     * libraryView aggregate. Entries with no memberships are absent from the map.
     */
    suspend fun getFavoriteCategoryPairs(): Map<Long, Set<Long>>
    // KMK <--

    suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>>

    suspend fun resetViewerFlags(): Boolean

    suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>)

    suspend fun update(update: MangaUpdate): Boolean

    suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean

    // KMK -->
    /** Overwrites [chapterFlags] on every library entry in one statement. */
    suspend fun updateLibraryChapterFlags(chapterFlags: Long): Boolean
    // KMK <--

    suspend fun insertNetworkManga(
        manga: List<Manga>,
        // KMK -->
        updateInfo: Boolean = true,
        // KMK <--
    ): List<Manga>

    // SY -->
    suspend fun getMangaBySourceId(sourceId: Long): List<Manga>

    suspend fun getAll(): List<Manga>

    suspend fun deleteManga(mangaId: Long)

    suspend fun deleteNonLibraryManga(sourceIds: List<Long>, keepReadManga: Boolean)

    // KMK -->
    suspend fun getNonLibraryManga(): List<Manga>
    // KMK <--

    suspend fun getReadMangaNotInLibraryView(): List<LibraryManga>
    // SY <--
}
