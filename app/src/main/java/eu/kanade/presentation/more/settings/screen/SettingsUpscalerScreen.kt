package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsUpscalerScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_upscale_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { globalAppGraph.upscalePreferences }
        val engine = remember { globalAppGraph.upscaleEngine }
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
        val isMtlEnabled = remember(enabled, isSimple) {
            try {
                prefs.isMtlEnabled()
            } catch (_: Exception) {
                false
            }
        }
        val effBackend = remember(backend, isSimple) {
            try {
                engine.effectiveBackend().name
            } catch (_: Exception) {
                backend
            }
        }
        val cacheBytes = remember(cacheEnabled, enabled) {
            try {
                engine.cacheSizeBytes()
            } catch (_: Exception) {
                0L
            }
        }
        val cacheCount = remember(cacheEnabled, enabled) {
            try {
                engine.cacheFileCount()
            } catch (_: Exception) {
                0
            }
        }
        val modelManager = remember { globalAppGraph.upscaleModelManager }
        val modelStatus by modelManager.status.collectAsState()

        val items = buildList {
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.enabled(),
                    title = stringResource(KMR.strings.pref_upscale_enabled),
                    subtitle = if (enabled) stringResource(KMR.strings.pref_upscale_enabled_summary) else stringResource(KMR.strings.pref_upscale_disabled_summary),
                ),
            )
            if (!isSimple && enabled && !isMtlEnabled) {
                add(
                    Preference.PreferenceItem.InfoPreference(
                        title = "Native upscaling requires AI Translation to be enabled (MTL gate). Switch to Simple mode or enable Translation in Settings → Translation.",
                    ),
                )
            }
            if (!isNomtl) {
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.mode(),
                        entries = kotlinx.collections.immutable.persistentMapOf(
                            "NATIVE" to "Native (Real-CUGAN / ESRGAN / Waifu2x)",
                            "SIMPLE" to "Simple (Bicubic / Bilinear / Nearest)",
                        ),
                        title = "Upscale mode",
                        subtitle = if (isSimple) "Simple — $simpleAlgo" else "Native — $model • $effBackend",
                        enabled = enabled,
                    ),
                )
            }
            if (isSimple) {
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.simpleAlgo(),
                        entries = kotlinx.collections.immutable.persistentMapOf(
                            "BICUBIC" to "Bicubic (smooth)",
                            "BILINEAR" to "Bilinear (balanced)",
                            "NEAREST" to "Nearest Neighbor (sharp/pixelated)",
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
                            "FAST" to stringResource(KMR.strings.pref_upscale_preset_fast) + " — 1.5× fixed, lowest memory",
                            "BALANCED" to stringResource(KMR.strings.pref_upscale_preset_balanced) + " — uses factor as-is",
                            "HIGH" to stringResource(KMR.strings.pref_upscale_preset_high) + " — factor ×1.2 (max 4×)",
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
                            "AUTO" to "Auto (Vulkan → NPU → CPU)",
                            "VULKAN" to "Vulkan (GPU)",
                            "NPU" to "NPU (Qualcomm/ONNX)",
                            "CPU" to "CPU (fallback)",
                        ),
                        title = stringResource(KMR.strings.pref_upscale_backend),
                        subtitle = "$backend → effective: $effBackend",
                        enabled = enabled,
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.model(),
                        entries = kotlinx.collections.immutable.persistentMapOf(
                            "REAL_CUGAN" to "Real-CUGAN (anime/manga, denoise)",
                            "REAL_ESRGAN" to "Real-ESRGAN (general, sharper)",
                            "WAIFU2X" to "Waifu2x (legacy, light)",
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
                    subtitle = if (factor < 1.02f) "${factor}x — near-identity, upscaling skipped" else "${factor}x",
                    valueString = String.format("%.1fx", factor),
                    valueRange = 10..40,
                    steps = 29,
                    enabled = enabled,
                    onValueChanged = { v -> prefs.upscaleFactor().set(v / 10f) },
                ),
            )
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.cacheEnabled(),
                    title = stringResource(KMR.strings.pref_upscale_cache),
                    subtitle = if (cacheEnabled) {
                        val mb = cacheBytes / (1024 * 1024)
                        val kb = (cacheBytes % (1024 * 1024)) / 1024
                        if (cacheBytes == 0L) {
                            stringResource(KMR.strings.pref_upscale_cache_summary)
                        } else {
                            "Cached $cacheCount files • ${mb}MB ${kb}KB / 200MB • 30-day TTL"
                        }
                    } else {
                        stringResource(KMR.strings.pref_upscale_cache_summary) + " (disabled)"
                    },
                    enabled = enabled,
                ),
            )
            add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_upscale_clear_cache),
                    subtitle = if (cacheBytes > 0) "Clear $cacheCount files (${cacheBytes / 1024} KB)" else stringResource(KMR.strings.pref_upscale_clear_cache_summary),
                    onClick = { engine.clearCache() },
                    enabled = enabled && cacheEnabled,
                ),
            )
            add(
                Preference.PreferenceItem.CustomPreference(
                    title = "Upscale models",
                    content = {
                        Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp)) {
                            when (modelStatus.state) {
                                eu.kanade.tachiyomi.ui.reader.setting.UpscaleModelManager.State.READY -> {
                                    Text(text = "Models ready — ${modelStatus.downloadedBytes / (1024 * 1024)} MB", style = MaterialTheme.typography.bodyMedium)
                                    Text(text = "Real-CUGAN / Real-ESRGAN / Waifu2x ready for Native mode", style = MaterialTheme.typography.bodySmall)
                                }
                                eu.kanade.tachiyomi.ui.reader.setting.UpscaleModelManager.State.DOWNLOADING -> {
                                    val percent = (modelStatus.progress * 100).toInt()
                                    Text(text = "Downloading $percent% — ${modelStatus.downloadedBytes / (1024 * 1024)}/${modelStatus.totalBytes / (1024 * 1024)} MB", style = MaterialTheme.typography.bodyMedium)
                                    if (!modelStatus.currentFile.isNullOrBlank()) {
                                        Text(text = "File: ${modelStatus.currentFile}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    LinearProgressIndicator(progress = { modelStatus.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                }
                                eu.kanade.tachiyomi.ui.reader.setting.UpscaleModelManager.State.ERROR -> {
                                    Text(text = modelStatus.error ?: "Download failed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                }
                                else -> {
                                    Text(text = "Models not installed — download to enable Native upscaling (Simple works without models)", style = MaterialTheme.typography.bodyMedium)
                                    Text(text = "~21 MB total (Real-CUGAN + ESRGAN + Waifu2x)", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    },
                ),
            )
            val modelActionTitle = when (modelStatus.state) {
                eu.kanade.tachiyomi.ui.reader.setting.UpscaleModelManager.State.DOWNLOADING -> "Cancel download"
                eu.kanade.tachiyomi.ui.reader.setting.UpscaleModelManager.State.READY -> "Redownload models"
                else -> "Download models"
            }
            add(
                Preference.PreferenceItem.TextPreference(
                    title = modelActionTitle,
                    subtitle = when (modelStatus.state) {
                        eu.kanade.tachiyomi.ui.reader.setting.UpscaleModelManager.State.DOWNLOADING -> "Downloading ${modelStatus.currentFile ?: ""}"
                        eu.kanade.tachiyomi.ui.reader.setting.UpscaleModelManager.State.ERROR -> modelStatus.error ?: "Tap to retry"
                        else -> null
                    },
                    onClick = {
                        when (modelStatus.state) {
                            eu.kanade.tachiyomi.ui.reader.setting.UpscaleModelManager.State.DOWNLOADING -> modelManager.cancelDownload()
                            eu.kanade.tachiyomi.ui.reader.setting.UpscaleModelManager.State.READY -> modelManager.startDownload(force = true)
                            else -> modelManager.startDownload()
                        }
                    },
                    enabled = enabled && !isSimple,
                ),
            )
            add(
                Preference.PreferenceItem.TextPreference(
                    title = "Clear upscaler models",
                    subtitle = "Remove downloaded ncnn upscaler weights",
                    onClick = { modelManager.clearModels() },
                    enabled = enabled && !isSimple && modelStatus.state != eu.kanade.tachiyomi.ui.reader.setting.UpscaleModelManager.State.DOWNLOADING,
                ),
            )
            add(
                Preference.PreferenceItem.InfoPreference(
                    title = if (enabled) "Upscaling is per-manga: enable globally above, then toggle Upscale Manga on each manga's details page." else "Enable upscaling to reveal the per-manga toggle on manga details.",
                ),
            )
            if (!isSimple && enabled) {
                add(
                    Preference.PreferenceItem.InfoPreference(
                        title = "Native mode currently uses high-quality bilinear as placeholder; true NCNN/ONNX inference (Real-CUGAN/ESRGAN) runs when native libs (libncnn/libonnxruntime) are bundled. Cache keys include factor, model, preset and backend.",
                    ),
                )
            }
        }
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_upscale_title),
                preferenceItems = items.toImmutableList(),
            ),
        )
    }
}
