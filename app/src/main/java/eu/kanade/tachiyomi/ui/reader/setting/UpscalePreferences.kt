package eu.kanade.tachiyomi.ui.reader.setting

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class UpscalePreferences(
    private val preferenceStore: PreferenceStore,
) {
    enum class Preset { FAST, BALANCED, HIGH }
    enum class Backend { AUTO, VULKAN, NPU, CPU }

    fun enabled() = preferenceStore.getBoolean("pref_upscale_enabled", false)
    fun preset() = preferenceStore.getString("pref_upscale_preset", Preset.BALANCED.name)
    fun backend() = preferenceStore.getString("pref_upscale_backend", Backend.AUTO.name)
    fun perSeriesEnabled() = preferenceStore.getString("pref_upscale_per_series", "")
}
