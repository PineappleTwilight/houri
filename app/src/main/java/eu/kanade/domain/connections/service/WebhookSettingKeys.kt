// AM (CONNECTIONS) -->
package eu.kanade.domain.connections.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.SettingKey

/**
 * Single source of truth for every key owned by [WebhookPreferences].
 *
 * Previously the key strings lived inline in each `WebhookPreferences.foo()` getter; the
 * settings screen duplicated nothing but any new consumer had to copy the literal. Both
 * [WebhookPreferences] and `WebhookSettingsHost` reference these constants, so a key can
 * no longer drift between storage and UI.
 *
 * Do not rename any [SettingKey.key] without a migration in
 * `mihon.core.migration.migrations` — backup files match on the raw string.
 */
object WebhookSettingKeys {
    val ENABLED = SettingKey("pref_webhook_enabled", false)
    val DISCORD_URL = SettingKey(Preference.privateKey("pref_webhook_discord_url"), "")
    val GENERIC_URL = SettingKey(Preference.privateKey("pref_webhook_generic_url"), "")
    val CHAPTER_STARTED = SettingKey("pref_webhook_chapter_started", false)
    val CHAPTER_READ = SettingKey("pref_webhook_chapter_read", true)
    val NEW_MANGA_STARTED = SettingKey("pref_webhook_new_manga_started", true)
    val MANGA_FINISHED = SettingKey("pref_webhook_manga_finished", true)
    val LIBRARY_UPDATE = SettingKey("pref_webhook_library_update", false)
    val BACKUP_CREATED = SettingKey("pref_webhook_backup_created", false)

    // KMK -->
    val MANGA_ADDED = SettingKey("pref_webhook_manga_added", true)
    val MANGA_REMOVED = SettingKey("pref_webhook_manga_removed", false)
    val DOWNLOADS_FINISHED = SettingKey("pref_webhook_downloads_finished", false)
    val BACKUP_RESTORED = SettingKey("pref_webhook_backup_restored", false)
    val MANGA_MIGRATED = SettingKey("pref_webhook_manga_migrated", false)
    val APP_UPDATED = SettingKey("pref_webhook_app_updated", true)
    val ACHIEVEMENT_UNLOCKED = SettingKey("pref_webhook_achievement_unlocked", true)
    val EXCLUDED_CATEGORIES = SettingKey<Set<String>>("pref_webhook_excluded_categories", emptySet())
    // KMK <--

    val INCLUDE_READING_TIME = SettingKey("pref_webhook_include_reading_time", true)

    val all: List<SettingKey<*>> = listOf(
        ENABLED,
        DISCORD_URL,
        GENERIC_URL,
        CHAPTER_STARTED,
        CHAPTER_READ,
        NEW_MANGA_STARTED,
        MANGA_FINISHED,
        LIBRARY_UPDATE,
        BACKUP_CREATED,
        // KMK -->
        MANGA_ADDED,
        MANGA_REMOVED,
        DOWNLOADS_FINISHED,
        BACKUP_RESTORED,
        MANGA_MIGRATED,
        APP_UPDATED,
        ACHIEVEMENT_UNLOCKED,
        EXCLUDED_CATEGORIES,
        // KMK <--
        INCLUDE_READING_TIME,
    )
}
// <-- AM (CONNECTIONS)
