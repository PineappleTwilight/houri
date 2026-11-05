package eu.kanade.domain.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.sync.models.SyncSettings
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.data.sync.models.SyncTriggerOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.UUID

@SingleIn(AppScope::class)
@Inject
class SyncPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun clientHost() = preferenceStore.getString("connection_sync_client_host", "https://sync.tachiyomi.org")
    fun clientAPIKey() = preferenceStore.getString("connection_sync_client_api_key", "")
    fun lastSyncTimestamp() = preferenceStore.getLong(Preference.appStateKey("last_sync_timestamp"), 0L)

    fun lastSyncEtag() = preferenceStore.getString("sync_etag", "")

    // SyncYomi protocol v2 state
    fun syncCursor() = preferenceStore.getLong(Preference.appStateKey("sync_cursor"), 0L)

    /** Epoch seconds of the start of the last successful sync; the next delta starts here. */
    fun lastPushedAt() = preferenceStore.getLong(Preference.appStateKey("sync_last_pushed_at"), 0L)

    /** Epoch millis of the last sync that carried the complete library. */
    fun lastFullSync() = preferenceStore.getLong(Preference.appStateKey("sync_last_full_sync"), 0L)
    fun fullSyncRequested() = preferenceStore.getBoolean(Preference.appStateKey("sync_full_requested"), false)
    fun serverSupportsV2() = preferenceStore.getBoolean(Preference.appStateKey("sync_server_supports_v2"), false)
    fun v2ProbedHost() = preferenceStore.getString(Preference.appStateKey("sync_v2_probed_host"), "")
    fun pendingDeletedCategoryUids() = preferenceStore.getStringSet(
        Preference.appStateKey("sync_pending_deleted_category_uids"),
        emptySet(),
    )
    fun pendingDeletedChapterKeys() = preferenceStore.getStringSet(
        Preference.appStateKey("sync_pending_deleted_chapter_keys"),
        emptySet(),
    )
    private val tombstoneMutex = Mutex()

    fun syncInterval() = preferenceStore.getInt("sync_interval", 0)
    fun syncService() = preferenceStore.getInt("sync_service", 0)

    // KMK -->
    fun webDavUrl() = preferenceStore.getString("connection_webdav_url", "")
    fun webDavUsername() = preferenceStore.getString("connection_webdav_username", "")
    fun webDavPassword() = preferenceStore.getString("connection_webdav_password", "")
    fun webDavFolder() = preferenceStore.getString("connection_webdav_folder", "houri")
    // KMK <--

    fun googleDriveAccessToken() = preferenceStore.getString(
        Preference.appStateKey("connection_google_drive_access_token"),
        "",
    )

    fun googleDriveRefreshToken() = preferenceStore.getString(
        Preference.appStateKey("connection_google_drive_refresh_token"),
        "",
    )

    fun uniqueDeviceID(): String {
        val uniqueIDPreference = preferenceStore.getString(Preference.appStateKey("unique_device_id"), "")

        // Retrieve the current value of the preference
        var uniqueID = uniqueIDPreference.get()
        if (uniqueID.isBlank()) {
            uniqueID = UUID.randomUUID().toString()
            uniqueIDPreference.set(uniqueID)
        }

        return uniqueID
    }

    fun isSyncEnabled(): Boolean {
        return syncService().get() != SyncManager.SyncService.NONE.value
    }

    private fun isSyncYomiEnabled() = syncService().get() == SyncManager.SyncService.SYNCYOMI.value

    /**
     * Remembers a deleted category so the next SyncYomi v2 request can send it as a tombstone.
     */
    suspend fun rememberDeletedCategory(uid: Long) {
        if (uid == 0L || !isSyncYomiEnabled()) return
        tombstoneMutex.withLock {
            pendingDeletedCategoryUids().set(pendingDeletedCategoryUids().get() + uid.toString())
        }
    }

    suspend fun forgetDeletedCategories(uids: Collection<String>) = tombstoneMutex.withLock {
        pendingDeletedCategoryUids().set(pendingDeletedCategoryUids().get() - uids.toSet())
    }

    suspend fun rememberDeletedChapters(keys: Collection<String>) {
        if (keys.isEmpty() || !isSyncYomiEnabled()) return
        tombstoneMutex.withLock {
            pendingDeletedChapterKeys().set(pendingDeletedChapterKeys().get() + keys)
        }
    }

    suspend fun forgetDeletedChapters(keys: Collection<String>) = tombstoneMutex.withLock {
        val pending = pendingDeletedChapterKeys().get()
        val remaining = pending - keys.toSet()
        if (remaining.size != pending.size) pendingDeletedChapterKeys().set(remaining)
    }

    fun getSyncSettings(): SyncSettings {
        return SyncSettings(
            libraryEntries = preferenceStore.getBoolean("sync_library_entries", true).get(),
            categories = preferenceStore.getBoolean("sync_categories", true).get(),
            chapters = preferenceStore.getBoolean("sync_chapters", true).get(),
            tracking = preferenceStore.getBoolean("sync_tracking", true).get(),
            history = preferenceStore.getBoolean("sync_history", true).get(),
            appSettings = preferenceStore.getBoolean("sync_appSettings", true).get(),
            extensionStores = preferenceStore.getBoolean("sync_extensionStores", true).get(),
            sourceSettings = preferenceStore.getBoolean("sync_sourceSettings", true).get(),
            privateSettings = preferenceStore.getBoolean("sync_privateSettings", true).get(),

            // SY -->
            customInfo = preferenceStore.getBoolean("sync_customInfo", true).get(),
            readEntries = preferenceStore.getBoolean("sync_readEntries", true).get(),
            savedSearchesFeeds = preferenceStore.getBoolean("sync_savedSearchesFeeds", true).get(),
            // SY <--
        )
    }

    fun setSyncSettings(syncSettings: SyncSettings) {
        preferenceStore.getBoolean("sync_library_entries", true).set(syncSettings.libraryEntries)
        preferenceStore.getBoolean("sync_categories", true).set(syncSettings.categories)
        preferenceStore.getBoolean("sync_chapters", true).set(syncSettings.chapters)
        preferenceStore.getBoolean("sync_tracking", true).set(syncSettings.tracking)
        preferenceStore.getBoolean("sync_history", true).set(syncSettings.history)
        preferenceStore.getBoolean("sync_appSettings", true).set(syncSettings.appSettings)
        preferenceStore.getBoolean("sync_extensionStores", true).set(syncSettings.extensionStores)
        preferenceStore.getBoolean("sync_sourceSettings", true).set(syncSettings.sourceSettings)
        preferenceStore.getBoolean("sync_privateSettings", true).set(syncSettings.privateSettings)

        // SY -->
        preferenceStore.getBoolean("sync_customInfo", true).set(syncSettings.customInfo)
        preferenceStore.getBoolean("sync_readEntries", true).set(syncSettings.readEntries)
        preferenceStore.getBoolean("sync_savedSearchesFeeds", true).set(syncSettings.savedSearchesFeeds)
        // SY <--
    }

    fun getSyncTriggerOptions(): SyncTriggerOptions {
        return SyncTriggerOptions(
            syncOnChapterRead = preferenceStore.getBoolean("sync_on_chapter_read", false).get(),
            syncOnChapterOpen = preferenceStore.getBoolean("sync_on_chapter_open", false).get(),
            syncOnAppStart = preferenceStore.getBoolean("sync_on_app_start", false).get(),
            syncOnAppResume = preferenceStore.getBoolean("sync_on_app_resume", false).get(),
        )
    }

    fun setSyncTriggerOptions(syncTriggerOptions: SyncTriggerOptions) {
        preferenceStore.getBoolean("sync_on_chapter_read", false)
            .set(syncTriggerOptions.syncOnChapterRead)
        preferenceStore.getBoolean("sync_on_chapter_open", false)
            .set(syncTriggerOptions.syncOnChapterOpen)
        preferenceStore.getBoolean("sync_on_app_start", false)
            .set(syncTriggerOptions.syncOnAppStart)
        preferenceStore.getBoolean("sync_on_app_resume", false)
            .set(syncTriggerOptions.syncOnAppResume)
    }

    // KMK -->
    fun showSyncingProgressBanner() = preferenceStore.getBoolean(
        Preference.appStateKey("pref_show_syncing_progress_banner_key"),
        true,
    )
    // KMK <--
}
