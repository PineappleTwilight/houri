// AM (DISCORD) -->
package eu.kanade.presentation.more.settings.screen

import eu.kanade.domain.connections.service.DiscordSettingKeys
import eu.kanade.presentation.more.settings.PreferenceDependency
import eu.kanade.presentation.more.settings.framework.SettingDefinition
import eu.kanade.presentation.more.settings.framework.switchSetting
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.SettingHost
import tachiyomi.core.common.preference.SettingsRegistry
import tachiyomi.i18n.kmk.KMR

/**
 * Framework host for the Discord RPC settings, mirroring the `WebhookSettingsHost` pilot.
 *
 * Each entry is one full setting: storage key + default ([DiscordSettingKeys]), title, and
 * row type. Screens render them with `toItems(store)` instead of hand-building
 * `PreferenceItem` instances.
 *
 * Non-standard rows (status int-list, custom-message dialog, accounts navigation, async
 * category multi-select, logout) stay hand-built in [SettingsDiscordScreen] as documented
 * escape hatches. Rows gated on another switch are functions taking the source
 * [Preference]: [PreferenceDependency] needs a live preference instance, which a static
 * definition cannot provide.
 */
object DiscordSettingsHost : SettingHost {
    override val settingKeys = DiscordSettingKeys.all

    init {
        SettingsRegistry.register(this)
    }

    val enableSwitch: SettingDefinition<Boolean> = switchSetting(
        key = DiscordSettingKeys.ENABLE,
        titleRes = KMR.strings.pref_enable_discord_rpc,
    )

    val incognitoSwitch: SettingDefinition<Boolean> = switchSetting(
        key = DiscordSettingKeys.INCOGNITO,
        titleRes = KMR.strings.pref_discord_incognito,
        subtitleRes = KMR.strings.pref_discord_incognito_summary,
    )

    val progressSwitch: SettingDefinition<Boolean> = switchSetting(
        key = DiscordSettingKeys.SHOW_PROGRESS,
        titleRes = KMR.strings.pref_discord_show_progress,
        subtitleRes = KMR.strings.pref_discord_show_progress_summary,
    )

    // KMK -->
    fun pageProgressSwitch(showProgress: Preference<Boolean>): SettingDefinition<Boolean> = switchSetting(
        key = DiscordSettingKeys.SHOW_PAGE_PROGRESS,
        titleRes = KMR.strings.pref_discord_show_page_progress,
        subtitleRes = KMR.strings.pref_discord_show_page_progress_summary,
        dependsOn = PreferenceDependency(showProgress),
    )
    // KMK <--

    fun chapterTitlesSwitch(showProgress: Preference<Boolean>): SettingDefinition<Boolean> = switchSetting(
        key = DiscordSettingKeys.USE_CHAPTER_TITLES,
        titleRes = KMR.strings.show_chapters_titles_title,
        subtitleRes = KMR.strings.show_chapters_titles_subtitle,
        dependsOn = PreferenceDependency(showProgress),
    )

    val timestampSwitch: SettingDefinition<Boolean> = switchSetting(
        key = DiscordSettingKeys.SHOW_TIMESTAMP,
        titleRes = KMR.strings.pref_discord_show_timestamp,
        subtitleRes = KMR.strings.pref_discord_show_timestamp_summary,
    )

    val buttonsSwitch: SettingDefinition<Boolean> = switchSetting(
        key = DiscordSettingKeys.SHOW_BUTTONS,
        titleRes = KMR.strings.pref_discord_show_buttons,
        subtitleRes = KMR.strings.pref_discord_show_buttons_summary,
    )

    fun downloadButtonSwitch(showButtons: Preference<Boolean>): SettingDefinition<Boolean> = switchSetting(
        key = DiscordSettingKeys.SHOW_DOWNLOAD_BUTTON,
        titleRes = KMR.strings.pref_discord_show_download_button,
        subtitleRes = KMR.strings.pref_discord_show_download_button_summary,
        dependsOn = PreferenceDependency(showButtons),
    )

    fun discordButtonSwitch(showButtons: Preference<Boolean>): SettingDefinition<Boolean> = switchSetting(
        key = DiscordSettingKeys.SHOW_DISCORD_BUTTON,
        titleRes = KMR.strings.pref_discord_show_discord_button,
        subtitleRes = KMR.strings.pref_discord_show_discord_button_summary,
        dependsOn = PreferenceDependency(showButtons),
    )
}
// <-- AM (DISCORD)
