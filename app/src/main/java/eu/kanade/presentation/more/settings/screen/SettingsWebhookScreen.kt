// AM (CONNECTIONS) -->
package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.framework.toItems
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

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
        val store = remember { globalAppGraph.preferenceStore }

        // KMK --> rows stay visible regardless of the master switch: StatusWrapper
        // hides (not greys) disabled rows, which previously left this page empty
        // with no way to turn webhooks on. The master switch remains the
        // functional gate inside WebhookNotifier.notify().

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
                    *WebhookSettingsHost.connectionItems.toItems(store).toTypedArray(),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_webhook_test),
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
                preferenceItems = WebhookSettingsHost.eventSwitches.toItems(store),
            ),
            // KMK -->
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_webhook_excluded_categories),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = webhookPreferences.excludedCategories(),
                        entries = categoryEntries,
                        title = stringResource(KMR.strings.pref_webhook_excluded_categories),
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
