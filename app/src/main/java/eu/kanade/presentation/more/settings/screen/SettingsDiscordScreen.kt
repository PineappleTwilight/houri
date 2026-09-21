// AM (DISCORD) -->
package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.presentation.category.hierarchicalVisualName
import eu.kanade.presentation.category.sortedByHierarchy
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceDependency
import eu.kanade.presentation.more.settings.framework.toItems
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

object SettingsDiscordScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsDiscordScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_category_connections

    @Composable
    override fun RowScope.AppBarAction() {
        val uriHandler = LocalUriHandler.current
        IconButton(onClick = { uriHandler.openUri("https://tachiyomi.org/help/guides/tracking/") }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val navigator = LocalNavigator.currentOrThrow
        val connectionsPreferences = remember { globalAppGraph.connectionsPreferences }
        val connectionsManager = remember { globalAppGraph.connectionsManager }
        val store = remember { globalAppGraph.preferenceStore }
        val enableDRPCPref = connectionsPreferences.enableDiscordRPC()
        val showProgressPref = connectionsPreferences.discordShowProgress()
        val showButtonsPref = connectionsPreferences.discordShowButtons()
        val customMessagePref = connectionsPreferences.discordCustomMessage()

        var dialog by remember { mutableStateOf<Any?>(null) }
        dialog?.run {
            when (this) {
                is LogoutConnectionDialog -> {
                    ConnectionsLogoutDialog(
                        // KMK -->
                        serviceName = stringResource(service.nameStrRes()),
                        onConfirmation = {
                            enableDRPCPref.set(false)
                            service.logout()
                            navigator.pop()
                        },
                        // KMK <--
                        onDismissRequest = {
                            dialog = null
                        },
                    )
                }
            }
        }

        var showCustomMessageDialog by rememberSaveable { mutableStateOf(false) }
        var tempCustomMessage by rememberSaveable { mutableStateOf(customMessagePref.get()) }

        if (showCustomMessageDialog) {
            AlertDialog(
                onDismissRequest = {
                    showCustomMessageDialog = false
                    tempCustomMessage = customMessagePref.get()
                },
                title = { Text(stringResource(KMR.strings.pref_discord_custom_message)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = tempCustomMessage,
                            onValueChange = { tempCustomMessage = it },
                            label = { Text(stringResource(KMR.strings.pref_discord_custom_message_summary)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        TextButton(
                            onClick = {
                                customMessagePref.delete()
                                tempCustomMessage = ""
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(MR.strings.action_reset))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            customMessagePref.set(tempCustomMessage)
                            showCustomMessageDialog = false
                        },
                    ) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCustomMessageDialog = false
                            tempCustomMessage = customMessagePref.get()
                        },
                    ) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KMR.strings.discord_accounts),
                onClick = { navigator.push(DiscordAccountsScreen) },
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.connections_discord),
                preferenceItems = persistentListOf(
                    *listOf(DiscordSettingsHost.enableSwitch).toItems(store).toTypedArray(),
                    Preference.PreferenceItem.ListPreference(
                        preference = connectionsPreferences.discordRPCStatus(),
                        title = stringResource(KMR.strings.pref_discord_status),
                        entries = persistentMapOf(
                            -1 to stringResource(KMR.strings.pref_discord_dnd),
                            0 to stringResource(KMR.strings.pref_discord_idle),
                            1 to stringResource(KMR.strings.pref_discord_online),
                        ),
                        dependsOn = PreferenceDependency(enableDRPCPref),
                    ),
                ),
            ),
            getRPCIncognitoGroup(
                connectionsPreferences = connectionsPreferences,
                store = store,
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_category_discord_customization),
                dependsOn = PreferenceDependency(enableDRPCPref),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_discord_custom_message),
                        subtitle = stringResource(KMR.strings.pref_discord_custom_message_summary),
                        onClick = { showCustomMessageDialog = true },
                    ),
                    *listOf(
                        DiscordSettingsHost.progressSwitch,
                        // KMK -->
                        DiscordSettingsHost.pageProgressSwitch(showProgressPref),
                        // KMK <--
                        DiscordSettingsHost.chapterTitlesSwitch(showProgressPref),
                        DiscordSettingsHost.timestampSwitch,
                        DiscordSettingsHost.buttonsSwitch,
                        DiscordSettingsHost.downloadButtonSwitch(showButtonsPref),
                        DiscordSettingsHost.discordButtonSwitch(showButtonsPref),
                    ).toItems(store).toTypedArray(),
                ),
            ),
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(MR.strings.logout),
                content = {
                    TextPreferenceWidget(
                        modifier = Modifier
                            .padding(horizontal = MaterialTheme.padding.large),
                        title = stringResource(MR.strings.logout),
                        icon = Icons.AutoMirrored.Filled.Logout,
                        iconTint = MaterialTheme.colorScheme.error,
                        onPreferenceClick = {
                            dialog = LogoutConnectionDialog(connectionsManager.discord)
                        },
                    )
                },
            ),
        )
    }

    @Composable
    private fun getRPCIncognitoGroup(
        connectionsPreferences: ConnectionsPreferences,
        store: PreferenceStore,
    ): Preference.PreferenceGroup {
        val getCategories = remember { globalAppGraph.getCategories }
        val allCategories by getCategories.subscribe().collectAsState(initial = emptyList())

        val discordRPCIncognitoCategoriesPref = connectionsPreferences.discordRPCIncognitoCategories()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.categories),
            preferenceItems = persistentListOf(
                *listOf(DiscordSettingsHost.incognitoSwitch).toItems(store).toTypedArray(),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = discordRPCIncognitoCategoriesPref,
                    // KMK -->
                    entries = allCategories
                        .sortedByHierarchy()
                        .associate { it.id.toString() to it.hierarchicalVisualName }
                        .toImmutableMap(),
                    // KMK <--
                    title = stringResource(MR.strings.categories),
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(KMR.strings.pref_discord_incognito_categories_details),
                ),
            ),
            dependsOn = PreferenceDependency(connectionsPreferences.enableDiscordRPC()),
        )
    }
}
// <-- AM (DISCORD)
