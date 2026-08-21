package eu.kanade.tachiyomi.ui.updates

import cafe.adriel.voyager.core.model.ScreenModel
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.updates.service.UpdatesPreferences

class UpdatesSettingsScreenModel(
    val updatesPreferences: UpdatesPreferences = globalAppGraph.updatesPreferences,
) : ScreenModel {

    fun toggleFilter(preference: (UpdatesPreferences) -> Preference<TriState>) {
        preference(updatesPreferences).getAndSet {
            it.next()
        }
    }

    // KMK -->
    fun toggleSwitch(preference: (UpdatesPreferences) -> Preference<Boolean>) {
        preference(updatesPreferences).getAndSet { !it }
    }
    // KMK <--
}
