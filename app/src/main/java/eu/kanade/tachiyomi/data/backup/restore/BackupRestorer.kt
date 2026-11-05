package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.FeedRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.RestorePhase
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import java.io.File
import java.text.SimpleDateFormat
import java.util.BitSet
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val MANGA_CHUNK_SIZE = 250

private const val PROGRESS_NOTIFICATION_INTERVAL_MS = 250L

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(/* SY --> */ isSync /* SY <-- */),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val extensionStoreRestorer: ExtensionStoreRestorer = ExtensionStoreRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(isSync),
    // SY -->
    private val savedSearchRestorer: SavedSearchRestorer = SavedSearchRestorer(),
    // SY <--
    // KMK -->
    private val feedRestorer: FeedRestorer = FeedRestorer(),
    // KMK <--
) {

    private val restoreAmount = AtomicInteger(0)
    private val restoreProgress = AtomicInteger(0)
    private val errors = CopyOnWriteArrayList<Pair<Date, String>>()

    private val lastProgressPost = AtomicLong(0L)

    /**
     * Entries decoded from the backup but not yet written, and the merged entries held back until
     * every other entry has been restored.
     *
     * Both exist because the restore streams the file instead of decoding it whole. See
     * [restoreManga].
     */
    private val pendingEntries = ArrayList<BackupManga>(MANGA_CHUNK_SIZE)
    private val mergedEntries = ArrayList<BackupManga>()

    /**
     * Categories as the backup wrote them, and the backup's category ids mapped onto the ids the
     * restore gave them. Constant for the whole run, so they are read once here rather than
     * threaded through every chunk.
     */
    private var backupCategories: List<BackupCategory> = emptyList()
    private var restoredCategoryIds: Map<Long, Long> = emptyMap()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        try {
            restoreFromFile(uri, options)
        } finally {
            // KMK --> A library-sized restore has just pushed every series, chapter and history
            // row into the write-ahead log, where they stay until something folds it. Doing it
            // here - outside the restore body only because a checkpoint must not run inside a
            // transaction - leaves the next process, the startup integrity probe and the next
            // backup snapshot a file that is already self-contained instead of one that has to
            // replay hundreds of megabytes of WAL. Safe either way: a busy checkpoint simply
            // leaves the fold to the next maintenance pass.
            runCatching { mihon.app.di.globalAppGraph.databaseHandler.checkpoint() }
            // KMK <--
        }

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        // Suppress organic achievement increments for DB imports — tracker/history counts should not inflate organic stats
        val achievementPrefs = try {
            mihon.app.di.globalAppGraph.achievementPreferences
        } catch (_: Exception) {
            null
        }
        val prevSuppress = achievementPrefs?.suppressOrganicForImport
        // A restore rewrites the whole unlocked-achievements preference in one write, so every
        // id the user ever earned arrives as a single "new" change. Notifying per id queued one
        // toast and one sound per achievement — on a fresh install that is the entire catalogue,
        // staggered 900ms apart. Bulk mode collapses the restore into one summary; it has to
        // bracket the whole restore, because the restore is exactly what produces the burst.
        val unlockNotifier = try {
            mihon.app.di.globalAppGraph.achievementNotifier
        } catch (_: Exception) {
            null
        }
        unlockNotifier?.setBulkMode(true)
        try {
            // Separate function so the bulk-mode bracket stays one indentation level out and
            // its `finally` cannot be skipped by anything in the restore body.
            restoreFromFileBulk(uri, options, achievementPrefs, prevSuppress)
        } finally {
            // A Throwable escaping the restore must not leave bulk mode latched, which would
            // silence toasts for the rest of the process. Whatever the restore unlocked is
            // summarised in exactly one line on the way out.
            runCatching { unlockNotifier?.setBulkMode(false) }
        }
    }

    private suspend fun restoreFromFileBulk(
        uri: Uri,
        options: RestoreOptions,
        achievementPrefs: tachiyomi.domain.achievement.service.AchievementPreferences?,
        prevSuppress: Boolean?,
    ) {
        try {
            achievementPrefs?.suppressOrganicForImport = true
            val decodeStart = SystemClock.elapsedRealtime()
            val decoder = BackupDecoder(context)
            // Metadata only: the entries themselves are streamed one at a time during restoreManga,
            // so a library-sized backup is never decoded into memory in full. See BackupReader.
            val (entryCount, backup) = decoder.decodeMetadata(uri)
            logcat(LogPriority.INFO) {
                "Restore read metadata for $entryCount entries in ${SystemClock.elapsedRealtime() - decodeStart}ms"
            }

            // Store source mapping for error messages
            val backupMaps = backup.backupSources
            sourceMapping = backupMaps.associate { it.sourceId to it.name }

            if (options.libraryEntries) {
                restoreAmount.addAndGet(entryCount)
            }
            if (options.categories) {
                restoreAmount.incrementAndGet()
            }
            // SY -->
            if (options.savedSearchesFeeds) {
                restoreAmount.incrementAndGet()
            }
            // SY <--
            if (options.appSettings) {
                restoreAmount.incrementAndGet()
            }
            if (options.extensionStores) {
                restoreAmount.addAndGet(backup.backupExtensionStores.size)
            }
            if (options.sourceSettings) {
                restoreAmount.incrementAndGet()
            }

            coroutineScope {
                // KMK --> Needed before restoreManga: a backup's category reference only means
                // anything once the tree exists and each backup id has its new database id.
                val restoredCategoryIds = if (options.categories) {
                    restoreCategories(backup.backupCategories)
                } else {
                    emptyMap()
                }
                // KMK <--
                // SY -->
                if (options.savedSearchesFeeds) {
                    restoreSavedSearches(
                        backup.backupSavedSearches,
                        // KMK -->
                        backup.backupFeeds,
                        // KMK <--
                    )
                }
                // SY <--
                if (options.appSettings) {
                    restoreAppPreferences(backup.backupPreferences, backup.backupCategories.takeIf { options.categories })
                }
                if (options.sourceSettings) {
                    restoreSourcePreferences(backup.backupSourcePreferences)
                }
                if (options.libraryEntries) {
                    // KMK -->
                    restoreManga(
                        uri,
                        if (options.categories) backup.backupCategories else emptyList(),
                        restoredCategoryIds,
                    )
                    // KMK <--
                }
                if (options.extensionStores) {
                    restoreExtensionStores(backup.backupExtensionStores)
                }

                // KMK --> Inside the try, so a restore that threw does not count.
                runCatching {
                    mihon.app.di.globalAppGraph.achievementManager.tryUnlockDirect("backup_restored")
                }
                // KMK <--
            }

            // KMK --> After the scope, not inside it. The restore above is a `launch`, so
            // anything enqueued from inside the scope started while the restore was still
            // writing. A full library refresh reads and writes every entry on the same
            // single-writer database the restore is using, so for a large backup the two
            // spent their time contending rather than the restore finishing first.
            if (options.libraryEntries) {
                // KMK --> The follow-up refresh was unconditional; it is now the
                // "Update library after restore" toggle, so restoring into a finished
                // library can skip hours of background updates.
                if (options.updateLibrary) {
                    LibraryUpdateJob.startNow(context)
                }
                // KMK <--
            }
            // KMK <--
        } finally {
            if (prevSuppress != null) achievementPrefs?.suppressOrganicForImport = prevSuppress
            // KMK --> credit imported library entries towards library/backlog achievements
            // (restore runs with organic increments suppressed, so library_X unlocks
            // would otherwise never fire for DB imports).
            try {
                if (options.libraryEntries) {
                    val graph = mihon.app.di.globalAppGraph
                    // KMK --> Counted in SQL. Reading the whole libraryView to take its size was a full
                    // scan plus a LibraryManga per series, right at the end of a restore that had
                    // just written all of them - and it had already been caught OOMing on a large
                    // library once, which is why that read had a guard returning empty.
                    val count = try {
                        graph.mangaRepository.getLibraryCount()
                    } catch (_: Exception) {
                        -1L
                    }
                    if (count >= 0) graph.achievementManager.onLibraryCountChanged(count)
                    // KMK --> Restore rewrites every category link, so the stale-unstarted set
                    // changes wholesale. Forced because a restore must be exact immediately.
                    runCatching { graph.achievementManager.refreshBacklog(force = true) }
                    // KMK <--
                }
            } catch (_: Exception) {}
            // KMK <--
        }
    }

    context(scope: CoroutineScope)
    private /* KMK --> */suspend /* KMK <-- */ fun restoreCategories(backupCategories: List<BackupCategory>): Map<Long, Long> {
        scope.ensureActive()
        val restoredIdsByBackupId = categoriesRestorer(backupCategories)

        restoreProgress.incrementAndGet()
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.categories),
                restoreProgress.get(),
                restoreAmount.get(),
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
        return restoredIdsByBackupId
    }

    // SY -->
    private fun CoroutineScope.restoreSavedSearches(
        backupSavedSearches: List<BackupSavedSearch>,
        // KMK -->
        backupFeeds: List<BackupFeed>,
        // KMK <--
    ) = launch {
        ensureActive()
        savedSearchRestorer.restoreSavedSearches(backupSavedSearches)
        // KMK -->
        feedRestorer.restoreFeeds(backupFeeds)
        // KMK <--

        restoreProgress.incrementAndGet()
        with(notifier) {
            showRestoreProgress(
                context.stringResource(KMR.strings.saved_searches_feeds),
                restoreProgress.get(),
                restoreAmount.get(),
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }
    // SY <--

    private fun CoroutineScope.restoreManga(
        uri: Uri,
        backupCategories: List<BackupCategory>,
        // KMK -->
        restoredCategoryIds: Map<Long, Long> = emptyMap(),
        // KMK <--
    ) = launch {
        val mangaStart = SystemClock.elapsedRealtime()
        val decoder = BackupDecoder(context)
        val phaseOf = mangaRestorer.phaseMatcher()
        mangaRestorer.prepare()
        this@BackupRestorer.backupCategories = backupCategories
        this@BackupRestorer.restoredCategoryIds = restoredCategoryIds

        // New entries first, existing ones second, merged ones last - see restorePhaseOf.
        //
        // The file is walked twice rather than decoded once and sorted. Sorting meant holding every
        // entry of a library-sized backup in memory at once - hundreds of megabytes of series,
        // chapters and history rows living alongside the buffer they were decoded from. Instead the
        // first pass restores what is new and records *which entries in the file* the database
        // already holds; the second pass goes back for those, skipping everything else by length.
        val laterIndices = BitSet()
        var restoredEntries = 0
        decoder.decodeEntries(uri)
            .withIndex()
            .collect { (index, manga) ->
                when (phaseOf(manga)) {
                    RestorePhase.NEW -> restoreEntry(manga)
                    RestorePhase.EXISTING -> laterIndices.set(index)
                    // KMK --> Merged entries resolve against the real entries that precede them, so
                    // they cannot be restored until both passes are done. They are a small minority
                    // of a library, which is what makes buffering them affordable.
                    // KMK <--
                    RestorePhase.MERGED -> mergedEntries += manga
                }
                restoredEntries++
            }
        decoder.decodeEntries(uri) { laterIndices.get(it) }
            .collect {
                restoreEntry(it)
                restoredEntries++
            }
        // KMK --> Whatever the passes left under one chunk is written now. Merged entries are
        // already after this on purpose, but restoreEntries clears [pendingEntries] in its
        // finally, so feeding it merged chunks with a partial buffer still pending dropped the
        // last entries of the library on the floor: they were never written and the progress
        // bar stopped short of 100%.
        if (pendingEntries.isNotEmpty()) restoreEntries(pendingEntries.toList())
        // KMK <--
        mergedEntries.chunked(MANGA_CHUNK_SIZE)
            .forEach { restoreEntries(it) }

        logcat(LogPriority.INFO) {
            "Restore wrote $restoredEntries entries in ${SystemClock.elapsedRealtime() - mangaStart}ms"
        }
    }

    /**
     * Buffers one entry into the current chunk and restores the chunk once it is full.
     *
     * Entries are decoded one at a time out of the backup file, so they are batched as they
     * arrive rather than being collected first: the peak is one chunk of entries plus whatever
     * merged entries are held back, not the whole library.
     */
    private suspend fun restoreEntry(entry: BackupManga) {
        pendingEntries += entry
        if (pendingEntries.size >= MANGA_CHUNK_SIZE) restoreEntries(pendingEntries.toList())
    }

    /**
     * Restores one chunk of entries, falling back to entry by entry if the batch fails.
     *
     * The batch is a single transaction, so one entry that cannot be written rolls back the whole
     * chunk - which is why a failure is retried per entry instead of reported. Each entry then gets
     * its own transaction and its own error, and the rest of the chunk still lands.
     */
    private suspend fun restoreEntries(chunk: List<BackupManga>) {
        if (chunk.isEmpty()) return
        try {
            // Also on cancellation: the edits buffered so far are already written, so dropping them
            // would leave the restored rows without the titles the backup carried.
            try {
                val restoredAsBatch = try {
                    currentCoroutineContext().ensureActive()
                    mangaRestorer.restore(chunk, backupCategories, restoredCategoryIds)
                    true
                } catch (e: Exception) {
                    // The edits this chunk buffered describe rows the rolled-back transaction just
                    // removed, and publishing them would attach a title to a manga that is not
                    // there. The retry re-buffers them.
                    mangaRestorer.discardPendingEdits()
                    logcat(LogPriority.WARN, e) { "Batch restore failed, retrying entry by entry" }
                    false
                }

                if (restoredAsBatch) {
                    restoreProgress.addAndGet(chunk.size)
                } else {
                    chunk.forEach { entry ->
                        currentCoroutineContext().ensureActive()
                        try {
                            mangaRestorer.restore(listOf(entry), backupCategories, restoredCategoryIds)
                        } catch (e: Exception) {
                            val sourceName = sourceMapping[entry.source] ?: entry.source.toString()
                            errors.add(Date() to "${entry.title} [$sourceName]: ${e.message}")
                        }
                        restoreProgress.incrementAndGet()
                    }
                }
            } finally {
                mangaRestorer.flushPendingEdits()
            }

            // One notification per chunk rather than per entry: each post is a round trip to the
            // system UI, which for an entry this small costs more than the entry's own database
            // work. The last chunk always posts, so the bar ends at 100%.
            val progress = restoreProgress.get()
            val posted = SystemClock.elapsedRealtime()
            if (
                progress >= restoreAmount.get() ||
                posted - lastProgressPost.get() >= PROGRESS_NOTIFICATION_INTERVAL_MS
            ) {
                lastProgressPost.set(posted)
                with(notifier) {
                    showRestoreProgress(chunk.last().title, progress, restoreAmount.get(), isSync)
                        // KMK -->
                        .show(Notifications.ID_RESTORE_PROGRESS)
                    // KMK <--
                }
            }
        } finally {
            pendingEntries.clear()
        }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        restoreProgress.incrementAndGet()
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.app_settings),
                restoreProgress.get(),
                restoreAmount.get(),
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        restoreProgress.incrementAndGet()
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.source_settings),
                restoreProgress.get(),
                restoreAmount.get(),
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    private fun CoroutineScope.restoreExtensionStores(
        backupExtensionStores: List<BackupExtensionStore>,
    ) = launch {
        backupExtensionStores
            .forEach {
                ensureActive()

                try {
                    extensionStoreRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error adding extension store: ${it.name} : ${e.message}")
                }

                restoreProgress.incrementAndGet()
                // KMK -->
                with(notifier) {
                    // KMK <--
                    showRestoreProgress(
                        context.stringResource(MR.strings.extensionStores),
                        restoreProgress.get(),
                        restoreAmount.get(),
                        isSync,
                    )
                        // KMK -->
                        .show(Notifications.ID_RESTORE_PROGRESS)
                    // KMK <--
                }
            }
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("houri_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }
}
