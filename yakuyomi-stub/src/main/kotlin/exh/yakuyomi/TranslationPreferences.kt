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

    fun glossaryJson() = preferenceStore.getString("pref_yakuyomi_glossary_json", "")
    fun preserveSfx() = preferenceStore.getBoolean("pref_yakuyomi_preserve_sfx", true)
    fun translationFormality() = preferenceStore.getString("pref_yakuyomi_formality", "auto")
    fun detectorInputSize() = preferenceStore.getInt("pref_yakuyomi_detector_input_size", 1024)
    fun detectorBoxThreshold() = preferenceStore.getFloat("pref_yakuyomi_detector_box_thresh", 0.7f)
    fun detectorSegThreshold() = preferenceStore.getFloat("pref_yakuyomi_detector_seg_thresh", 0.12f)
    fun ocrMinProb() = preferenceStore.getFloat("pref_yakuyomi_ocr_min_prob", 0.5f)
    fun ocrBicubic() = preferenceStore.getBoolean("pref_yakuyomi_ocr_bicubic", true)
    fun ocrUnsharp() = preferenceStore.getBoolean("pref_yakuyomi_ocr_unsharp", true)
    fun inpainterMethod() = preferenceStore.getString("pref_yakuyomi_inpainter_method", "aot")
    fun inpainterTileSize() = preferenceStore.getInt("pref_yakuyomi_inpainter_tile", 768)
    fun inpainterMaskDilate() = preferenceStore.getFloat("pref_yakuyomi_inpainter_dilate", 24f)
    fun inpainterBboxPad() = preferenceStore.getInt("pref_yakuyomi_inpainter_pad", 16)
    fun renderFontScale() = preferenceStore.getFloat("pref_yakuyomi_render_font_scale", 0.85f)
    fun renderExpandW() = preferenceStore.getFloat("pref_yakuyomi_render_expand_w", 1.3f)
    fun renderExpandH() = preferenceStore.getFloat("pref_yakuyomi_render_expand_h", 1.5f)
    fun renderTateChuYoko() = preferenceStore.getBoolean("pref_yakuyomi_render_tcy", true)
    fun renderFontSizeMax() = preferenceStore.getInt("pref_yakuyomi_render_font_max", 60)
    fun renderFontSizeMin() = preferenceStore.getInt("pref_yakuyomi_render_font_min", 9)
    fun glossaryMap(): Map<String, String> = emptyMap()
    fun resetEngineTuning() {
        detectorInputSize().set(1024)
        detectorBoxThreshold().set(0.7f)
        detectorSegThreshold().set(0.12f)
        ocrMinProb().set(0.5f)
        ocrBicubic().set(true)
        ocrUnsharp().set(true)
        inpainterMethod().set("aot")
        inpainterTileSize().set(768)
        inpainterMaskDilate().set(24f)
        inpainterBboxPad().set(16)
        renderFontScale().set(0.85f)
        renderExpandW().set(1.3f)
        renderExpandH().set(1.5f)
        renderTateChuYoko().set(true)
        renderFontSizeMax().set(60)
        renderFontSizeMin().set(9)
    }

    fun isConfigured(): Boolean = false
    fun hasApiKey(): Boolean = false
}
