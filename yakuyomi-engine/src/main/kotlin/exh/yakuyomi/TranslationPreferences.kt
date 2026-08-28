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

    fun targetLang() = preferenceStore.getString("pref_yakuyomi_target_lang", "EN")

    fun apiKey() = preferenceStore.getString(Preference.privateKey("pref_yakuyomi_api_key"), "")

    fun provider() = preferenceStore.getString("pref_yakuyomi_provider", "openrouter")

    fun model() = preferenceStore.getString("pref_yakuyomi_model", "google/gemma-2-9b-it:free")

    fun offlineFallback() = preferenceStore.getBoolean("pref_yakuyomi_offline_fallback", true)

    fun autoTranslateOnDownload() = preferenceStore.getBoolean("pref_yakuyomi_auto_download", false)

    fun cacheEnabled() = preferenceStore.getBoolean("pref_yakuyomi_cache_enabled", true)

    fun breadcrumbWindowSize() = preferenceStore.getInt("pref_yakuyomi_breadcrumb_window", 5)

    fun isConfigured(): Boolean = enabled().get() && targetLang().get().isNotBlank()

    fun hasApiKey(): Boolean = apiKey().get().isNotBlank()
}
