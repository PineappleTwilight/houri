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
    private val upscaleMangaStore: UpscaleMangaStore,
) {
    enum class Preset { FAST, BALANCED, HIGH }
    enum class Backend { AUTO, VULKAN, NPU, CPU }
    enum class Model { REAL_CUGAN, REAL_ESRGAN, WAIFU2X }
    enum class SimpleAlgo { BICUBIC, BILINEAR, NEAREST }
    enum class Mode { NATIVE, SIMPLE }

    companion object {
        const val KEY_ENABLED = "pref_upscale_enabled"
        const val KEY_PRESET = "pref_upscale_preset"
        const val KEY_BACKEND = "pref_upscale_backend"
        const val KEY_MODEL = "pref_upscale_model"
        const val KEY_CACHE_ENABLED = "pref_upscale_cache_enabled"
        const val KEY_FACTOR = "pref_upscale_factor"
        const val KEY_SIMPLE_ALGO = "pref_upscale_simple_algo"
        const val KEY_MODE = "pref_upscale_mode"
    }

    init {
        try {
            val legacy = preferenceStore.getString("pref_upscale_per_series", "").get()
            if (legacy.isNotBlank()) {
                preferenceStore.getString("pref_upscale_per_series", "").set("")
            }
        } catch (_: Exception) {}
    }

    fun enabled() = preferenceStore.getBoolean(KEY_ENABLED, false)
    fun preset() = preferenceStore.getString(KEY_PRESET, Preset.BALANCED.name)
    fun backend() = preferenceStore.getString(KEY_BACKEND, Backend.AUTO.name)
    fun model() = preferenceStore.getString(KEY_MODEL, Model.REAL_CUGAN.name)
    fun cacheEnabled() = preferenceStore.getBoolean(KEY_CACHE_ENABLED, true)
    fun upscaleFactor() = preferenceStore.getFloat(KEY_FACTOR, 2f)
    fun simpleAlgo() = preferenceStore.getString(KEY_SIMPLE_ALGO, SimpleAlgo.BICUBIC.name)
    fun mode() = preferenceStore.getString(KEY_MODE, if (BuildConfig.IS_NOMTL) Mode.SIMPLE.name else Mode.NATIVE.name)

    fun isMtlEnabled(): Boolean = !BuildConfig.IS_NOMTL && translationPreferences.enabled().get()
    fun isSimpleMode(): Boolean = BuildConfig.IS_NOMTL || effectiveMode() == Mode.SIMPLE

    fun isGloballyEnabled(): Boolean {
        if (!enabled().get()) return false
        if (!isSimpleMode() && !isMtlEnabled()) return false
        return true
    }

    fun effectiveSimpleAlgo(): SimpleAlgo = runCatching { SimpleAlgo.valueOf(simpleAlgo().get()) }.getOrDefault(SimpleAlgo.BICUBIC)

    fun isEnabledForManga(mangaId: Long): Boolean {
        if (mangaId <= 0) return isGloballyEnabled()
        if (!isGloballyEnabled()) return false
        return upscaleMangaStore.isEnabled(mangaId)
    }

    fun isMangaToggleEnabled(mangaId: Long): Boolean = upscaleMangaStore.isEnabled(mangaId)

    fun setMangaToggleEnabled(mangaId: Long, enabled: Boolean) = upscaleMangaStore.setEnabled(mangaId, enabled)

    fun getMangaTogglePreference(mangaId: Long) = upscaleMangaStore.getPreference(mangaId)

    fun effectivePreset(): Preset = runCatching { Preset.valueOf(preset().get()) }.getOrDefault(Preset.BALANCED)
    fun effectiveBackend(): Backend = runCatching { Backend.valueOf(backend().get()) }.getOrDefault(Backend.AUTO)
    fun effectiveModel(): Model = runCatching { Model.valueOf(model().get()) }.getOrDefault(Model.REAL_CUGAN)
    fun effectiveMode(): Mode = runCatching { Mode.valueOf(mode().get()) }.getOrDefault(if (BuildConfig.IS_NOMTL) Mode.SIMPLE else Mode.NATIVE)
}
