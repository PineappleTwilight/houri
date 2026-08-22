// AM (CONNECTIONS) -->
package eu.kanade.domain.connections.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class WebhookPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun enabled() = preferenceStore.getBoolean("pref_webhook_enabled", false)

    fun discordWebhookUrl() = preferenceStore.getString(
        Preference.privateKey("pref_webhook_discord_url"),
        "",
    )

    fun genericWebhookUrl() = preferenceStore.getString(
        Preference.privateKey("pref_webhook_generic_url"),
        "",
    )

    fun notifyOnChapterStarted() = preferenceStore.getBoolean("pref_webhook_chapter_started", false)

    fun notifyOnChapterRead() = preferenceStore.getBoolean("pref_webhook_chapter_read", true)

    fun notifyOnNewMangaStarted() = preferenceStore.getBoolean("pref_webhook_new_manga_started", true)

    fun notifyOnMangaFinished() = preferenceStore.getBoolean("pref_webhook_manga_finished", true)

    fun notifyOnLibraryUpdate() = preferenceStore.getBoolean("pref_webhook_library_update", false)

    fun notifyOnBackupCreated() = preferenceStore.getBoolean("pref_webhook_backup_created", false)

    // KMK -->
    fun notifyOnMangaAdded() = preferenceStore.getBoolean("pref_webhook_manga_added", true)

    fun notifyOnMangaRemoved() = preferenceStore.getBoolean("pref_webhook_manga_removed", false)

    fun notifyOnDownloadsFinished() = preferenceStore.getBoolean("pref_webhook_downloads_finished", false)

    fun notifyOnBackupRestored() = preferenceStore.getBoolean("pref_webhook_backup_restored", false)

    fun notifyOnMangaMigrated() = preferenceStore.getBoolean("pref_webhook_manga_migrated", false)

    fun notifyOnAppUpdated() = preferenceStore.getBoolean("pref_webhook_app_updated", true)

    fun excludedCategories() = preferenceStore.getStringSet(
        "pref_webhook_excluded_categories",
        emptySet(),
    )
    // KMK <--

    fun includeReadingTime() = preferenceStore.getBoolean("pref_webhook_include_reading_time", true)
}
// <-- AM (CONNECTIONS)
