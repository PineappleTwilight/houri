package eu.kanade.presentation.more.settings.framework

// KMK -->
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceDependency
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.SettingKey
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.core.common.preference.Preference as PreferenceData

/**
 * Modular settings framework — UI wiring.
 *
 * A [SettingDefinition] binds one [SettingKey] to its rendered row: how to read/write the
 * value from a [PreferenceStore] ([bind]) and how to build the [Preference.PreferenceItem]
 * for it ([makeItem]). The existing `PreferenceItem` dispatcher stays the single renderer,
 * so framework rows look and behave exactly like hand-built ones.
 *
 * Adding a new setting is one declaration plus one host entry, e.g.:
 *
 * ```
 * val MY_SWITCH = switchSetting(
 *     key = MyKeys.MY_SWITCH,
 *     titleRes = KMR.strings.pref_my_switch,
 * )
 * ```
 *
 * then `MyHost.definitions += MY_SWITCH` (or include it in the host's list) and render it
 * with [SettingRow] or [List.toItems] inside a [Preference.PreferenceGroup].
 */
class SettingDefinition<T>(
    val key: SettingKey<T>,
    val titleRes: StringResource,
    val subtitleRes: StringResource? = null,
    val bind: PreferenceStore.(SettingKey<T>) -> PreferenceData<T>,
    val makeItem: @Composable (PreferenceData<T>, Boolean) -> Preference.PreferenceItem<out Any, out Any>,
    // KMK --> visibility gates threaded into the built item (see Preference.isVisible).
    val dependsOn: PreferenceDependency? = null,
    val mtlOnly: Boolean = false,
    val ramGated: Boolean = false,
    val grayOut: Boolean = false,
    // KMK <--
)

/**
 * Builds the [Preference.PreferenceItem] for every definition in this list, preserving order.
 *
 * @param enabledGate per-definition enablement (e.g. a master switch); defaults to always enabled.
 */
@Composable
fun List<SettingDefinition<*>>.toItems(
    store: PreferenceStore,
    enabledGate: (SettingDefinition<*>) -> Boolean = { true },
): ImmutableList<Preference.PreferenceItem<out Any, out Any>> {
    // NOTE: plain for-loop, not map{} — @Composable makeItem calls are only legal
    // directly in a composable body, not inside a non-composable lambda.
    val items = ArrayList<Preference.PreferenceItem<out Any, out Any>>(size)
    for (definition in this) {
        @Suppress("UNCHECKED_CAST")
        val bound = definition as SettingDefinition<Any?>
        val preference = bound.bind(store, bound.key)
        items.add(bound.makeItem(preference, enabledGate(definition)))
    }
    return persistentListOf(*items.toTypedArray())
}

fun switchSetting(
    key: SettingKey<Boolean>,
    titleRes: StringResource,
    subtitleRes: StringResource? = null,
    // KMK -->
    dependsOn: PreferenceDependency? = null,
    mtlOnly: Boolean = false,
    ramGated: Boolean = false,
    grayOut: Boolean = false,
    // KMK <--
): SettingDefinition<Boolean> {
    return SettingDefinition(
        key = key,
        titleRes = titleRes,
        subtitleRes = subtitleRes,
        bind = { settingKey -> getBoolean(settingKey.key, settingKey.default) },
        makeItem = { preference, enabled ->
            Preference.PreferenceItem.SwitchPreference(
                preference = preference,
                title = stringResource(titleRes),
                subtitle = subtitleRes?.let { stringResource(it) },
                enabled = enabled,
                // KMK -->
                dependsOn = dependsOn,
                mtlOnly = mtlOnly,
                ramGated = ramGated,
                grayOut = grayOut,
                // KMK <--
            )
        },
        dependsOn = dependsOn,
        mtlOnly = mtlOnly,
        ramGated = ramGated,
        grayOut = grayOut,
    )
}

fun editTextSetting(
    key: SettingKey<String>,
    titleRes: StringResource,
    subtitleRes: StringResource? = null,
    validator: (String) -> Boolean = { it.isNotBlank() },
    // KMK -->
    dependsOn: PreferenceDependency? = null,
    mtlOnly: Boolean = false,
    ramGated: Boolean = false,
    grayOut: Boolean = false,
    // KMK <--
): SettingDefinition<String> {
    return SettingDefinition(
        key = key,
        titleRes = titleRes,
        subtitleRes = subtitleRes,
        bind = { settingKey -> getString(settingKey.key, settingKey.default) },
        makeItem = { preference, enabled ->
            Preference.PreferenceItem.EditTextPreference(
                preference = preference,
                title = stringResource(titleRes),
                // Match the hand-built default: "%s" previews the current value.
                subtitle = subtitleRes?.let { stringResource(it) } ?: "%s",
                enabled = enabled,
                validator = validator,
                // KMK -->
                dependsOn = dependsOn,
                mtlOnly = mtlOnly,
                ramGated = ramGated,
                grayOut = grayOut,
                // KMK <--
            )
        },
        dependsOn = dependsOn,
        mtlOnly = mtlOnly,
        ramGated = ramGated,
        grayOut = grayOut,
    )
}

fun intSliderSetting(
    key: SettingKey<Int>,
    titleRes: StringResource,
    subtitleRes: StringResource? = null,
    valueRange: IntProgression,
    valueFormat: @Composable (Int) -> String = { it.toString() },
    // KMK -->
    dependsOn: PreferenceDependency? = null,
    mtlOnly: Boolean = false,
    ramGated: Boolean = false,
    grayOut: Boolean = false,
    // KMK <--
): SettingDefinition<Int> {
    return SettingDefinition(
        key = key,
        titleRes = titleRes,
        subtitleRes = subtitleRes,
        bind = { settingKey -> getInt(settingKey.key, settingKey.default) },
        makeItem = { preference, enabled ->
            // Store-backed slider: unlike hand-built SliderPreference rows that keep local
            // state, the value is collected from the store and written back on change.
            val current by preference.collectAsState()
            Preference.PreferenceItem.SliderPreference(
                value = current,
                title = stringResource(titleRes),
                subtitle = subtitleRes?.let { stringResource(it) },
                valueString = valueFormat(current),
                valueRange = valueRange,
                enabled = enabled,
                onValueChanged = { preference.set(it) },
                // KMK -->
                dependsOn = dependsOn,
                mtlOnly = mtlOnly,
                ramGated = ramGated,
                grayOut = grayOut,
                // KMK <--
            )
        },
        dependsOn = dependsOn,
        mtlOnly = mtlOnly,
        ramGated = ramGated,
        grayOut = grayOut,
    )
}

fun multiSelectSetting(
    key: SettingKey<Set<String>>,
    titleRes: StringResource,
    subtitleRes: StringResource? = null,
    entries: @Composable () -> ImmutableMap<String, String>,
    // KMK -->
    dependsOn: PreferenceDependency? = null,
    mtlOnly: Boolean = false,
    ramGated: Boolean = false,
    grayOut: Boolean = false,
    // KMK <--
): SettingDefinition<Set<String>> {
    return SettingDefinition(
        key = key,
        titleRes = titleRes,
        subtitleRes = subtitleRes,
        bind = { settingKey -> getStringSet(settingKey.key, settingKey.default) },
        makeItem = { preference, enabled ->
            Preference.PreferenceItem.MultiSelectListPreference(
                preference = preference,
                entries = entries(),
                title = stringResource(titleRes),
                // Match the hand-built default: "%s" previews the current selection.
                subtitle = subtitleRes?.let { stringResource(it) } ?: "%s",
                enabled = enabled,
                // KMK -->
                dependsOn = dependsOn,
                mtlOnly = mtlOnly,
                ramGated = ramGated,
                grayOut = grayOut,
                // KMK <--
            )
        },
        dependsOn = dependsOn,
        mtlOnly = mtlOnly,
        ramGated = ramGated,
        grayOut = grayOut,
    )
}

/**
 * Store-backed list setting for enum-style values serialized by name.
 *
 * @param bind custom binding for non-trivial serialization (objects, int-mapped enums, …).
 */
fun <T : Any> listSetting(
    key: SettingKey<T>,
    titleRes: StringResource,
    subtitleRes: StringResource? = null,
    entries: @Composable () -> ImmutableMap<T, String>,
    bind: PreferenceStore.(SettingKey<T>) -> PreferenceData<T>,
    // KMK -->
    dependsOn: PreferenceDependency? = null,
    mtlOnly: Boolean = false,
    ramGated: Boolean = false,
    grayOut: Boolean = false,
    // KMK <--
): SettingDefinition<T> {
    return SettingDefinition(
        key = key,
        titleRes = titleRes,
        subtitleRes = subtitleRes,
        bind = bind,
        makeItem = { preference, enabled ->
            Preference.PreferenceItem.ListPreference(
                preference = preference,
                entries = entries(),
                title = stringResource(titleRes),
                // Match the hand-built default: "%s" previews the current entry.
                subtitle = subtitleRes?.let { stringResource(it) } ?: "%s",
                enabled = enabled,
                // KMK -->
                dependsOn = dependsOn,
                mtlOnly = mtlOnly,
                ramGated = ramGated,
                grayOut = grayOut,
                // KMK <--
            )
        },
        dependsOn = dependsOn,
        mtlOnly = mtlOnly,
        ramGated = ramGated,
        grayOut = grayOut,
    )
}
// KMK <--
