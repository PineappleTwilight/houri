package mihon.domain.migration.usecases

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.event.AppEvent
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.source.isMergedSourceId
import kotlinx.coroutines.CancellationException
import mihon.app.di.globalAppGraph
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.DeleteMergeById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.GetMergedReferencesByManga
import tachiyomi.domain.manga.interactor.InsertMergedReference
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import java.time.Instant

@Inject
class MigrateMangaUseCase(
    private val sourcePreferences: SourcePreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val coverCache: CoverCache,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    // KMK -->
    private val getHistory: GetHistory,
    private val upsertHistory: UpsertHistory,
    // KMK <--
    // KMK --> Merged-entry support
    private val getManga: GetManga,
    private val getMergedReferencesById: GetMergedReferencesById,
    private val getMergedReferencesByManga: GetMergedReferencesByManga,
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId,
    private val insertMergedReference: InsertMergedReference,
    private val deleteMergeById: DeleteMergeById,
    // KMK <--
) {
    // KMK -->
    private val appEventBus get() = globalAppGraph.appEventBus
    // KMK <--
    private val enhancedServices by lazy { trackerManager.trackers.filterIsInstance<EnhancedTracker>() }

    suspend operator fun invoke(
        current: Manga,
        target: Manga,
        replace: Boolean,
        // KMK -->
        presetFlags: Set<MigrationFlag>? = null,
        // KMK <--
        // SY -->
        throttleFunc: suspend () -> Unit = {},
        // SY <--
    ) {
        // KMK -->
        // A merged entry (source 6969) must not migrate like a plain entry: that would
        // favorite the target as a standalone entry and unfavorite the merged entry,
        // tearing the merge apart and leaving all its merged sources behind. Instead,
        // keep the merged entry and swap the source it was built from for the new one.
        if (isMergedSourceId(current.source) && !isMergedSourceId(target.source)) {
            migrateMergedManga(
                current = current,
                target = target,
                replace = replace,
                flags = presetFlags ?: sourcePreferences.migrationFlags().get(),
                throttleFunc = throttleFunc,
            )
            return
        }
        // KMK <--

        val targetSource = sourceManager.get(target.source) ?: return
        val currentSource = sourceManager.get(current.source)
        val flags = /* KMK --> */ presetFlags ?: /* KMK <-- */ sourcePreferences.migrationFlags().get()

        try {
            updateMangaFromRemote(
                manga = target,
                fetchChapters = true,
                // SY -->
                throttleFunc = throttleFunc,
                // SY <--
            ).getOrThrow()

            // KMK -->
            // Pass the current entry's edited series details through to the migrated one.
            // Those edits (title/author/artist/description/genre/status overrides) live in
            // `custom_manga_info`, keyed by manga id, and the migrated entry has a different
            // id - so its row starts empty and everything the user cleaned or corrected fell
            // back to raw source metadata. The row is copied, not moved, like notes and
            // categories; `updateMangaFromRemote` has just refreshed the target's source-side
            // fields, and the edit resolves over them. Unconditional rather than behind a
            // MigrationFlag: this is user-entered data that must not silently vanish, not a
            // setting. Resolved through `globalAppGraph` like `appEventBus` so the legacy
            // Injekt factory still shares Metro's single CustomMangaRepositoryImpl.
            globalAppGraph.getCustomMangaInfo.get(current.id)?.let { editedInfo ->
                globalAppGraph.setCustomMangaInfo.set(editedInfo.copy(id = target.id))
            }
            // KMK <--

            // Update chapters read, bookmark and dateFetch
            if (MigrationFlag.CHAPTER in flags) {
                val prevMangaChapters = getChaptersByMangaId.await(current.id)

                // SY -->
                val prevHistoryList = getHistory.await(current.id)
                    .associateBy { it.chapterId }
                // SY <--

                copyChapterProgress(target, prevMangaChapters, prevHistoryList)
            }

            // Update categories
            if (MigrationFlag.CATEGORY in flags) {
                val categoryIds = getCategories.await(current.id).map { it.id }
                setMangaCategories.await(target.id, categoryIds)
            }

            // Update track
            // SY -->
            if (MigrationFlag.TRACK in flags) {
                // SY <--
                getTracks.await(current.id).mapNotNull { track ->
                    val updatedTrack = track.copy(mangaId = target.id)

                    val service = enhancedServices
                        .firstOrNull { it.isTrackFrom(updatedTrack, current, currentSource) }

                    if (service != null) {
                        service.migrateTrack(updatedTrack, target, targetSource)
                    } else {
                        updatedTrack
                    }
                }
                    .takeIf { it.isNotEmpty() }
                    ?.let { insertTrack.awaitAll(it) }
            }

            // Delete downloaded
            if (MigrationFlag.REMOVE_DOWNLOAD in flags && currentSource != null) {
                downloadManager.deleteManga(current, currentSource)
            }

            // Update custom cover (recheck if custom cover exists)
            if (MigrationFlag.CUSTOM_COVER in flags && current.hasCustomCover()) {
                coverCache.setCustomCoverToCache(target, coverCache.getCustomCoverFile(current.id).inputStream())
            }

            val currentMangaUpdate = MangaUpdate(
                id = current.id,
                favorite = false,
                dateAdded = 0,
            )
                .takeIf { replace }
            val targetMangaUpdate = MangaUpdate(
                id = target.id,
                favorite = true,
                chapterFlags = current.chapterFlags
                    // KMK -->
                    .takeIf { MigrationFlag.EXTRA in flags },
                // KMK <--
                viewerFlags = current.viewerFlags
                    // KMK -->
                    .takeIf { MigrationFlag.EXTRA in flags },
                // KMK <--
                dateAdded = if (replace) current.dateAdded else Instant.now().toEpochMilli(),
                notes = if (MigrationFlag.NOTES in flags) current.notes else null,
            )

            updateManga.awaitAll(listOfNotNull(currentMangaUpdate, targetMangaUpdate))
            // KMK -->
            // A migrated source inside a merged entry must replace that source in the merge
            // instead of leaving the merged entry pointing at the old entry. (Merged entries
            // themselves take the dedicated path above; this covers their source entries.)
            if (replace) {
                retargetMergedReferences(current, target)
            }
            appEventBus.emit(
                AppEvent.MangaMigrated(
                    mangaTitle = target.title,
                    fromSource = currentSource?.name ?: current.source.toString(),
                    toSource = targetSource.name,
                    sourceId = target.source,
                    mangaId = target.id,
                ),
            )
            // KMK <--
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
        }
    }

    // KMK -->
    /**
     * Carries the read state of the migrated entry's chapters over to [target]:
     * matching chapters (by recognized number) inherit read state, bookmark,
     * last-read page and read history; chapters only present on the new entry
     * are marked read up to the highest already-read chapter number.
     */
    private suspend fun copyChapterProgress(
        target: Manga,
        prevMangaChapters: List<Chapter>,
        prevHistoryList: Map<Long, History>,
    ) {
        val mangaChapters = getChaptersByMangaId.await(target.id)

        val maxChapterRead = prevMangaChapters
            .filter { it.read }
            .maxOfOrNull { it.chapterNumber }

        // SY -->
        val historyUpdates = mutableListOf<HistoryUpdate>()
        // SY <--

        val updatedMangaChapters = mangaChapters.map { mangaChapter ->
            var updatedChapter = mangaChapter
            if (updatedChapter.isRecognizedNumber) {
                val prevChapter = prevMangaChapters
                    .find { it.isRecognizedNumber && it.chapterNumber == updatedChapter.chapterNumber }

                if (prevChapter != null) {
                    updatedChapter = updatedChapter.copy(
                        // SY -->
                        // If chapters match then mark new manga's chapters read/unread as old one
                        read = prevChapter.read,
                        // SY <--
                        dateFetch = prevChapter.dateFetch,
                        bookmark = prevChapter.bookmark,
                        lastPageRead = prevChapter.lastPageRead,
                    )
                    // SY -->
                    prevHistoryList[prevChapter.id]?.let { prevHistory ->
                        historyUpdates += HistoryUpdate(
                            mangaChapter.id,
                            prevHistory.readAt ?: return@let,
                            prevHistory.readDuration,
                        )
                    }
                    // SY <--
                } else if (maxChapterRead != null && updatedChapter.chapterNumber <= maxChapterRead) {
                    // If chapters which only present on new manga then mark read up to latest read chapter number
                    updatedChapter = updatedChapter.copy(read = true)
                }
            }

            updatedChapter
        }

        val chapterUpdates = updatedMangaChapters.map { it.toChapterUpdate() }
        updateChapter.awaitAll(chapterUpdates)
        // SY -->
        upsertHistory.awaitAll(historyUpdates)
        // SY <--
    }
    // KMK <--

    // KMK -->
    /**
     * Re-points the merged-entry references of [current] to [target] so a source
     * migration inside a merged entry replaces that source instead of leaving
     * the merged entry behind on the old one. If [target] is already part of the
     * merge, the old reference is dropped instead of duplicated. The new
     * reference is always inserted before the old one is deleted, so a failure
     * cannot lose the merge.
     */
    private suspend fun retargetMergedReferences(current: Manga, target: Manga) {
        if (isMergedSourceId(target.source)) return

        val references = getMergedReferencesByManga.await(
            mangaId = current.id,
            mangaUrl = current.url,
            mangaSourceId = current.source,
        )
        for (reference in references) {
            // Skip the merge's own reference; it never points at a real source.
            if (isMergedSourceId(reference.mangaSourceId)) continue
            if (reference.mangaUrl == target.url && reference.mangaSourceId == target.source) {
                // The reference already points at the target; nothing to change.
                continue
            }
            val targetAlreadyMerged = getMergedReferencesById.await(reference.mergeId!!).any {
                it.id != reference.id &&
                    it.mangaSourceId == target.source &&
                    it.mangaUrl == target.url
            }
            if (!targetAlreadyMerged) {
                insertMergedReference.await(
                    reference.copy(
                        id = -1,
                        mangaId = target.id,
                        mangaUrl = target.url,
                        mangaSourceId = target.source,
                    ),
                ) ?: continue
            }
            deleteMergeById.await(reference.id)
        }
    }
    // KMK <--

    // KMK -->
    /**
     * Migrates a merged entry (a library entry backed by [exh.source.MergedSource])
     * by keeping the merged entry itself and swapping the source it was built
     * from (its info manga) for the new one. A plain migration would instead
     * favorite the target as a standalone entry and unfavorite the merged
     * entry, silently tearing the merge apart.
     *
     * Everything that belongs to the merged entry as a whole - categories,
     * tracks, notes, reading flags, custom cover - stays on the merged entry,
     * since it keeps its identity; only its sources change.
     *
     * With `replace = false` the new source is added to the merge as an
     * additional source and nothing is removed.
     */
    private suspend fun migrateMergedManga(
        current: Manga,
        target: Manga,
        replace: Boolean,
        flags: Set<MigrationFlag>,
        throttleFunc: suspend () -> Unit,
    ) {
        val targetSource = sourceManager.get(target.source) ?: return
        try {
            val references = getMergedReferencesById.await(current.id)
            // The reference of the source the merged entry was built from: the
            // info manga, or the first real source when nothing is flagged.
            val replacedReference = references.firstOrNull {
                it.isInfoManga && !isMergedSourceId(it.mangaSourceId)
            } ?: references.firstOrNull { !isMergedSourceId(it.mangaSourceId) } ?: return
            if (replacedReference.mangaUrl == target.url &&
                replacedReference.mangaSourceId == target.source
            ) {
                // The merge already points at the target; nothing to migrate.
                return
            }
            val replacedManga = replacedReference.mangaId?.let { getManga.await(it) }
                ?: getManga.await(replacedReference.mangaUrl, replacedReference.mangaSourceId)

            updateMangaFromRemote(
                manga = target,
                fetchChapters = true,
                throttleFunc = throttleFunc,
            ).getOrThrow()

            // Carry read progress over from the merged chapter view, which
            // aggregates every source of the merge.
            if (MigrationFlag.CHAPTER in flags) {
                val prevMangaChapters = getMergedChaptersByMangaId.await(current.id)
                val prevHistoryList = references.mapNotNull { it.mangaId }.distinct()
                    .flatMap { getHistory.await(it) }
                    .associateBy { it.chapterId }
                copyChapterProgress(target, prevMangaChapters, prevHistoryList)
            }

            val alreadyMerged = references.any {
                it.id != replacedReference.id &&
                    it.mangaSourceId == target.source &&
                    it.mangaUrl == target.url
            }
            if (!alreadyMerged) {
                val newReference = if (replace) {
                    // The new source takes over the old source's role in the merge.
                    replacedReference.copy(
                        id = -1,
                        mangaId = target.id,
                        mangaUrl = target.url,
                        mangaSourceId = target.source,
                    )
                } else {
                    // Copy: the new source joins as an additional source.
                    replacedReference.copy(
                        id = -1,
                        isInfoManga = false,
                        getChapterUpdates = true,
                        chapterSortMode = MergedMangaReference.CHAPTER_SORT_NONE,
                        chapterPriority = 0,
                        downloadChapters = true,
                        mangaId = target.id,
                        mangaUrl = target.url,
                        mangaSourceId = target.source,
                    )
                }
                // Insert first: only drop the old reference once the new one is
                // persisted, so a failure cannot lose the merge.
                insertMergedReference.await(newReference) ?: return
                if (replace) deleteMergeById.await(replacedReference.id)
            } else if (replace) {
                deleteMergeById.await(replacedReference.id)
            }

            if (replace) {
                // Retire the replaced source's entry like a plain migration would.
                replacedManga?.let { manga ->
                    updateManga.awaitAll(
                        listOf(
                            MangaUpdate(
                                id = manga.id,
                                favorite = false,
                                dateAdded = 0,
                            ),
                        ),
                    )
                    if (MigrationFlag.REMOVE_DOWNLOAD in flags) {
                        sourceManager.get(manga.source)?.let { source ->
                            downloadManager.deleteManga(manga, source)
                        }
                    }
                }
            }

            appEventBus.emit(
                AppEvent.MangaMigrated(
                    mangaTitle = target.title,
                    fromSource = replacedManga?.let { sourceManager.get(it.source)?.name }
                        ?: replacedReference.mangaSourceId.toString(),
                    toSource = targetSource.name,
                    sourceId = target.source,
                    mangaId = current.id,
                ),
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        }
    }
    // KMK <--
}
