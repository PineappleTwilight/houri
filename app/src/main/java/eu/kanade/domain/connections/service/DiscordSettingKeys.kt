// AM (DISCORD) -->
package eu.kanade.domain.connections.service

import tachiyomi.core.common.preference.SettingKey

/**
 * Single source of truth for every key owned by [ConnectionsPreferences]' Discord RPC
 * accessors, mirroring the `WebhookSettingKeys` pilot.
 *
 * Key strings are frozen: backup files match on the raw string and existing installs
 * already persist under these names. Do not rename any [SettingKey.key] without a
 * migration in `mihon.core.migration.migrations`.
 */
object DiscordSettingKeys {
    val ENABLE = SettingKey("pref_enable_discord_rpc", false)
    val STATUS = SettingKey("pref_discord_rpc_status", 1)
    val INCOGNITO = SettingKey("pref_discord_rpc_incognito", false)
    val INCOGNITO_CATEGORIES = SettingKey<Set<String>>("discord_rpc_incognito_categories", emptySet())
    val USE_CHAPTER_TITLES = SettingKey("pref_discord_rpc_use_chapter_titles", false)
    val CUSTOM_MESSAGE = SettingKey("pref_discord_custom_message", "")
    val SHOW_PROGRESS = SettingKey("pref_discord_show_progress", true)

    // KMK -->
    val SHOW_PAGE_PROGRESS = SettingKey("pref_discord_show_page_progress", true)
    // KMK <--

    val SHOW_TIMESTAMP = SettingKey("pref_discord_show_timestamp", true)
    val SHOW_BUTTONS = SettingKey("pref_discord_show_buttons", true)
    val SHOW_DOWNLOAD_BUTTON = SettingKey("pref_discord_show_download_button", true)
    val SHOW_DISCORD_BUTTON = SettingKey("pref_discord_show_discord_button", true)
    val ACCOUNTS = SettingKey("discord_accounts", "")

    val all: List<SettingKey<*>> = listOf(
        ENABLE,
        STATUS,
        INCOGNITO,
        INCOGNITO_CATEGORIES,
        USE_CHAPTER_TITLES,
        CUSTOM_MESSAGE,
        SHOW_PROGRESS,
        // KMK -->
        SHOW_PAGE_PROGRESS,
        // KMK <--
        SHOW_TIMESTAMP,
        SHOW_BUTTONS,
        SHOW_DOWNLOAD_BUTTON,
        SHOW_DISCORD_BUTTON,
        ACCOUNTS,
    )
}
// <-- AM (DISCORD)
