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
import eu.kanade.domain.connections.service.WebhookSettingKeys
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
        val store = remember { globalAppGraph.preferenceStore }

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
                    // KMK --> master switch stays enabled so webhooks can be turned on;
                    // only the URL fields gate on it.
                    *WebhookSettingsHost.connectionItems.toItems(store) { def ->
                        def.key.key == WebhookSettingKeys.ENABLED.key || enabled
                    }.toTypedArray(),
                    // KMK <--
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
                preferenceItems = WebhookSettingsHost.eventSwitches.toItems(store) { enabled },
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
