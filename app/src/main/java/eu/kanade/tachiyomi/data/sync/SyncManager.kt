package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.net.Uri
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.sync.service.BackupRequestBody
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.data.sync.service.SyncData
import eu.kanade.tachiyomi.data.sync.service.SyncResult
import eu.kanade.tachiyomi.data.sync.service.SyncYomiSyncService
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncService
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import mihon.app.di.globalAppGraph
import okio.buffer
import okio.sink
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Chapter
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.MangaMapper.mapManga
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.util.Date
import kotlin.system.measureTimeMillis
import kotlin.time.Clock

/**
 * A manager to handle synchronization tasks in the app, such as updating
 * sync preferences and performing synchronization with a remote server.
 *
 * @property context The application context.
 */
class SyncManager(
    private val context: Context,
    private val handler: DatabaseHandler = globalAppGraph.databaseHandler,
    private val syncPreferences: SyncPreferences = globalAppGraph.syncPreferences,
    private var json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
    private val getCategories: GetCategories = globalAppGraph.getCategories,
    private val protoBuf: ProtoBuf = globalAppGraph.protoBuf,
) {
    private val backupCreator: BackupCreator = BackupCreator(context, false)
    private val notifier: SyncNotifier = SyncNotifier(context)
    private val mangaRestorer: MangaRestorer = MangaRestorer(isSync = true)

    enum class SyncService(val value: Int) {
        NONE(0),
        SYNCYOMI(1),
        GOOGLE_DRIVE(2),
        // KMK -->
        WEB_DAV(3),
        // KMK <--
        ;

        companion object {
            fun fromInt(value: Int) = entries.firstOrNull { it.value == value } ?: NONE
        }
    }

    /**
     * Syncs data with a sync service.
     *
     * This function retrieves local data (favorites, manga, extensions, and categories)
     * from the database using the BackupManager, then synchronizes the data with a sync service.
     */
    suspend fun syncData() {
        // Epoch seconds. Everything modified before this instant is in this upload; the next delta starts here.
        val syncStart = Clock.System.now().epochSeconds

        // Reset isSyncing in case it was left over or failed syncing during restore.
        handler.await(inTransaction = true) {
            mangaQueries.resetIsSyncing()
            chapterQueries.resetIsSyncing()
        }

        val syncOptions = syncPreferences.getSyncSettings()

        val backupOptions = BackupOptions(
            libraryEntries = syncOptions.libraryEntries,
            categories = syncOptions.categories,
            chapters = syncOptions.chapters,
            tracking = syncOptions.tracking,
            history = syncOptions.history,
            extensionStores = syncOptions.extensionStores,
            appSettings = syncOptions.appSettings,
            sourceSettings = syncOptions.sourceSettings,
            privateSettings = syncOptions.privateSettings,

            // SY -->
            customInfo = syncOptions.customInfo,
            readEntries = syncOptions.readEntries,
            savedSearchesFeeds = syncOptions.savedSearchesFeeds,
            // SY <--
        )

        // Handle sync based on the selected service
        val syncService = when (val syncService = SyncService.fromInt(syncPreferences.syncService().get())) {
            SyncService.SYNCYOMI -> {
                SyncYomiSyncService(
                    context,
                    json,
                    syncPreferences,
                    notifier,
                )
            }

            SyncService.GOOGLE_DRIVE -> {
                GoogleDriveSyncService(context, json, syncPreferences)
            }

            // KMK -->
            SyncService.WEB_DAV -> {
                WebDavSyncService(context, json, syncPreferences, notifier)
            }
            // KMK <--

            else -> {
                logcat(LogPriority.ERROR) { "Invalid sync service type: $syncService" }
                null
            }
        }

        // Whether to upload the whole library or only what changed since the last successful sync (SyncYomi v2).
        val full = try {
            syncService?.needsFullSync() ?: true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to probe sync server" }
            notifier.showSyncError(e.message)
            return
        }

        val databaseManga = if (full) {
            getAllMangaThatNeedsSync()
        } else {
            getMangasModifiedSince(syncPreferences.lastPushedAt().get())
        }

        logcat(LogPriority.DEBUG) { "Begin create backup (full=$full)" }
        val backupManga = backupCreator.backupMangas(databaseManga, backupOptions)
            .let { if (full) it else changedSince(it, syncPreferences.lastPushedAt().get()) }
        val backup = Backup(
            backupManga = backupManga,
            backupCategories = backupCreator.backupCategories(backupOptions),
            backupSources = backupCreator.backupSources(backupManga),
            backupPreferences = backupCreator.backupAppPreferences(backupOptions),
            backupSourcePreferences = backupCreator.backupSourcePreferences(backupOptions),
            backupExtensionStores = backupCreator.backupExtensionStores(backupOptions),

            // SY -->
            backupSavedSearches = backupCreator.backupSavedSearches(backupOptions),
            // SY <--

            // KMK -->
            backupFeeds = backupCreator.backupFeeds(backupOptions),
            // KMK <--
        )
        logcat(LogPriority.DEBUG) { "End create backup" }

        // Create the SyncData object
        val syncData = SyncData(
            deviceId = syncPreferences.uniqueDeviceID(),
            backup = backup,
        )

        val result: SyncResult = syncService?.doSync(syncData, full) ?: return
        val remoteBackup = result.backup

        if (remoteBackup == null) {
            logcat(LogPriority.DEBUG) { "Skip restore due to network issues" }
            // should we call showSyncError?
            return
        }

        if (remoteBackup === syncData.backup || (result.protocolV2 && !result.changed)) {
            // nothing changed
            logcat(LogPriority.DEBUG) { "Skip restore, nothing new on the remote" }
            finishWithSuccess(syncStart, "Sync completed successfully")
            return
        }

        if (!result.protocolV2) {
            // Stop the sync early if the remote backup is null or empty
            if (remoteBackup.backupManga.isEmpty() &&
                remoteBackup.backupCategories.isEmpty() &&
                remoteBackup.backupSources.isEmpty()
            ) {
                notifier.showSyncError("No data found on remote server.")
                return
            }

            // Check if it's first sync based on lastSyncTimestamp
            if (syncPreferences.lastSyncTimestamp().get() == 0L && databaseManga.isNotEmpty()) {
                // It's first sync no need to restore data. (just update remote data)
                finishWithSuccess(syncStart, "Updated remote data successfully")
                return
            }
        }

        val (filteredFavorites, nonFavorites) = filterFavoritesAndNonFavorites(remoteBackup)
        updateNonFavorites(nonFavorites)
        // the restore below may not run, so its reset of the sync flag cannot be relied on
        handler.await(inTransaction = true) {
            mangaQueries.resetIsSyncing()
        }

        val newSyncData = backup.copy(
            backupManga = filteredFavorites,
            backupCategories = remoteBackup.backupCategories,
            // a v2 delta only carries changed sources
            backupSources = remoteBackup.backupSources.ifEmpty { backup.backupSources },
            backupPreferences = remoteBackup.backupPreferences,
            backupSourcePreferences = remoteBackup.backupSourcePreferences,
            backupExtensionStores = remoteBackup.backupExtensionStores,

            // SY -->
            backupSavedSearches = remoteBackup.backupSavedSearches,
            // SY <--

            // KMK -->
            backupFeeds = remoteBackup.backupFeeds,
            // KMK <--
        )

        val hasMangaChanges = filteredFavorites.isNotEmpty()
        val hasCategoryChanges = categoriesDiffer(backup.backupCategories, remoteBackup.backupCategories)
        // a v2 delta leaves out the sections nothing changed in; an absent section is not a change
        val hasSourceChanges = newSyncData.backupSources != backup.backupSources
        val hasPreferenceChanges = remoteBackup.backupPreferences.isNotEmpty() &&
            remoteBackup.backupPreferences != backup.backupPreferences
        val hasSourcePreferenceChanges = remoteBackup.backupSourcePreferences.isNotEmpty() &&
            remoteBackup.backupSourcePreferences != backup.backupSourcePreferences
        val hasExtensionRepoChanges = remoteBackup.backupExtensionStores.isNotEmpty() &&
            extensionStoresDiffer(backup.backupExtensionStores, remoteBackup.backupExtensionStores)
        val hasSavedSearchChanges = remoteBackup.backupSavedSearches.isNotEmpty() &&
            remoteBackup.backupSavedSearches != backup.backupSavedSearches
        // KMK -->
        val hasFeedChanges = remoteBackup.backupFeeds.isNotEmpty() &&
            remoteBackup.backupFeeds != backup.backupFeeds
        // KMK <--

        if (!hasMangaChanges && !hasCategoryChanges && !hasSourceChanges &&
            !hasPreferenceChanges && !hasSourcePreferenceChanges &&
            !hasExtensionRepoChanges && !hasSavedSearchChanges &&
            // KMK -->
            !hasFeedChanges
            // KMK <--
        ) {
            finishWithSuccess(syncStart, "Sync completed successfully")
            return
        }

        if (syncOptions.categories) {
            val mergedUids = newSyncData.backupCategories.map { it.uid }.filter { it != 0L }.toSet()
            val mergedNames = newSyncData.backupCategories.map { it.name }.toSet()
            val localCategories = getCategories.await().filterNot { it.id == 0L }
            val categoriesToDelete = localCategories.filter {
                (it.uid == 0L || it.uid !in mergedUids) && it.name !in mergedNames
            }
            if (categoriesToDelete.isNotEmpty()) {
                categoriesToDelete.forEach { category ->
                    handler.await { categoryQueries.delete(category.id) }
                    if (category.uid != 0L) {
                        syncPreferences.rememberDeletedCategory(category.uid)
                    }
                }
            }
        }

        if (newSyncData.hasNothingToRestore()) {
            // the category deletions above were the whole change; an empty backup cannot be decoded
            finishWithSuccess(syncStart, "Sync completed successfully")
            return
        }

        val backupUri = writeSyncDataToCache(context, newSyncData)
        logcat(LogPriority.DEBUG) { "Got Backup Uri: $backupUri" }
        if (backupUri != null) {
            // Await the apply so a failed restore fails the sync instead of
            // silently reporting success with nothing written.
            val applied = BackupRestoreJob.startAndAwaitSuccess(
                context,
                backupUri,
                sync = true,
                options = RestoreOptions(
                    appSettings = true,
                    sourceSettings = true,
                    libraryEntries = true,
                    categories = true,
                    extensionStores = true,
                ),
            )
            if (applied) {
                syncPreferences.lastSyncTimestamp().set(Date().time)
                syncPreferences.lastPushedAt().set(syncStart)
            } else {
                // The cursor already advanced past the data that failed to apply, so
                // ask the server for everything on the next run to self-heal.
                syncPreferences.fullSyncRequested().set(true)
                notifier.showSyncError("Failed to apply synced data; will retry with a full sync")
                logcat(LogPriority.ERROR) { "Sync restore failed; requesting full sync on next run" }
            }
        } else {
            logcat(LogPriority.ERROR) { "Failed to write sync data to file" }
        }
    }

    private fun finishWithSuccess(syncStart: Long, message: String) {
        syncPreferences.lastSyncTimestamp().set(Date().time)
        // everything modified before this instant reached the server; the next delta starts here
        syncPreferences.lastPushedAt().set(syncStart)
        notifier.showSyncSuccess(message)
    }

    private fun Backup.hasNothingToRestore() = backupManga.isEmpty() &&
        backupCategories.isEmpty() &&
        backupSources.isEmpty() &&
        backupPreferences.isEmpty() &&
        backupSourcePreferences.isEmpty() &&
        backupExtensionStores.isEmpty() &&
        backupSavedSearches.isEmpty() &&
        // KMK -->
        backupFeeds.isEmpty()
    // KMK <--

    private fun writeSyncDataToCache(context: Context, backup: Backup): Uri? {
        val cacheFile = File(context.cacheDir, "tachiyomi_sync_data.proto.gz")
        return try {
            cacheFile.sink().buffer().use { BackupRequestBody(backup, protoBuf).writeTo(it) }
            Uri.fromFile(cacheFile)
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, throwable = e) { "Failed to write sync data to cache" }
            null
        }
    }

    /**
     * Retrieves all manga from the local database.
     *
     * @return a list of all manga stored in the database
     */
    private suspend fun getAllMangaFromDB(): List<Manga> {
        return handler.awaitList { mangaQueries.getAllManga(::mapManga) }
    }

    private suspend fun getAllMangaThatNeedsSync(): List<Manga> {
        return handler.awaitList { mangaQueries.getMangasWithFavoriteTimestamp(::mapManga) }
    }

    private suspend fun getMangasModifiedSince(since: Long): List<Manga> {
        return handler.awaitList { mangaQueries.getMangasModifiedSince(since, ::mapManga) }
    }

    private suspend fun isMangaDifferent(localManga: Manga, remoteManga: BackupManga): Boolean {
        val localChapters = handler.await {
            chapterQueries.getChaptersByMangaId(
                localManga.id,
                0,
                // KMK -->
                Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
                Manga.CHAPTER_SHOW_BOOKMARKED,
                // KMK <--
            ).executeAsList()
        }
        val localCategories = getCategories.await(localManga.id).map { it.order }

        if (areChaptersDifferent(localChapters, remoteManga.chapters)) {
            return true
        }

        if (localManga.version != remoteManga.version) {
            return true
        }

        if (localCategories.toSet() != remoteManga.categories.toSet()) {
            return true
        }

        return false
    }

    private fun areChaptersDifferent(localChapters: List<Chapter>, remoteChapters: List<BackupChapter>): Boolean {
        val localChapterMap = localChapters.associateBy { it.remote_url }
        val remoteChapterMap = remoteChapters.associateBy { it.url }

        if (localChapterMap.size != remoteChapterMap.size) {
            return true
        }

        for ((url, localChapter) in localChapterMap) {
            val remoteChapter = remoteChapterMap[url]

            // If a matching remote chapter doesn't exist, or the version numbers are different, consider them different
            if (remoteChapter == null || localChapter.version != remoteChapter.version) {
                return true
            }
        }

        return false
    }

    /**
     * Filters the favorite and non-favorite manga from the backup and checks
     * if the favorite manga is different from the local database.
     * @param backup the Backup object containing the backup data.
     * @return a Pair of lists, where the first list contains different favorite manga
     * and the second list contains non-favorite manga.
     */
    private suspend fun filterFavoritesAndNonFavorites(backup: Backup): Pair<List<BackupManga>, List<BackupManga>> {
        val favorites = mutableListOf<BackupManga>()
        val nonFavorites = mutableListOf<BackupManga>()
        val logTag = "filterFavoritesAndNonFavorites"

        val elapsedTimeMillis = measureTimeMillis {
            val databaseManga = getAllMangaFromDB()
            val localMangaMap = databaseManga.associateBy {
                Triple(it.source, it.url, it.title)
            }

            logcat(LogPriority.DEBUG, logTag) { "Starting to filter favorites and non-favorites from backup data." }

            backup.backupManga.forEach { remoteManga ->
                val compositeKey = Triple(remoteManga.source, remoteManga.url, remoteManga.title)
                val localManga = localMangaMap[compositeKey]
                when {
                    // Checks if the manga is in favorites and needs updating or adding
                    remoteManga.favorite -> {
                        if (localManga == null || isMangaDifferent(localManga, remoteManga)) {
                            logcat(LogPriority.DEBUG, logTag) { "Adding to favorites: ${remoteManga.title}" }
                            favorites.add(remoteManga)
                        } else {
                            logcat(LogPriority.DEBUG, logTag) { "Already up-to-date favorite: ${remoteManga.title}" }
                        }
                    }
                    // Handle non-favorites
                    !remoteManga.favorite -> {
                        logcat(LogPriority.DEBUG, logTag) { "Adding to non-favorites: ${remoteManga.title}" }
                        nonFavorites.add(remoteManga)
                    }
                }
            }
        }

        val minutes = elapsedTimeMillis / 60000
        val seconds = (elapsedTimeMillis % 60000) / 1000
        logcat(LogPriority.DEBUG, logTag) {
            "Filtering completed in ${minutes}m ${seconds}s. Favorites found: ${favorites.size}, " +
                "Non-favorites found: ${nonFavorites.size}"
        }

        return Pair(favorites, nonFavorites)
    }

    /**
     * Updates the non-favorite manga in the local database with their favorite status from the backup.
     * @param nonFavorites the list of non-favorite BackupManga objects from the backup.
     */
    private suspend fun updateNonFavorites(nonFavorites: List<BackupManga>) {
        val localMangaList = getAllMangaFromDB()

        val localMangaMap = localMangaList.associateBy { Triple(it.source, it.url, it.title) }

        nonFavorites.forEach { nonFavorite ->
            val key = Triple(nonFavorite.source, nonFavorite.url, nonFavorite.title)
            localMangaMap[key]?.let { localManga ->
                if (localManga.favorite != nonFavorite.favorite) {
                    val updatedManga = localManga.copy(favorite = nonFavorite.favorite)
                    mangaRestorer.updateManga(updatedManga)
                }
            }
        }
    }
}
