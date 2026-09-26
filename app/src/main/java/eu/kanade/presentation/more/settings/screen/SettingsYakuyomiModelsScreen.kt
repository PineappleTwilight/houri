package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.filterVisible
import kotlinx.collections.immutable.persistentListOf
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

// KMK -->
/**
 * Models and engine subpage of the MTL/Yakuyomi settings hub.
 *
 * Hosts local LLM selection/import/start-stop/autostart/download-progress/
 * cleanup, MTL detector/OCR/inpainter download/cleanup, remote model URL
 * overrides/re-download and navigation to the existing LLM/engine advanced
 * screens. All rows and gating are reused from [SettingsYakuyomiScreen] so
 * preference keys and visibility behavior are unchanged.
 */
object SettingsYakuyomiModelsScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = KMR.strings.pref_yakuyomi_models_engine

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { globalAppGraph.translationPreferences }
        val modelManager = remember { globalAppGraph.modelManager }
        val isNomtl = eu.kanade.tachiyomi.BuildConfig.IS_NOMTL
        val provider by prefs.provider().collectAsState()
        val isMangatranslator = provider == "mangatranslator"

        val groups = listOfNotNull(
            SettingsYakuyomiScreen.getLocalLlmGroup().takeIf { !isNomtl && !isMangatranslator },
            SettingsYakuyomiScreen.getModelGroup(modelManager).takeIf { !isNomtl && !isMangatranslator },
            SettingsYakuyomiScreen.getRemoteModelGroup(prefs, modelManager).takeIf { !isMangatranslator && !isNomtl },
            SettingsYakuyomiScreen.getAdvancedGroup(prefs).takeIf { !isNomtl && !isMangatranslator },
        )
        // KMK --> Gate first, then decide whether the page is empty: getModelGroup is
        // ramGated, so a low-RAM (but non-nomtl) device has every group filtered out and
        // would otherwise land on a blank page with no explanation.
        val visible = groups.filterVisible()
        // KMK <--
        if (visible.isNotEmpty()) return visible
        val emptyNote = if (isNomtl) {
            KMR.strings.pref_yakuyomi_models_nomtl_note
        } else {
            KMR.strings.pref_yakuyomi_models_mangatranslator_note
        }
        return listOf(
            Preference.PreferenceGroup(
                title = "",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(emptyNote),
                    ),
                ),
            ),
        )
    }
}
// KMK <--
