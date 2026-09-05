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

    /**
     * Selected on-device LLM id from [LocalLlmCatalog]. Empty means "auto" — the best-fitting
     * model for this device is presented (and used) as the default. Only the RAM gate is enforced.
     */
    fun localModel() = preferenceStore.getString("pref_yakuyomi_local_model", "")

    /** Backend preference for the local provider (reserved; llama.cpp is the only backend). */
    fun localBackendPref() = preferenceStore.getString("pref_yakuyomi_local_backend", "auto")

    /** Absolute path of a user-supplied custom GGUF model (empty = use the catalog). */
    fun localModelFile() = preferenceStore.getString("pref_yakuyomi_local_model_file", "")

    /** Whether to preload the local LLM engine on app startup (provider must be local). */
    fun localLlmAutoStart() = preferenceStore.getBoolean("pref_yakuyomi_local_llm_auto_start", false)

    /**
     * Per-model llama.cpp sampling overrides, serialized as a JSON map of
     * `modelId -> [LocalLlmSamplingConfig]`. Empty string = all defaults.
     */
    fun localLlmSamplingOverrides() = preferenceStore.getString("pref_yakuyomi_local_llm_sampling", "")

    /**
     * Whether to attempt on-device Gemini Nano (ML Kit GenAI / AICore) as the priority LLM
     * provider when the device supports it. Defaults to enabled; unsupported devices fall
     * back to the configured cloud provider automatically.
     */
    fun geminiNanoEnabled() = preferenceStore.getBoolean("pref_yakuyomi_gemini_nano", true)

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

    /**
     * Color of the translated text rendered onto the page, as an ARGB int. Defaults to
     * opaque black. The outline is still chosen from the background luminance so the
     * text stays readable on any bubble/panel color.
     */
    fun translationTextColor() = preferenceStore.getInt("pref_yakuyomi_text_color", 0xFF000000.toInt())

    /**
     * Optional manual hex override for the translated text color (e.g. "#FFFFFF", "ffffff").
     * When blank or unparseable, [translationTextColor] (the preset) is used.
     */
    fun translationTextColorHex() = preferenceStore.getString("pref_yakuyomi_text_color_hex", "")

    fun glossaryJson() = preferenceStore.getString("pref_yakuyomi_glossary_json", "")

    fun glossaryMap(): Map<String, String> {
        val raw = glossaryJson().get()
        if (raw.isBlank()) return emptyMap()
        return try {
            val json = org.json.JSONObject(raw)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { k -> map[k] = json.optString(k, "") }
            map.filterValues { it.isNotBlank() }
        } catch (_: Exception) {
            // Fallback: parse as lines "source -> target"
            raw.lines().mapNotNull { line ->
                val parts = line.split("->", ":", "=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()
        }
    }

    fun preserveSfx() = preferenceStore.getBoolean("pref_yakuyomi_preserve_sfx", true)

    fun translationFormality() = preferenceStore.getString("pref_yakuyomi_formality", "auto")

    fun isConfigured(): Boolean = enabled().get() && targetLang().get().isNotBlank()

    fun hasApiKey(): Boolean = effectiveApiKey().isNotBlank()
}
