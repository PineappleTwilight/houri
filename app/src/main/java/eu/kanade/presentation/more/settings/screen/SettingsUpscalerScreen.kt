package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
        val mode by prefs.mode().collectAsState()
        val simpleAlgo by prefs.simpleAlgo().collectAsState()
        val factor by prefs.upscaleFactor().collectAsState()
        val cacheEnabled by prefs.cacheEnabled().collectAsState()
        val isNomtl = eu.kanade.tachiyomi.BuildConfig.IS_NOMTL
        val isSimple = isNomtl || mode == "SIMPLE"

        val items = buildList {
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.enabled(),
                    title = stringResource(KMR.strings.pref_upscale_enabled),
                    subtitle = if (enabled) stringResource(KMR.strings.pref_upscale_enabled_summary) else stringResource(KMR.strings.pref_upscale_disabled_summary),
                ),
            )
            if (!isNomtl) {
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.mode(),
                        entries = kotlinx.collections.immutable.persistentMapOf(
                            "NATIVE" to "Native (Real-CUGAN / ESRGAN)",
                            "SIMPLE" to "Simple (Bicubic / Bilinear)",
                        ),
                        title = "Upscale mode",
                        subtitle = mode,
                        enabled = enabled,
                    ),
                )
            }
            if (isSimple) {
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.simpleAlgo(),
                        entries = kotlinx.collections.immutable.persistentMapOf(
                            "BICUBIC" to "Bicubic",
                            "BILINEAR" to "Bilinear",
                            "NEAREST" to "Nearest Neighbor",
                        ),
                        title = "Simple algorithm",
                        subtitle = simpleAlgo,
                        enabled = enabled,
                    ),
                )
            }
            if (!isSimple) {
                add(
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
                )
                add(
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
                )
                add(
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
                )
            }
            add(
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
            )
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.cacheEnabled(),
                    title = stringResource(KMR.strings.pref_upscale_cache),
                    subtitle = stringResource(KMR.strings.pref_upscale_cache_summary),
                    enabled = enabled,
                ),
            )
            add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_upscale_clear_cache),
                    subtitle = stringResource(KMR.strings.pref_upscale_clear_cache_summary),
                    onClick = { globalAppGraph.upscaleEngine.clearCache() },
                    enabled = enabled,
                ),
            )
        }
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_upscale_title),
                preferenceItems = items.toImmutableList(),
            ),
        )
    }
}
