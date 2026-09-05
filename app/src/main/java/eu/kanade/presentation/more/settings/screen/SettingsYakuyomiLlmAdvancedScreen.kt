package eu.kanade.presentation.more.settings.screen

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import exh.yakuyomi.LocalLlmModel
import exh.yakuyomi.LocalLlmSamplingConfig
import kotlinx.collections.immutable.toPersistentList
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

/**
 * Per-model llama.cpp sampling/context editor, reached from the local-provider group of the
 * MTL settings. Values persist per model id; "Reset to defaults" drops the override.
 */
class SettingsYakuyomiLlmAdvancedScreen(
    private val model: LocalLlmModel,
) : SearchableSettings {

    @Composable
    @ReadOnlyComposable
    override fun getTitleRes(): StringResource = KMR.strings.pref_yakuyomi_llm_advanced

    @Composable
    override fun getPreferences(): List<Preference> {
        val manager = remember { globalAppGraph.localLlmManager }
        val context = LocalContext.current
        var cfg by remember { mutableStateOf(manager.samplingFor(model)) }

        fun update(transform: (LocalLlmSamplingConfig) -> LocalLlmSamplingConfig) {
            cfg = transform(cfg)
            manager.setSampling(model.id, cfg)
        }

        val defaults = LocalLlmSamplingConfig(
            temperature = if (model.isTranslationFinetune) 0f else 0.3f,
            contextLength = model.contextLength,
        )

        return listOf(
            Preference.PreferenceGroup(
                title = "llama.cpp sampling",
                preferenceItems = listOf(
                    Preference.PreferenceItem.SliderPreference(
                        value = (cfg.temperature * 100).toInt().coerceIn(0, 100),
                        title = "Temperature",
                        subtitle = "Randomness of the output: %s",
                        valueString = String.format(Locale.US, "%.2f", cfg.temperature),
                        valueRange = 0..100,
                        steps = 99,
                        onValueChanged = { v -> update { it.copy(temperature = v / 100f) } },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = (cfg.topP * 100).toInt().coerceIn(0, 100),
                        title = "Top P",
                        subtitle = "Nucleus sampling cutoff: %s",
                        valueString = String.format(Locale.US, "%.2f", cfg.topP),
                        valueRange = 0..100,
                        steps = 99,
                        onValueChanged = { v -> update { it.copy(topP = v / 100f) } },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = cfg.topK,
                        title = "Top K",
                        subtitle = "Consider only the top K tokens: %s",
                        valueString = cfg.topK.toString(),
                        valueRange = 1..100,
                        steps = 98,
                        onValueChanged = { v -> update { it.copy(topK = v) } },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = (cfg.repeatPenalty * 100).toInt().coerceIn(80, 200),
                        title = "Repeat penalty",
                        subtitle = "Discourage repeating tokens: %s",
                        valueString = String.format(Locale.US, "%.2f", cfg.repeatPenalty),
                        valueRange = 80..200,
                        steps = 119,
                        onValueChanged = { v -> update { it.copy(repeatPenalty = v / 100f) } },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = cfg.maxTokens,
                        title = "Max tokens",
                        subtitle = "Longest generation per page: %s",
                        valueString = cfg.maxTokens.toString(),
                        valueRange = 64..4096,
                        steps = 62,
                        onValueChanged = { v -> update { it.copy(maxTokens = v) } },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = cfg.contextLength,
                        title = "Context length",
                        subtitle = "Token context window: %s",
                        valueString = cfg.contextLength.toString(),
                        valueRange = 512..8192,
                        steps = 62,
                        onValueChanged = { v -> update { it.copy(contextLength = v) } },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = cfg.numThreads,
                        title = "Threads",
                        subtitle = "CPU threads (0 = auto): %s",
                        valueString = if (cfg.numThreads <= 0) "auto (${cfg.resolvedThreads})" else cfg.numThreads.toString(),
                        valueRange = 0..16,
                        steps = 15,
                        onValueChanged = { v -> update { it.copy(numThreads = v) } },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = cfg.gpuLayers.coerceIn(-1, 99),
                        title = "GPU layers",
                        subtitle = "Layers offloaded to the GPU (-1 = auto, falls back to CPU): %s",
                        valueString = if (cfg.gpuLayers < 0) "auto (all layers)" else cfg.gpuLayers.toString(),
                        valueRange = -1..99,
                        steps = 99,
                        onValueChanged = { v -> update { it.copy(gpuLayers = v) } },
                    ),
                ).toPersistentList(),
            ),
            Preference.PreferenceGroup(
                title = "",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = "Reset to defaults",
                        subtitle = "Restore the recommended values for ${model.displayName}",
                        onClick = {
                            manager.resetSampling(model.id)
                            cfg = defaults
                            Toast.makeText(context, "Sampling reset to defaults", Toast.LENGTH_SHORT).show()
                        },
                    ),
                ).toPersistentList(),
            ),
        )
    }
}
