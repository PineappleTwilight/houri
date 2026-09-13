package eu.kanade.presentation.more.settings.framework

// KMK -->
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.PreferenceItem
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Renders a single [SettingDefinition] through the existing [PreferenceItem] dispatcher.
 *
 * This is the automatic UI wiring: the caller supplies only the definition and the store,
 * and the row reads, displays, validates, and persists itself like any hand-built item.
 *
 * @param enabled enables gating rows behind another setting (e.g. a master switch).
 * @param highlightKey forwarded to the dispatcher for search-navigation highlighting.
 */
@Composable
fun <T> SettingRow(
    definition: SettingDefinition<T>,
    store: PreferenceStore,
    enabled: Boolean = true,
    highlightKey: String? = null,
) {
    val preference = remember(definition.key.key) { definition.bind(store, definition.key) }
    PreferenceItem(
        item = definition.makeItem(preference, enabled),
        highlightKey = highlightKey,
    )
}
// KMK <--
