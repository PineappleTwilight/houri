// AM (CONNECTIONS) -->
package eu.kanade.domain.connections.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.connections.ConnectionsService
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class ConnectionsPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun connectionsUsername(sync: ConnectionsService) = preferenceStore.getString(
        connectionsUsername(sync.id),
        "",
    )

    fun connectionsPassword(sync: ConnectionsService) = preferenceStore.getString(
        connectionsPassword(sync.id),
        "",
    )

    fun setConnectionsCredentials(sync: ConnectionsService, username: String, password: String) {
        connectionsUsername(sync).set(username)
        connectionsPassword(sync).set(password)
    }

    fun connectionsToken(sync: ConnectionsService) = preferenceStore.getString(
        Preference.privateKey(connectionsToken(sync.id)),
        "",
    )

    fun enableDiscordRPC() =
        preferenceStore.getBoolean(DiscordSettingKeys.ENABLE.key, DiscordSettingKeys.ENABLE.default)

    fun discordRPCStatus() =
        preferenceStore.getInt(DiscordSettingKeys.STATUS.key, DiscordSettingKeys.STATUS.default)

    fun discordRPCIncognito() =
        preferenceStore.getBoolean(DiscordSettingKeys.INCOGNITO.key, DiscordSettingKeys.INCOGNITO.default)

    fun discordRPCIncognitoCategories() = preferenceStore.getStringSet(
        DiscordSettingKeys.INCOGNITO_CATEGORIES.key,
        DiscordSettingKeys.INCOGNITO_CATEGORIES.default,
    )

    fun useChapterTitles() =
        preferenceStore.getBoolean(DiscordSettingKeys.USE_CHAPTER_TITLES.key, DiscordSettingKeys.USE_CHAPTER_TITLES.default)

    fun discordCustomMessage() =
        preferenceStore.getString(DiscordSettingKeys.CUSTOM_MESSAGE.key, DiscordSettingKeys.CUSTOM_MESSAGE.default)

    fun discordShowProgress() =
        preferenceStore.getBoolean(DiscordSettingKeys.SHOW_PROGRESS.key, DiscordSettingKeys.SHOW_PROGRESS.default)

    // KMK -->
    fun discordShowPageProgress() = preferenceStore.getBoolean(
        DiscordSettingKeys.SHOW_PAGE_PROGRESS.key,
        DiscordSettingKeys.SHOW_PAGE_PROGRESS.default,
    )
    // KMK <--

    fun discordShowTimestamp() =
        preferenceStore.getBoolean(DiscordSettingKeys.SHOW_TIMESTAMP.key, DiscordSettingKeys.SHOW_TIMESTAMP.default)

    fun discordShowButtons() =
        preferenceStore.getBoolean(DiscordSettingKeys.SHOW_BUTTONS.key, DiscordSettingKeys.SHOW_BUTTONS.default)

    fun discordShowDownloadButton() = preferenceStore.getBoolean(
        DiscordSettingKeys.SHOW_DOWNLOAD_BUTTON.key,
        DiscordSettingKeys.SHOW_DOWNLOAD_BUTTON.default,
    )

    fun discordShowDiscordButton() = preferenceStore.getBoolean(
        DiscordSettingKeys.SHOW_DISCORD_BUTTON.key,
        DiscordSettingKeys.SHOW_DISCORD_BUTTON.default,
    )

    fun discordAccounts() =
        preferenceStore.getString(DiscordSettingKeys.ACCOUNTS.key, DiscordSettingKeys.ACCOUNTS.default)

    companion object {

        fun connectionsUsername(syncId: Long) = "pref_connections_username_$syncId"

        private fun connectionsPassword(syncId: Long) = "pref_connections_password_$syncId"

        private fun connectionsToken(syncId: Long) = "connection_token_$syncId"
    }
}
// <-- AM (CONNECTIONS)
