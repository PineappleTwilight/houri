package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.util.collectAsState

// KMK -->
/**
 * Behavior subpage of the MTL/Yakuyomi settings hub.
 *
 * Hosts offline fallback, translation cache enablement/clear-cache action,
 * automatic translation on download, saving translated pages, auto-save while
 * reading, breadcrumb/context window and the grammar note. All rows and
 * gating are reused from [SettingsYakuyomiScreen] so preference keys and
 * visibility behavior are unchanged.
 */
object SettingsYakuyomiBehaviorScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = KMR.strings.pref_yakuyomi_behavior_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { globalAppGraph.translationPreferences }
        val cache = remember { globalAppGraph.translationCache }
        val provider by prefs.provider().collectAsState()
        return listOf(
            SettingsYakuyomiScreen.getBehaviorGroup(prefs, cache, hideLocal = provider == "mangatranslator"),
        )
    }
}
// KMK <--
