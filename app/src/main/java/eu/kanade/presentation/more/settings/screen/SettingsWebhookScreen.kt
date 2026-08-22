// AM (CONNECTIONS) -->
package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsWebhookScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsWebhookScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.webhook_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val webhookPreferences = remember { globalAppGraph.webhookPreferences }
        val webhookNotifier = remember { globalAppGraph.webhookNotifier }

        val enabled by webhookPreferences.enabled().collectAsState()

        // KMK -->
        val categories = remember { mutableStateOf<List<Category>>(emptyList()) }
        LaunchedEffect(Unit) {
            categories.value = globalAppGraph.getCategories.await()
                .filterNot(Category::isSystemCategory)
        }
        val categoryEntries = remember(categories.value) {
            buildList {
                var parentName = ""
                categories.value.sortedWith(compareBy({ it.parentId }, { it.order })).forEach { category ->
                    if (category.parentId == 0L) {
                        add(category.id.toString() to category.name)
                        parentName = category.name
                    } else {
                        add(category.id.toString() to "$parentName / ${category.name}")
                    }
                }
            }.toMap().toImmutableMap()
        }
        // KMK <--

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_category_connections),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.enabled(),
                        title = stringResource(KMR.strings.pref_webhook_enabled),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = webhookPreferences.discordWebhookUrl(),
                        title = stringResource(KMR.strings.pref_webhook_discord_url),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = webhookPreferences.genericWebhookUrl(),
                        title = stringResource(KMR.strings.pref_webhook_generic_url),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_webhook_test),
                        enabled = enabled,
                        onClick = {
                            scope.launchUI {
                                webhookNotifier.sendTest()
                                context.toast(KMR.strings.webhook_test_sent)
                            }
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(
                        stringResource(KMR.strings.webhook_info),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.webhook_events),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnChapterStarted(),
                        title = stringResource(KMR.strings.pref_webhook_chapter_started),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnChapterRead(),
                        title = stringResource(KMR.strings.pref_webhook_chapter_read),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.includeReadingTime(),
                        title = stringResource(KMR.strings.pref_webhook_include_reading_time),
                        subtitle = stringResource(KMR.strings.pref_webhook_include_reading_time_summary),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnNewMangaStarted(),
                        title = stringResource(KMR.strings.pref_webhook_new_manga_started),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnMangaFinished(),
                        title = stringResource(KMR.strings.pref_webhook_manga_finished),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnLibraryUpdate(),
                        title = stringResource(KMR.strings.pref_webhook_library_update),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnBackupCreated(),
                        title = stringResource(KMR.strings.pref_webhook_backup_created),
                        enabled = enabled,
                    ),
                    // KMK -->
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnMangaAdded(),
                        title = stringResource(KMR.strings.pref_webhook_manga_added),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnMangaRemoved(),
                        title = stringResource(KMR.strings.pref_webhook_manga_removed),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnDownloadsFinished(),
                        title = stringResource(KMR.strings.pref_webhook_downloads_finished),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnBackupRestored(),
                        title = stringResource(KMR.strings.pref_webhook_backup_restored),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnMangaMigrated(),
                        title = stringResource(KMR.strings.pref_webhook_manga_migrated),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = webhookPreferences.notifyOnAppUpdated(),
                        title = stringResource(KMR.strings.pref_webhook_app_updated),
                        enabled = enabled,
                    ),
                    // KMK <--
                ),
            ),
            // KMK -->
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_webhook_excluded_categories),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = webhookPreferences.excludedCategories(),
                        entries = categoryEntries,
                        title = stringResource(KMR.strings.pref_webhook_excluded_categories),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.InfoPreference(
                        stringResource(KMR.strings.pref_webhook_excluded_categories_summary),
                    ),
                ),
            ),
            // KMK <--
        )
    }
}
// <-- AM (CONNECTIONS)
