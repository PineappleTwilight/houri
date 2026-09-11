package eu.kanade.tachiyomi.ui.reader.setting

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import exh.yakuyomi.TranslationPreferences
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class UpscalePreferences(
    private val preferenceStore: PreferenceStore,
    private val translationPreferences: TranslationPreferences,
) {
    enum class Preset { FAST, BALANCED, HIGH }
    enum class Backend { AUTO, VULKAN, NPU, CPU }
    enum class Model { REAL_CUGAN, REAL_ESRGAN, WAIFU2X }

    fun enabled() = preferenceStore.getBoolean("pref_upscale_enabled", false)
    fun preset() = preferenceStore.getString("pref_upscale_preset", Preset.BALANCED.name)
    fun backend() = preferenceStore.getString("pref_upscale_backend", Backend.AUTO.name)
    fun model() = preferenceStore.getString("pref_upscale_model", Model.REAL_CUGAN.name)
    fun perSeriesEnabled() = preferenceStore.getString("pref_upscale_per_series", "")
    fun cacheEnabled() = preferenceStore.getBoolean("pref_upscale_cache_enabled", true)
    fun upscaleFactor() = preferenceStore.getFloat("pref_upscale_factor", 2f)

    fun isMtlEnabled(): Boolean = !BuildConfig.IS_NOMTL && translationPreferences.enabled().get()

    @Volatile
    private var cachedPerSeriesIds: Set<Long>? = null
    @Volatile
    private var cachedPerSeriesRaw: String? = null

    fun isEnabledForManga(mangaId: Long): Boolean {
        if (!isMtlEnabled()) return false
        if (!enabled().get()) return false
        val perSeries = perSeriesEnabled().get()
        if (perSeries.isBlank()) return true
        val enabledIds = getCachedPerSeriesIds(perSeries)
        return mangaId in enabledIds
    }

    fun setEnabledForManga(mangaId: Long, enabled: Boolean) {
        val perSeries = perSeriesEnabled().get()
        val set = getCachedPerSeriesIds(perSeries).toMutableSet()
        if (enabled) set.add(mangaId) else set.remove(mangaId)
        val serialized = set.joinToString(",")
        perSeriesEnabled().set(serialized)
        cachedPerSeriesRaw = serialized
        cachedPerSeriesIds = set.toSet()
    }

    private fun getCachedPerSeriesIds(raw: String): Set<Long> {
        val cachedRaw = cachedPerSeriesRaw
        val cached = cachedPerSeriesIds
        if (cached != null && cachedRaw == raw) return cached
        val parsed = raw.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
        cachedPerSeriesRaw = raw
        cachedPerSeriesIds = parsed
        return parsed
    }

    fun effectivePreset(): Preset = runCatching { Preset.valueOf(preset().get()) }.getOrDefault(Preset.BALANCED)
    fun effectiveBackend(): Backend = runCatching { Backend.valueOf(backend().get()) }.getOrDefault(Backend.AUTO)
    fun effectiveModel(): Model = runCatching { Model.valueOf(model().get()) }.getOrDefault(Model.REAL_CUGAN)
}
