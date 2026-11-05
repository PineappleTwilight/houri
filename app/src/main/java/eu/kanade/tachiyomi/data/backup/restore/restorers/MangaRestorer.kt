package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupFlatMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMergedMangaReference
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import exh.EXHMigrations
import exh.source.isMergedSourceId
import mihon.app.di.globalAppGraph
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.manga.MangaMapper
import tachiyomi.data.manga.MergedMangaMapper
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import java.time.ZonedDateTime
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class MangaRestorer(
    private var isSync: Boolean = false,

    private val handler: DatabaseHandler = globalAppGraph.databaseHandler,
    private val getCategories: GetCategories = globalAppGraph.getCategories,
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = globalAppGraph.getMangaByUrlAndSourceId,
    private val getChaptersByMangaId: GetChaptersByMangaId = globalAppGraph.getChaptersByMangaId,
    private val updateManga: UpdateManga = globalAppGraph.updateManga,
    private val getTracks: GetTracks = globalAppGraph.getTracks,
    private val insertTrack: InsertTrack = globalAppGraph.insertTrack,
    // KMK --> Kept as a val: the restore computes fetch intervals from the backup's own
    // chapter list (see restoreMangaDetails), which needs the interactor, not just its window.
    private val fetchInterval: FetchInterval = globalAppGraph.fetchInterval,
    // KMK <--
    // SY -->
    private val setCustomMangaInfo: SetCustomMangaInfo = globalAppGraph.setCustomMangaInfo,
    private val insertFlatMetadata: InsertFlatMetadata = globalAppGraph.insertFlatMetadata,
    private val getFlatMetadataById: GetFlatMetadataById = globalAppGraph.getFlatMetadataById,
    // SY <--
) {
    private var now = ZonedDateTime.now()
    private var currentFetchWindow = fetchInterval.getWindow(now)

    /**
     * Read once per restore instead of once per manga. Nothing in this class writes
     * categories, so every entry of a restore sees the same list.
     */
    private var restoredCategories: List<Category> = emptyList()

    /**
     * `mergeUrl`/`mangaUrl` of every merged row already in the database, plus every
     * row this restore inserted. Backs the "is this reference already restored?"
     * check, which used to load the whole `merged` table per merged entry and then
     * scan it linearly per reference.
     */
    private val knownMergedReferences = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    private val pendingEdits = ArrayList<CustomMangaInfo>()
    private val pendingEditsLock = Any()

    init {
        now = ZonedDateTime.now()
        currentFetchWindow = fetchInterval.getWindow(now)
    }

    /**
     * Loads the state that is identical for every entry of a restore.
     *
     * Call once before fanning out to [restore]. The restore does not add or remove
     * categories, and merged rows are added to [knownMergedReferences] as they are
     * inserted, so both stay valid for the whole run.
     */
    suspend fun prepare() {
        restoredCategories = getCategories.await()
        knownMergedReferences.clear()
        knownMergedReferences.addAll(
            handler.awaitList { mergedQueries.selectAll(MergedMangaMapper::map) }
                .map { it.mergeUrl to it.mangaUrl },
        )
    }

    /**
     * Publishes the manga info edits buffered by [restore] in a single batch.
     *
     * One [tachiyomi.domain.manga.interactor.SetCustomMangaInfo] call per entry
     * copied the whole cache and queued its own transaction, which is quadratic
     * once a backup carries an edit for most of the library.
     */
    fun flushPendingEdits() {
        val batch = synchronized(pendingEditsLock) {
            if (pendingEdits.isEmpty()) return
            pendingEdits.toList().also { pendingEdits.clear() }
        }
        setCustomMangaInfo.setAll(batch)
    }

    /**
     * Throws away the edits buffered since the last flush.
     *
     * The buffer is written in its own transaction, so it survives a rolled-back restore of the
     * rows it describes. A caller that rolls a batch back and retries entry by entry calls this
     * first: the retry re-buffers the same edits, and publishing them twice would attach a title
     * to a manga row that does not exist.
     */
    fun discardPendingEdits() {
        synchronized(pendingEditsLock) { pendingEdits.clear() }
    }

    /**
     * Returns which pass of the restore each entry belongs in, as of right now.
     *
     * Read once per restore. A restore wants entries the database already has *before* the ones it
     * does not, so an update lands on the existing row, and merged entries last of all; ordering a
     * decoded backup to achieve that meant holding every entry in memory to sort it, which is
     * exactly what the streaming entry pass exists to avoid. The matcher lets the caller order the
     * file instead.
     *
     * `internal` rather than public because [RestorePhase] is: the phase is this app's restore
     * pipeline's business, not a type anything outside it can act on.
     */
    internal suspend fun phaseMatcher(): (BackupManga) -> RestorePhase {
        val knownUrlsBySource = handler.awaitList { mangaQueries.getAllMangaSourceAndUrl() }
            .groupBy({ it.source_id }, { it.remote_url })
            .mapValues { (_, urls) -> urls.toHashSet() }

        return { restorePhaseOf(it.source, it.url, knownUrlsBySource) }
    }

    /**
     * Restores [backupMangas] together, in one transaction: either every one lands or none does.
     *
     * ### Why a batch and not one transaction per entry
     *
     * Every `handler.await(inTransaction = true)` that is not already inside a transaction acquires
     * a thread from the database writer pool, opens a real SQLite transaction, and commits it -
     * and the nested calls inside it join that one, so the count is per *entry*, not per query. A
     * library of fifteen thousand series was therefore fifteen thousand commits, each with its
     * thread handoff, against a restore that does milliseconds of actual SQL per entry.
     *
     * Batching trades one thing away: a single bad entry now rolls back its whole batch. That is
     * why the caller retries a failed batch entry by entry, and this is the transaction that
     * makes both halves atomic.
     */
    suspend fun restore(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
        // KMK --> backup category id -> restored database id, from CategoriesRestorer
        restoredCategoryIds: Map<Long, Long> = emptyMap(),
        // KMK <--
    ) {
        handler.await(inTransaction = true) {
            backupMangas.forEach { restoreEntry(it, backupCategories, restoredCategoryIds) }
        }
    }

    /**
     * Restore a single manga.
     */
    private suspend fun Database.restoreEntry(
        backupManga: BackupManga,
        backupCategories: List<BackupCategory>,
        restoredCategoryIds: Map<Long, Long>,
    ) {
        val dbManga = findExistingManga(backupManga)
        var manga = backupManga.getMangaImpl()
        // SY -->
        manga = EXHMigrations.migrateBackupEntry(manga)
        // SY <--
        val restoredManga = if (dbManga == null) {
            restoreNewManga(manga)
        } else {
            restoreExistingManga(manga, dbManga)
        }

        restoreMangaDetails(
            manga = restoredManga,
            chapters = backupManga.chapters,
            categories = backupManga.categories,
            // KMK -->
            categoryIds = backupManga.categoryIds,
            restoredCategoryIds = restoredCategoryIds,
            // KMK <--
            backupCategories = backupCategories,
            history = backupManga.history,
            tracks = backupManga.tracking,
            excludedScanlators = backupManga.excludedScanlators,
            // SY -->
            mergedMangaReferences = backupManga.mergedMangaReferences,
            flatMetadata = backupManga.flatMetadata,
            customManga = backupManga.getCustomMangaInfo(),
            // SY <--
        )

        if (isSync) {
            mangaQueries.resetIsSyncing()
            chapterQueries.resetIsSyncing()
        }
    }

    private suspend fun findExistingManga(backupManga: BackupManga): Manga? {
        return getMangaByUrlAndSourceId.await(backupManga.url, backupManga.source)
    }

    private suspend fun restoreExistingManga(manga: Manga, dbManga: Manga): Manga {
        return if (manga.version > dbManga.version) {
            updateManga(dbManga.copyFrom(manga).copy(id = dbManga.id))
        } else {
            updateManga(manga.copyFrom(dbManga).copy(id = dbManga.id))
        }
    }

    private fun Manga.copyFrom(newer: Manga): Manga {
        return this.copy(
            // SyncYomi v2: un-favouriting is a normal versioned change. Manual restore still
            // keeps an entry in the library if either side had it (legacy backup behaviour).
            favorite = if (isSync) newer.favorite else (this.favorite || newer.favorite),
            // SY -->
            ogAuthor = newer.author,
            ogArtist = newer.artist,
            ogDescription = newer.description,
            ogGenre = newer.genre,
            ogThumbnailUrl = newer.thumbnailUrl,
            ogStatus = newer.status,
            // SY <--
            initialized = this.initialized || newer.initialized,
            version = newer.version,
            // SY -->
            lastModifiedAt = newer.lastModifiedAt,
            // SY <--
        )
    }

    suspend fun updateManga(manga: Manga): Manga {
        handler.await(true) {
            mangaQueries.update(
                source = manga.source,
                url = manga.url,
                // SY -->
                artist = manga.ogArtist,
                author = manga.ogAuthor,
                description = manga.ogDescription,
                genre = manga.ogGenre?.joinToString(separator = ", "),
                title = manga.ogTitle,
                status = manga.ogStatus,
                thumbnailUrl = manga.ogThumbnailUrl,
                // SY <--
                // KMK --> favorite and dateAdded are one column now: the timestamp is only
                // written while the entry is in the library, so the flag and the date pass
                // separately and the statement combines them.
                favoriteSet = true,
                favorite = manga.favorite,
                favoriteAt = manga.dateAdded,
                // KMK <--
                lastUpdate = manga.lastUpdate,
                // KMK -->
                // Preserve the backup's update schedule when it carries one; null
                // keeps the existing row values via coalesce (issue #12).
                nextUpdate = manga.nextUpdate.takeIf { it != 0L },
                calculateInterval = manga.fetchInterval.takeIf { it != 0 }?.toLong(),
                // KMK <--
                initialized = manga.initialized,
                viewer = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                mangaId = manga.id,
                updateStrategy = manga.updateStrategy.let(UpdateStrategyColumnAdapter::encode),
                version = manga.version,
                // SY -->
                lastModifiedAt = if (isSync) manga.lastModifiedAt else null,
                isSyncing = if (isSync) 1 else 0,
                // SY <--
                notes = manga.notes,
                memo = manga.memo.let(MemoColumnAdapter::encode),
                // KMK -->
                scanlatorPriority = manga.scanlatorPriority.let(StringListColumnAdapter::encode),
                blacklistedChapters = manga.blacklistedChapters.let(StringListColumnAdapter::encode),
                rereadCount = manga.rereadCount.toLong(),
                rereading = manga.rereading,
                rereadStartedAt = manga.rereadStartedAt,
                scanlatorRangeRules = manga.scanlatorRangeRules.let(StringListColumnAdapter::encode),
                isLightNovel = manga.isLightNovel,
                // KMK <--
            )
        }
        return manga
    }

    private suspend fun restoreNewManga(
        manga: Manga,
    ): Manga {
        // KMK --> The insert takes every column the backup carries, including the
        // rereading-support ones, so the conditional follow-up update the old insert
        // needed - one extra write per manga with any of them set - is gone.
        return manga.copy(
            id = insertManga(manga),
        )
        // KMK <--
    }

    private suspend fun restoreChapters(manga: Manga, backupChapters: List<BackupChapter>) {
        val dbChaptersByUrl = getChaptersByMangaId.await(manga.id)
            .associateBy { it.url }

        val (existingChapters, newChapters) = backupChapters
            .mapNotNull { backupChapter ->
                val chapter = backupChapter.toChapterImpl().copy(mangaId = manga.id)
                val dbChapter = dbChaptersByUrl[chapter.url]

                when {
                    dbChapter == null -> chapter // New chapter
                    chapter.forComparison() == dbChapter.forComparison() -> null // Same state; skip
                    else -> updateChapterBasedOnSyncState(chapter, dbChapter) // Update existed chapter
                }
            }
            .partition { it.id > 0 }

        insertNewChapters(newChapters)
        updateExistingChapters(existingChapters)

        // SyncYomi v2 responses carry the complete remaining chapter list for each manga.
        // Drop local chapters the server (or another device) tombstoned — without remembering
        // new tombstones; we are applying remote state, not deleting locally.
        if (isSync) {
            val keepUrls = backupChapters.mapTo(HashSet(backupChapters.size)) { it.url }
            val obsoleteIds = dbChaptersByUrl.values
                .mapNotNull { chapter -> chapter.id.takeIf { chapter.url !in keepUrls } }
            if (obsoleteIds.isNotEmpty()) {
                handler.await(true) {
                    chapterQueries.removeChaptersWithIds(obsoleteIds)
                }
            }
        }
    }

    private fun updateChapterBasedOnSyncState(chapter: Chapter, dbChapter: Chapter): Chapter {
        return if (isSync) {
            chapter.copy(
                id = dbChapter.id,
                bookmark = chapter.bookmark || dbChapter.bookmark,
                read = chapter.read,
                lastPageRead = chapter.lastPageRead,
                // KMK -->
                sourceOrder = max(chapter.sourceOrder, dbChapter.sourceOrder),
                dateUpload = min(chapter.dateUpload, dbChapter.dateUpload),
                // KMK <--
            )
        } else {
            chapter.copyFrom(dbChapter)
                // KMK -->
                .copy(
                    id = dbChapter.id,
                    bookmark = chapter.bookmark || dbChapter.bookmark,
                    sourceOrder = max(chapter.sourceOrder, dbChapter.sourceOrder),
                    dateUpload = min(chapter.dateUpload, dbChapter.dateUpload),
                )
                // KMK <--
                .let {
                    when {
                        dbChapter.read && !it.read -> it.copy(read = true, lastPageRead = dbChapter.lastPageRead)
                        it.lastPageRead == 0L && dbChapter.lastPageRead != 0L -> it.copy(
                            lastPageRead = dbChapter.lastPageRead,
                        )
                        else -> it
                    }
                }
        }
    }

    private fun Chapter.forComparison() =
        this.copy(
            id = 0L,
            mangaId = 0L,
            dateFetch = 0L,
            // KMK -->
            // dateUpload = 0L, some time source loses dateUpload so we overwrite with backup
            // sourceOrder = 0L, although sourceOrder will be updated on refresh, we want to avoid order mixed up anyway
            // KMK <--
            lastModifiedAt = 0L,
            version = 0L,
        )

    private suspend fun insertNewChapters(chapters: List<Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
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
                    lastModifiedAt = if (isSync) chapter.lastModifiedAt else 0,
                    isSyncing = if (isSync) 1 else 0,
                    // SY <--
                    memo = chapter.memo,
                )
            }
        }
    }

    private suspend fun updateExistingChapters(chapters: List<Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chapterQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = null,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = null,
                    dateFetch = null,
                    // KMK -->
                    sourceOrder = chapter.sourceOrder,
                    dateUpload = chapter.dateUpload,
                    // KMK <--
                    chapterId = chapter.id,
                    version = chapter.version,
                    // SY -->
                    lastModifiedAt = if (isSync) chapter.lastModifiedAt else null,
                    isSyncing = if (isSync) 1 else 0,
                    // SY <--
                    memo = chapter.memo.let(MemoColumnAdapter::encode),
                    // KMK -->
                    bookmarkColor = chapter.bookmarkColor.value.toLong(),
                    // KMK <--
                )
            }
        }
    }

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    private suspend fun insertManga(manga: Manga): Long {
        return handler.awaitOneExecutable(true) {
            mangaQueries.insert(
                source = manga.source,
                url = manga.url,
                // SY -->
                artist = manga.ogArtist,
                author = manga.ogAuthor,
                description = manga.ogDescription,
                genre = manga.ogGenre,
                title = manga.ogTitle,
                status = manga.ogStatus,
                thumbnailUrl = manga.ogThumbnailUrl,
                // SY <--
                // KMK --> favorite and dateAdded are one column now (see updateManga)
                favoriteAt = if (manga.favorite) manga.dateAdded else null,
                // KMK <--
                lastUpdate = manga.lastUpdate,
                // KMK -->
                // Preserve the backup's update schedule (0 for legacy backups = today's behavior).
                nextUpdate = manga.nextUpdate,
                calculateInterval = manga.fetchInterval.toLong(),
                // KMK <--
                initialized = manga.initialized,
                viewerFlags = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                updateStrategy = manga.updateStrategy,
                version = manga.version,
                // SY -->
                lastModifiedAt = if (isSync) manga.lastModifiedAt else 0,
                isSyncing = if (isSync) 1 else 0,
                // SY <--
                notes = manga.notes,
                memo = manga.memo,
                // KMK --> The insert takes every column the backup can carry, so the
                // rereading-support fields persist here rather than through the follow-up
                // update the old two-statement insert needed.
                scanlatorPriority = manga.scanlatorPriority,
                blacklistedChapters = manga.blacklistedChapters,
                rereadCount = manga.rereadCount.toLong(),
                rereading = manga.rereading,
                rereadStartedAt = manga.rereadStartedAt,
                scanlatorRangeRules = manga.scanlatorRangeRules,
                isLightNovel = manga.isLightNovel,
                // KMK <--
            )
            mangaQueries.selectLastInsertedRowId()
        }
    }

    private suspend fun restoreMangaDetails(
        manga: Manga,
        chapters: List<BackupChapter>,
        categories: List<Long>,
        // KMK -->
        categoryIds: List<Long> = emptyList(),
        restoredCategoryIds: Map<Long, Long> = emptyMap(),
        // KMK <--
        backupCategories: List<BackupCategory>,
        history: List<BackupHistory>,
        tracks: List<BackupTracking>,
        excludedScanlators: List<String>,
        // SY -->
        mergedMangaReferences: List<BackupMergedMangaReference>,
        flatMetadata: BackupFlatMetadata?,
        customManga: CustomMangaInfo?,
        // SY <--
    ): Manga {
        restoreCategories(manga, categories, categoryIds, backupCategories, restoredCategoryIds)
        restoreChapters(manga, chapters)
        restoreTracking(manga, tracks)
        restoreHistory(manga, history)
        restoreExcludedScanlators(manga, excludedScanlators)
        // KMK -->
        // Trust the backup's update schedule when it carries one. Recalculating here
        // collapses every title's predicted date to today + interval (issue #12).
        if (manga.nextUpdate == 0L || manga.fetchInterval == 0) {
            // The chapters parameter is the backup's own list, which is already in hand:
            // reading the schedule back from the table re-fetched every chapter row this
            // restore had just written - a third full chapter read per entry, paid by every
            // entry of a backup that carries no schedule (i.e. a Mihon-format backup).
            updateManga.await(
                fetchInterval.toMangaUpdate(
                    manga = manga,
                    dateTime = now,
                    window = currentFetchWindow,
                    chapters = chapters.map { it.toChapterImpl() },
                ),
            )
        }
        // KMK <--
        // SY -->
        restoreMergedMangaReferencesForManga(manga.id, mergedMangaReferences)
        flatMetadata?.let { restoreFlatMetadata(manga.id, it) }
        restoreEditedInfo(customManga?.copy(id = manga.id))
        // SY <--

        return manga
    }

    /**
     * Restores the categories a manga is in.
     * Only if [backupCategories] is provided and user chooses to restore it.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    private suspend fun restoreCategories(
        manga: Manga,
        categories: List<Long>,
        // KMK -->
        categoryIds: List<Long>,
        backupCategories: List<BackupCategory>,
        restoredCategoryIds: Map<Long, Long>,
        // KMK <--
    ) {
        // KMK --> categoryIds is the only unambiguous link: `sort` is numbered per parent, so
        // a root and its first child are both sort 0 and the order path below cannot tell
        // them apart. Pre-resolve those ids; older backups, and runs that did not restore
        // categories, resolve none and fall through to sort rather than dropping the manga's
        // categories on the floor.
        val resolvedIds = categoryIds.mapNotNull { restoredCategoryIds[it] }.distinct()
        val useIds = resolvedIds.isNotEmpty()
        val dbCategories = if (useIds) emptyList() else restoredCategories
        // KMK <--

        // KMK --> Same name can exist at two levels now, so a plain associateBy would silently
        // keep whichever came last. Prefer the restored root when a backup entry is a subcategory.
        val dbCategoriesByName = dbCategories
            .groupBy { it.name }
            .mapValues { (_, matches) -> matches.firstOrNull { it.parentId == 0L } ?: matches.first() }

        // KMK --> Order stays last: it is the pre-subcategory encoding, still needed for backups
        // written before categoryIds existed, and for runs that did not restore categories.
        val backupCategoriesByOrder = if (useIds) {
            emptyMap()
        } else {
            backupCategories.associateBy { it.order }
        }
        // KMK <--

        // KMK --> distinct() stops a legacy duplicate sort value inserting the same category twice.
        val mangaCategoriesToUpdate = if (useIds) {
            resolvedIds
        } else {
            categories.mapNotNull { backupCategoryOrder ->
                backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                    dbCategoriesByName[backupCategory.name]?.let { dbCategory ->
                        dbCategory.id
                    }
                }
            }.distinct()
        }

        if (mangaCategoriesToUpdate.isNotEmpty()) {
            handler.await(true) {
                manga_categoryQueries.deleteMangaCategoryByMangaId(manga.id)
                mangaCategoriesToUpdate.forEach { categoryId ->
                    manga_categoryQueries.insert(mangaId = manga.id, categoryId = categoryId)
                }
            }
        }
    }

    private suspend fun restoreHistory(manga: Manga, backupHistory: List<BackupHistory>) {
        if (backupHistory.isEmpty()) return

        // Two queries for the whole manga rather than two per history row. Every row
        // needs its chapter's id, and the chapter list is read after [restoreChapters]
        // so it also covers the chapters this restore just inserted.
        val chapterIdsByUrl = getChaptersByMangaId.await(manga.id).associate { it.url to it.id }
        if (chapterIdsByUrl.isEmpty()) return
        val dbHistoryByChapterId = handler.awaitList { historyQueries.getHistoryByMangaId(manga.id) }
            .associateBy { it.chapter_id }

        val toUpdate = backupHistory.mapNotNull { history ->
            // KMK -->
            val chapterId = chapterIdsByUrl[history.url]
                ?: return@mapNotNull null // Chapter doesn't exist; skip
            val dbHistory = dbHistoryByChapterId[chapterId]
            // KMK <--
            val item = history.getHistoryImpl()

            if (dbHistory == null) {
                // New history entry
                item.copy(chapterId = chapterId)
            } else {
                // Update history entry
                item.copy(
                    id = dbHistory.id,
                    chapterId = dbHistory.chapter_id,
                    readAt = max(item.readAt?.time ?: 0L, dbHistory.read_at?.time ?: 0L)
                        .takeIf { it > 0L }
                        ?.let { Date(it) },
                    readDuration = max(item.readDuration, dbHistory.read_duration) - dbHistory.read_duration,
                )
            }
        }

        if (toUpdate.isNotEmpty()) {
            handler.await(true) {
                toUpdate.forEach {
                    historyQueries.upsert(
                        chapterId = it.chapterId,
                        readAt = it.readAt,
                        readDuration = it.readDuration,
                    )
                }
            }
        }
    }

    private suspend fun restoreTracking(manga: Manga, backupTracks: List<BackupTracking>) {
        val dbTrackByTrackerId = getTracks.await(manga.id).associateBy { it.trackerId }

        val (existingTracks, newTracks) = backupTracks
            .mapNotNull {
                val track = it.getTrackImpl()
                val dbTrack = dbTrackByTrackerId[track.trackerId]
                    ?: // New track
                    return@mapNotNull track.copy(
                        id = 0, // Let DB assign new ID
                        mangaId = manga.id,
                    )

                if (track.forComparison() == dbTrack.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing track
                dbTrack.copy(
                    remoteId = track.remoteId,
                    libraryId = track.libraryId,
                    lastChapterRead = max(dbTrack.lastChapterRead, track.lastChapterRead),
                    // KMK -->
                    rereadCount = max(dbTrack.rereadCount, track.rereadCount),
                    // KMK <--
                )
            }
            .partition { it.id > 0 }

        if (newTracks.isNotEmpty()) {
            insertTrack.awaitAll(newTracks)
        }
        if (existingTracks.isNotEmpty()) {
            handler.await(true) {
                existingTracks.forEach { track ->
                    manga_trackQueries.update(
                        mangaId = track.mangaId,
                        trackerId = track.trackerId,
                        remoteId = track.remoteId,
                        remoteUrl = track.remoteUrl,
                        libraryId = track.libraryId,
                        title = track.title,
                        lastChapterRead = track.lastChapterRead,
                        totalChapters = track.totalChapters,
                        status = track.status,
                        score = track.score,
                        startDate = track.startDate,
                        finishDate = track.finishDate,
                        private = track.private,
                        // KMK -->
                        rereadCount = track.rereadCount.toLong(),
                        // KMK <--
                        id = track.id,
                    )
                }
            }
        }
    }

    // SY -->
    /**
     * Restore the categories from Json
     *
     * @param mergeMangaId the merge manga for the references
     * @param backupMergedMangaReferences the list of backup manga references for the merged manga
     */
    private suspend fun restoreMergedMangaReferencesForManga(
        mergeMangaId: Long,
        backupMergedMangaReferences: List<BackupMergedMangaReference>,
    ) {
        if (backupMergedMangaReferences.isEmpty()) return

        backupMergedMangaReferences
            // KMK -->
            .map { EXHMigrations.migrateBackupMergedMangaReference(it) }
            // KMK <--
            .forEach { backupMergedMangaReference ->
                // If the backupMergedMangaReference isn't in the db,
                // remove the id and insert a new backupMergedMangaReference
                // Store the inserted id in the backupMergedMangaReference
                if (!knownMergedReferences.contains(backupMergedMangaReference.mergeUrl to backupMergedMangaReference.mangaUrl)) {
                    // Let the db assign the id
                    // KMK -->
                    val mergedManga = handler.awaitList {
                        // KMK <--
                        mangaQueries.getMangaByUrlAndSource(
                            sourceId = backupMergedMangaReference.mangaSourceId,
                            remoteUrl = backupMergedMangaReference.mangaUrl,
                            mapper = MangaMapper::mapManga,
                        )
                        // KMK -->
                    }.firstOrNull()
                        // KMK <--
                        ?: return@forEach
                    backupMergedMangaReference.getMergedMangaReference().run {
                        handler.await {
                            mergedQueries.insert(
                                infoManga = isInfoManga,
                                getChapterUpdates = getChapterUpdates,
                                chapterSortMode = chapterSortMode.toLong(),
                                chapterPriority = chapterPriority.toLong(),
                                downloadChapters = downloadChapters,
                                mergeId = mergeMangaId,
                                mergeUrl = mergeUrl,
                                mangaId = mergedManga.id,
                                mangaUrl = mangaUrl,
                                mangaSource = mangaSourceId,
                            )
                        }
                    }
                    knownMergedReferences.add(backupMergedMangaReference.mergeUrl to backupMergedMangaReference.mangaUrl)
                }
            }
    }

    private suspend fun restoreFlatMetadata(mangaId: Long, backupFlatMetadata: BackupFlatMetadata) {
        if (getFlatMetadataById.await(mangaId) == null) {
            insertFlatMetadata.await(backupFlatMetadata.getFlatMetadata(mangaId))
        }
    }

    private fun restoreEditedInfo(mangaJson: CustomMangaInfo?) {
        mangaJson ?: return
        synchronized(pendingEditsLock) { pendingEdits += mangaJson }
    }

    private fun BackupManga.getCustomMangaInfo(): CustomMangaInfo? {
        if (customTitle != null ||
            customArtist != null ||
            customAuthor != null ||
            customThumbnailUrl != null ||
            customDescription != null ||
            customGenre != null ||
            customStatus != 0
        ) {
            return CustomMangaInfo(
                id = 0L,
                title = customTitle,
                author = customAuthor,
                artist = customArtist,
                thumbnailUrl = customThumbnailUrl,
                description = customDescription,
                genre = customGenre,
                status = customStatus.takeUnless { it == 0 }?.toLong(),
            )
        }
        return null
    }
    // SY <--

    private fun Track.forComparison() = this.copy(id = 0L, mangaId = 0L)

    /**
     * Restores the excluded scanlators for the manga.
     *
     * @param manga the manga whose excluded scanlators have to be restored.
     * @param excludedScanlators the excluded scanlators to restore.
     */
    private suspend fun restoreExcludedScanlators(manga: Manga, excludedScanlators: List<String>) {
        if (excludedScanlators.isEmpty()) return
        // A backup that lists a scanlator twice - or one the entry already excludes - is a
        // no-op: the insert ignores the conflict (mihon#4022), so the stored exclusions no
        // longer have to be read first to skip them here.
        handler.await(inTransaction = true) {
            excludedScanlators.forEach {
                excluded_scanlatorQueries.insert(manga.id, it)
            }
        }
    }
}

/**
 * Which pass of a restore an entry belongs to. The passes run in declaration order.
 *
 * This is the ordering policy, expressed as a classification rather than a `Comparator`, because
 * the restore no longer holds the entries to sort: it streams them out of the backup file one at a
 * time and walks the file more than once, so "restore these first" has to be answered per entry
 * instead of by sorting a list that was never materialised.
 */
internal enum class RestorePhase {
    /** Entries the database already has, so an update lands on the existing row. */
    EXISTING,

    /** Entries it does not, which the restore inserts. */
    NEW,

    /** Merged entries, which are resolved against the real entries that precede them. */
    MERGED,
}

/**
 * The pass [source]/[url] is restored in.
 *
 * [knownUrlsBySource] must hold sets: a per-entry lookup against a larger collection is what made
 * ordering a library-sized backup expensive, and the lookup now happens once per entry rather than
 * once per comparison.
 */
internal fun restorePhaseOf(
    source: Long,
    url: String,
    knownUrlsBySource: Map<Long, Set<String>>,
): RestorePhase = when {
    // KMK -->
    isMergedSourceId(source) -> RestorePhase.MERGED
    // KMK <--
    // Known is checked against the entry's *own* source: the same url under two sources is two
    // different series, so a hit elsewhere in the map means nothing.
    knownUrlsBySource[source]?.contains(url) == true -> RestorePhase.EXISTING
    else -> RestorePhase.NEW
}
