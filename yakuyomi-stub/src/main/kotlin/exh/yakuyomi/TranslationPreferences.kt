package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * No-op stub of [TranslationPreferences] for the no-MTL APK variant. Every preference is a real
 * stored preference (so a user who migrates from an MTL build keeps their settings), but the
 * feature is hard-off: [enabled] always reads false.
 */
@SingleIn(AppScope::class)
@Inject
class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun enabled(): Preference<Boolean> = preferenceStore.getBoolean("pref_yakuyomi_enabled", false)

    fun targetLang() = preferenceStore.getString("pref_yakuyomi_target_lang", "en")
    fun apiKey() = preferenceStore.getString(Preference.privateKey("pref_yakuyomi_api_key"), "")
    fun provider() = preferenceStore.getString("pref_yakuyomi_provider", "openrouter")
    fun model() = preferenceStore.getString("pref_yakuyomi_model", "google/gemma-2-9b-it:free")

    fun apiKeyForProvider(provider: String) =
        preferenceStore.getString(Preference.privateKey("pref_yakuyomi_api_key_${provider.lowercase()}"), "")

    fun modelForProvider(provider: String) =
        preferenceStore.getString("pref_yakuyomi_model_${provider.lowercase()}", "")

    fun effectiveApiKey(): String {
        val p = provider().get().lowercase()
        val per = apiKeyForProvider(p).get()
        if (per.isNotBlank()) return per
        return apiKey().get()
    }

    fun effectiveModel(): String {
        val p = provider().get().lowercase()
        val per = modelForProvider(p).get()
        if (per.isNotBlank()) return per
        return model().get()
    }

    fun setEffectiveApiKey(value: String) {
        val p = provider().get().lowercase()
        apiKeyForProvider(p).set(value)
        apiKey().set(value)
    }

    fun setEffectiveModel(value: String) {
        val p = provider().get().lowercase()
        modelForProvider(p).set(value)
        model().set(value)
    }
    fun geminiNanoEnabled() = preferenceStore.getBoolean("pref_yakuyomi_gemini_nano", true)
    fun offlineFallback() = preferenceStore.getBoolean("pref_yakuyomi_offline_fallback", false)
    fun autoTranslateOnDownload() = preferenceStore.getBoolean("pref_yakuyomi_auto_download", false)
    fun saveTranslatedPages() = preferenceStore.getBoolean("pref_yakuyomi_save_translated_pages", true)
    fun autoSaveWhileReading() = preferenceStore.getBoolean("pref_yakuyomi_auto_save_while_reading", true)
    fun cacheEnabled() = preferenceStore.getBoolean("pref_yakuyomi_cache_enabled", true)
    fun customBaseUrl() = preferenceStore.getString("pref_yakuyomi_custom_base_url", "")
    fun customHeaders() = preferenceStore.getString("pref_yakuyomi_custom_headers", "")
    fun breadcrumbWindowSize() = preferenceStore.getInt("pref_yakuyomi_breadcrumb_window", 5)
    fun fontFamily() = preferenceStore.getString("pref_yakuyomi_font_family", "casual")
    fun translationTextColor() = preferenceStore.getInt("pref_yakuyomi_text_color", 0xFF000000.toInt())
    fun translationTextColorHex() = preferenceStore.getString("pref_yakuyomi_text_color_hex", "")
    fun localModel() = preferenceStore.getString("pref_yakuyomi_local_model", "")
    fun localBackendPref() = preferenceStore.getString("pref_yakuyomi_local_backend", "auto")
    fun localModelFile() = preferenceStore.getString("pref_yakuyomi_local_model_file", "")
    fun localLlmAutoStart() = preferenceStore.getBoolean("pref_yakuyomi_local_llm_auto_start", false)
    fun localLlmSamplingOverrides() = preferenceStore.getString("pref_yakuyomi_local_llm_sampling", "")

    fun isConfigured(): Boolean = false
    fun hasApiKey(): Boolean = false
}
