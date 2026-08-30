package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun enabled() = preferenceStore.getBoolean("pref_yakuyomi_enabled", false)

    fun targetLang() = preferenceStore.getString("pref_yakuyomi_target_lang", "en")

    fun apiKey() = preferenceStore.getString(Preference.privateKey("pref_yakuyomi_api_key"), "")

    fun provider() = preferenceStore.getString("pref_yakuyomi_provider", "openrouter")

    fun model() = preferenceStore.getString("pref_yakuyomi_model", "google/gemma-2-9b-it:free")

    fun offlineFallback() = preferenceStore.getBoolean("pref_yakuyomi_offline_fallback", false)

    fun autoTranslateOnDownload() = preferenceStore.getBoolean("pref_yakuyomi_auto_download", false)

    fun saveTranslatedPages() = preferenceStore.getBoolean("pref_yakuyomi_save_translated_pages", true)

    fun autoSaveWhileReading() = preferenceStore.getBoolean("pref_yakuyomi_auto_save_while_reading", true)

    fun cacheEnabled() = preferenceStore.getBoolean("pref_yakuyomi_cache_enabled", true)

    fun customBaseUrl() = preferenceStore.getString("pref_yakuyomi_custom_base_url", "")

    fun customHeaders() = preferenceStore.getString("pref_yakuyomi_custom_headers", "")

    fun breadcrumbWindowSize() = preferenceStore.getInt("pref_yakuyomi_breadcrumb_window", 5)

    /**
     * Font used to typeset translated text. Maps to an Android system font family
     * (e.g. "casual" for a comic/manga look). "default" keeps the system default.
     */
    fun fontFamily() = preferenceStore.getString("pref_yakuyomi_font_family", "casual")

    fun isConfigured(): Boolean = enabled().get() && targetLang().get().isNotBlank()

    fun hasApiKey(): Boolean = apiKey().get().isNotBlank()
}
