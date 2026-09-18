package eu.kanade.presentation.more.settings

// KMK -->
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.core.common.preference.Preference as PreferenceData

/**
 * Declares that a [Preference] is only visible while another component holds a given state.
 *
 * @param source the backing preference of the component this row depends on.
 * @param expected the value [source] must hold for the dependent row to be visible.
 * Defaults to `true`, i.e. "visible while the source switch is on".
 * @param invert when true, the row is visible while [source] holds anything *except*
 * [expected].
 */
data class PreferenceDependency(
    val source: PreferenceData<*>,
    val expected: Any? = true,
    val invert: Boolean = false,
)

@Composable
fun PreferenceDependency.isSatisfied(): Boolean {
    @Suppress("UNCHECKED_CAST")
    val current by (source as PreferenceData<Any?>).collectAsState()
    val matches = current == expected
    return if (invert) !matches else matches
}

/**
 * Whether this [Preference] passes all of its gates ([enabled], [dependsOn], [mtlOnly],
 * [ramGated]). A failing row is either removed or, when [Preference.grayOut] is set, shown
 * dimmed and non-interactive.
 */
@Composable
fun Preference.gatesPassed(): Boolean {
    if (!enabled) return false
    dependsOn?.let { if (!it.isSatisfied()) return false }
    // DeviceMemory ships with the same FQN in both the engine and the stub modules,
    // so this resolves in either flavor.
    if (mtlOnly && BuildConfig.IS_NOMTL) return false
    if (ramGated && !exh.yakuyomi.DeviceMemory.isMtlSupported(LocalContext.current)) return false
    return true
}

/**
 * Whether this [Preference] may be shown at all. Rows that fail this check are removed from
 * the screen, the group and the settings-search index — unless [Preference.grayOut] opts
 * them into staying visible while dimmed (see [isInteractive]).
 */
@Composable
fun Preference.isVisible(): Boolean {
    return gatesPassed() || grayOut
}

/**
 * Whether this [Preference] accepts input. Always true for rows without gates; false for
 * rows that fail a gate (whether hidden or greyed out via [Preference.grayOut]).
 */
@Composable
fun Preference.isInteractive(): Boolean {
    return gatesPassed()
}

/**
 * Drops invisible rows (and groups left empty by that) from a screen's preference list.
 * Call from `@Composable getPreferences()` call sites that feed both [PreferenceScreen] and
 * the settings-search index so both agree on what exists.
 */
@Composable
fun List<Preference>.filterVisible(): List<Preference> {
    return mapNotNull { preference ->
        when (preference) {
            is Preference.PreferenceGroup -> {
                if (!preference.isVisible()) {
                    null
                } else {
                    val items = preference.preferenceItems.filter { it.isVisible() }
                    if (items.isEmpty()) {
                        null
                    } else {
                        preference.copy(preferenceItems = items.toImmutableList())
                    }
                }
            }
            is Preference.PreferenceItem<*, *> -> preference.takeIf { it.isVisible() }
        }
    }
}
// KMK <--
