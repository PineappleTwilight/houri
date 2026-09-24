package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsYakuyomiPromptScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_yakuyomi_prompt_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { globalAppGraph.translationPreferences }
        val enabled by prefs.enabled().collectAsState()

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_yakuyomi_prompt_title),
                preferenceItems = buildList {
                    add(
                        Preference.PreferenceItem.EditTextPreference(
                            preference = prefs.customTranslationInstructions(),
                            title = stringResource(KMR.strings.pref_yakuyomi_prompt_custom_instructions),
                            subtitle = stringResource(KMR.strings.pref_yakuyomi_prompt_custom_instructions_summary),
                            validator = { true },
                            multiline = true,
                            allowEmpty = true,
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.ListPreference(
                            preference = prefs.translationFormality(),
                            entries = persistentMapOf(
                                "auto" to stringResource(KMR.strings.pref_yakuyomi_prompt_formality_auto),
                                "casual" to stringResource(KMR.strings.pref_yakuyomi_prompt_formality_casual),
                                "polite" to stringResource(KMR.strings.pref_yakuyomi_prompt_formality_polite),
                                "formal" to stringResource(KMR.strings.pref_yakuyomi_prompt_formality_formal),
                                "literary" to stringResource(KMR.strings.pref_yakuyomi_prompt_formality_literary),
                            ),
                            title = stringResource(KMR.strings.pref_yakuyomi_prompt_formality),
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.ListPreference(
                            preference = prefs.sfxPolicy(),
                            entries = persistentMapOf(
                                "preserve" to stringResource(KMR.strings.pref_yakuyomi_prompt_sfx_preserve),
                                "mixed" to stringResource(KMR.strings.pref_yakuyomi_prompt_sfx_mixed),
                                "translate" to stringResource(KMR.strings.pref_yakuyomi_prompt_sfx_translate),
                            ),
                            title = stringResource(KMR.strings.pref_yakuyomi_prompt_sfx),
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.EditTextPreference(
                            preference = prefs.promptFewShotSource(),
                            title = stringResource(KMR.strings.pref_yakuyomi_prompt_fewshot_source),
                            subtitle = stringResource(KMR.strings.pref_yakuyomi_prompt_fewshot_source_summary),
                            validator = { true },
                            multiline = true,
                            allowEmpty = true,
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.EditTextPreference(
                            preference = prefs.promptFewShotTarget(),
                            title = stringResource(KMR.strings.pref_yakuyomi_prompt_fewshot_target),
                            subtitle = stringResource(KMR.strings.pref_yakuyomi_prompt_fewshot_target_summary),
                            validator = { true },
                            multiline = true,
                            allowEmpty = true,
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.EditTextPreference(
                            preference = prefs.glossaryJson(),
                            title = stringResource(KMR.strings.pref_yakuyomi_prompt_glossary),
                            subtitle = stringResource(KMR.strings.pref_yakuyomi_prompt_glossary_summary),
                            validator = { true },
                            multiline = true,
                            allowEmpty = true,
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KMR.strings.pref_yakuyomi_prompt_reset),
                            subtitle = stringResource(KMR.strings.pref_yakuyomi_prompt_reset_summary),
                            onClick = prefs::resetPromptPolicy,
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.InfoPreference(
                            title = stringResource(KMR.strings.pref_yakuyomi_prompt_info),
                        ),
                    )
                }.toPersistentList(),
            ),
        )
    }
}
