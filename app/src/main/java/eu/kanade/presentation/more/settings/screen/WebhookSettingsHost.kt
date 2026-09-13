// AM (CONNECTIONS) -->
package eu.kanade.presentation.more.settings.screen

import eu.kanade.domain.connections.service.WebhookSettingKeys
import eu.kanade.presentation.more.settings.framework.SettingDefinition
import eu.kanade.presentation.more.settings.framework.editTextSetting
import eu.kanade.presentation.more.settings.framework.switchSetting
import tachiyomi.core.common.preference.SettingHost
import tachiyomi.core.common.preference.SettingsRegistry
import tachiyomi.i18n.kmk.KMR

/**
 * Pilot host for the modular settings framework.
 *
 * Each entry is one full setting: storage key + default ([WebhookSettingKeys]), title, and
 * row type. The screen renders them with `toItems(store)` instead of hand-building
 * `PreferenceItem` instances — adding a new webhook toggle is now one [switchSetting]
 * declaration plus one list entry here.
 *
 * Non-standard rows (test button, info text, async category multi-select) stay hand-built
 * in [SettingsWebhookScreen] as documented escape hatches.
 */
object WebhookSettingsHost : SettingHost {
    override val settingKeys = WebhookSettingKeys.all

    init {
        SettingsRegistry.register(this)
    }

    val connectionSwitch: SettingDefinition<Boolean> = switchSetting(
        key = WebhookSettingKeys.ENABLED,
        titleRes = KMR.strings.pref_webhook_enabled,
    )

    val urlFields: List<SettingDefinition<String>> = listOf(
        editTextSetting(
            key = WebhookSettingKeys.DISCORD_URL,
            titleRes = KMR.strings.pref_webhook_discord_url,
        ),
        editTextSetting(
            key = WebhookSettingKeys.GENERIC_URL,
            titleRes = KMR.strings.pref_webhook_generic_url,
        ),
    )

    /** Connection rows (master switch + URL fields) in screen order. */
    val connectionItems: List<SettingDefinition<*>> = listOf<SettingDefinition<*>>(connectionSwitch) + urlFields

    /** Event toggles in the exact order previously hand-built in [SettingsWebhookScreen]. */
    val eventSwitches: List<SettingDefinition<Boolean>> = listOf(
        switchSetting(
            key = WebhookSettingKeys.CHAPTER_STARTED,
            titleRes = KMR.strings.pref_webhook_chapter_started,
        ),
        switchSetting(
            key = WebhookSettingKeys.CHAPTER_READ,
            titleRes = KMR.strings.pref_webhook_chapter_read,
        ),
        switchSetting(
            key = WebhookSettingKeys.INCLUDE_READING_TIME,
            titleRes = KMR.strings.pref_webhook_include_reading_time,
            subtitleRes = KMR.strings.pref_webhook_include_reading_time_summary,
        ),
        switchSetting(
            key = WebhookSettingKeys.NEW_MANGA_STARTED,
            titleRes = KMR.strings.pref_webhook_new_manga_started,
        ),
        switchSetting(
            key = WebhookSettingKeys.MANGA_FINISHED,
            titleRes = KMR.strings.pref_webhook_manga_finished,
        ),
        switchSetting(
            key = WebhookSettingKeys.LIBRARY_UPDATE,
            titleRes = KMR.strings.pref_webhook_library_update,
        ),
        switchSetting(
            key = WebhookSettingKeys.BACKUP_CREATED,
            titleRes = KMR.strings.pref_webhook_backup_created,
        ),
        // KMK -->
        switchSetting(
            key = WebhookSettingKeys.MANGA_ADDED,
            titleRes = KMR.strings.pref_webhook_manga_added,
        ),
        switchSetting(
            key = WebhookSettingKeys.MANGA_REMOVED,
            titleRes = KMR.strings.pref_webhook_manga_removed,
        ),
        switchSetting(
            key = WebhookSettingKeys.DOWNLOADS_FINISHED,
            titleRes = KMR.strings.pref_webhook_downloads_finished,
        ),
        switchSetting(
            key = WebhookSettingKeys.BACKUP_RESTORED,
            titleRes = KMR.strings.pref_webhook_backup_restored,
        ),
        switchSetting(
            key = WebhookSettingKeys.MANGA_MIGRATED,
            titleRes = KMR.strings.pref_webhook_manga_migrated,
        ),
        switchSetting(
            key = WebhookSettingKeys.APP_UPDATED,
            titleRes = KMR.strings.pref_webhook_app_updated,
        ),
        switchSetting(
            key = WebhookSettingKeys.ACHIEVEMENT_UNLOCKED,
            titleRes = KMR.strings.pref_webhook_achievement_unlocked,
        ),
        // KMK <--
    )
}
// <-- AM (CONNECTIONS)
