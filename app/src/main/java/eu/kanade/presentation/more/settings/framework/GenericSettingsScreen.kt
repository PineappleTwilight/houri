package eu.kanade.presentation.more.settings.framework

// KMK -->
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.util.Screen
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Base class for settings screens rendered from [SettingDefinition] lists.
 *
 * Subclasses declare their groups by mixing framework-driven items ([List.toItems]) with
 * hand-built items for non-standard rows (navigation, async data, login-gated widgets):
 *
 * ```
 * object SettingsExampleScreen : GenericSettingsScreen(KMR.strings.example_title) {
 *     @Composable
 *     override fun preferenceGroups(store: PreferenceStore): List<Preference> {
 *         val enabled by store.rememberEnabled(ExampleKeys.ENABLED)
 *         return listOf(
 *             Preference.PreferenceGroup(
 *                 title = stringResource(KMR.strings.example_group),
 *                 preferenceItems = ExampleHost.mainItems.toItems(store) { enabled } +
 *                     Preference.PreferenceItem.TextPreference(...custom...),
 *             ),
 *         )
 *     }
 * }
 * ```
 *
 * The screen stays a [SearchableSettings][eu.kanade.presentation.more.settings.screen.SearchableSettings]
 * implementation, so search indexing and highlight navigation keep working unchanged.
 */
abstract class GenericSettingsScreen(
    private val titleRes: StringResource,
) : Screen(),
    eu.kanade.presentation.more.settings.screen.SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = titleRes

    @Composable
    protected abstract fun preferenceGroups(store: PreferenceStore): List<Preference>

    @Composable
    override fun getPreferences(): List<Preference> {
        val store = remember { globalAppGraph.preferenceStore }
        return preferenceGroups(store)
    }
}
// KMK <--
