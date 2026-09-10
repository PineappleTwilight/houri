package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsUpscalerScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_upscale_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { globalAppGraph.upscalePreferences }
        val enabled by prefs.enabled().collectAsState()
        val preset by prefs.preset().collectAsState()
        val backend by prefs.backend().collectAsState()
        val model by prefs.model().collectAsState()
        val factor by prefs.upscaleFactor().collectAsState()
        val cacheEnabled by prefs.cacheEnabled().collectAsState()

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_upscale_title),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.enabled(),
                        title = stringResource(KMR.strings.pref_upscale_enabled),
                        subtitle = if (enabled) stringResource(KMR.strings.pref_upscale_enabled_summary) else stringResource(KMR.strings.pref_upscale_disabled_summary),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.preset(),
                        entries = kotlinx.collections.immutable.persistentMapOf(
                            "FAST" to stringResource(KMR.strings.pref_upscale_preset_fast),
                            "BALANCED" to stringResource(KMR.strings.pref_upscale_preset_balanced),
                            "HIGH" to stringResource(KMR.strings.pref_upscale_preset_high),
                        ),
                        title = stringResource(KMR.strings.pref_upscale_preset),
                        subtitle = preset,
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.backend(),
                        entries = kotlinx.collections.immutable.persistentMapOf(
                            "AUTO" to "Auto",
                            "VULKAN" to "Vulkan",
                            "NPU" to "NPU",
                            "CPU" to "CPU",
                        ),
                        title = stringResource(KMR.strings.pref_upscale_backend),
                        subtitle = backend,
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.model(),
                        entries = kotlinx.collections.immutable.persistentMapOf(
                            "REAL_CUGAN" to "Real-CUGAN",
                            "REAL_ESRGAN" to "Real-ESRGAN",
                            "WAIFU2X" to "Waifu2x",
                        ),
                        title = stringResource(KMR.strings.pref_upscale_model),
                        subtitle = model,
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = (factor * 10).toInt().coerceIn(10, 40),
                        title = stringResource(KMR.strings.pref_upscale_factor),
                        subtitle = "${factor}x",
                        valueString = String.format("%.1fx", factor),
                        valueRange = 10..40,
                        steps = 30,
                        enabled = enabled,
                        onValueChanged = { v -> prefs.upscaleFactor().set(v / 10f) },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.cacheEnabled(),
                        title = stringResource(KMR.strings.pref_upscale_cache),
                        subtitle = stringResource(KMR.strings.pref_upscale_cache_summary),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_upscale_clear_cache),
                        subtitle = stringResource(KMR.strings.pref_upscale_clear_cache_summary),
                        onClick = { globalAppGraph.upscaleEngine.clearCache() },
                        enabled = enabled,
                    ),
                ),
            ),
        )
    }
}
