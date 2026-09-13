// AM (CONNECTIONS) -->
package eu.kanade.domain.connections.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class WebhookPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun enabled() = preferenceStore.getBoolean(WebhookSettingKeys.ENABLED.key, WebhookSettingKeys.ENABLED.default)

    fun discordWebhookUrl() = preferenceStore.getString(
        WebhookSettingKeys.DISCORD_URL.key,
        WebhookSettingKeys.DISCORD_URL.default,
    )

    fun genericWebhookUrl() = preferenceStore.getString(
        WebhookSettingKeys.GENERIC_URL.key,
        WebhookSettingKeys.GENERIC_URL.default,
    )

    fun notifyOnChapterStarted() =
        preferenceStore.getBoolean(WebhookSettingKeys.CHAPTER_STARTED.key, WebhookSettingKeys.CHAPTER_STARTED.default)

    fun notifyOnChapterRead() =
        preferenceStore.getBoolean(WebhookSettingKeys.CHAPTER_READ.key, WebhookSettingKeys.CHAPTER_READ.default)

    fun notifyOnNewMangaStarted() = preferenceStore.getBoolean(
        WebhookSettingKeys.NEW_MANGA_STARTED.key,
        WebhookSettingKeys.NEW_MANGA_STARTED.default,
    )

    fun notifyOnMangaFinished() = preferenceStore.getBoolean(
        WebhookSettingKeys.MANGA_FINISHED.key,
        WebhookSettingKeys.MANGA_FINISHED.default,
    )

    fun notifyOnLibraryUpdate() = preferenceStore.getBoolean(
        WebhookSettingKeys.LIBRARY_UPDATE.key,
        WebhookSettingKeys.LIBRARY_UPDATE.default,
    )

    fun notifyOnBackupCreated() = preferenceStore.getBoolean(
        WebhookSettingKeys.BACKUP_CREATED.key,
        WebhookSettingKeys.BACKUP_CREATED.default,
    )

    // KMK -->
    fun notifyOnMangaAdded() =
        preferenceStore.getBoolean(WebhookSettingKeys.MANGA_ADDED.key, WebhookSettingKeys.MANGA_ADDED.default)

    fun notifyOnMangaRemoved() =
        preferenceStore.getBoolean(WebhookSettingKeys.MANGA_REMOVED.key, WebhookSettingKeys.MANGA_REMOVED.default)

    fun notifyOnDownloadsFinished() = preferenceStore.getBoolean(
        WebhookSettingKeys.DOWNLOADS_FINISHED.key,
        WebhookSettingKeys.DOWNLOADS_FINISHED.default,
    )

    fun notifyOnBackupRestored() = preferenceStore.getBoolean(
        WebhookSettingKeys.BACKUP_RESTORED.key,
        WebhookSettingKeys.BACKUP_RESTORED.default,
    )

    fun notifyOnMangaMigrated() = preferenceStore.getBoolean(
        WebhookSettingKeys.MANGA_MIGRATED.key,
        WebhookSettingKeys.MANGA_MIGRATED.default,
    )

    fun notifyOnAppUpdated() =
        preferenceStore.getBoolean(WebhookSettingKeys.APP_UPDATED.key, WebhookSettingKeys.APP_UPDATED.default)

    fun notifyOnAchievementUnlocked() = preferenceStore.getBoolean(
        WebhookSettingKeys.ACHIEVEMENT_UNLOCKED.key,
        WebhookSettingKeys.ACHIEVEMENT_UNLOCKED.default,
    )

    fun excludedCategories() = preferenceStore.getStringSet(
        WebhookSettingKeys.EXCLUDED_CATEGORIES.key,
        WebhookSettingKeys.EXCLUDED_CATEGORIES.default,
    )
    // KMK <--

    fun includeReadingTime() = preferenceStore.getBoolean(
        WebhookSettingKeys.INCLUDE_READING_TIME.key,
        WebhookSettingKeys.INCLUDE_READING_TIME.default,
    )
}
// <-- AM (CONNECTIONS)
