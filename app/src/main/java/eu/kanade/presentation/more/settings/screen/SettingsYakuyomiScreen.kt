package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.toast
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsYakuyomiScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_yakuyomi_enabled

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val prefs = remember { globalAppGraph.translationPreferences }
        val cache = remember { globalAppGraph.translationCache }

        val enabled by prefs.enabled().collectAsState()
        val targetLang by prefs.targetLang().collectAsState()
        val provider by prefs.provider().collectAsState()
        val model by prefs.model().collectAsState()

        return listOf(
            getGeneralGroup(prefs),
            getProviderGroup(prefs),
            getBehaviorGroup(prefs, cache),
        )
    }

    @Composable
    private fun getGeneralGroup(prefs: exh.yakuyomi.TranslationPreferences): Preference.PreferenceGroup {
        val enabled by prefs.enabled().collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_yakuyomi_enabled),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.enabled(),
                    title = stringResource(KMR.strings.pref_yakuyomi_enabled),
                    subtitle = stringResource(KMR.strings.pref_yakuyomi_enabled_summary),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.targetLang(),
                    entries = persistentMapOf(
                        "EN" to "English (EN)",
                        "ES" to "Español (ES)",
                        "FR" to "Français (FR)",
                        "DE" to "Deutsch (DE)",
                        "PT" to "Português (PT)",
                        "RU" to "Русский (RU)",
                        "IT" to "Italiano (IT)",
                        "PL" to "Polski (PL)",
                        "TR" to "Türkçe (TR)",
                        "ID" to "Indonesia (ID)",
                        "AR" to "العربية (AR)",
                        "ZH" to "中文 (ZH)",
                        "JA" to "日本語 (JA)",
                        "KO" to "한국어 (KO)",
                        "TH" to "ไทย (TH)",
                        "VI" to "Tiếng Việt (VI)",
                    ),
                    title = stringResource(KMR.strings.pref_yakuyomi_target_lang),
                    subtitle = stringResource(KMR.strings.pref_yakuyomi_target_lang) + ": %s",
                    enabled = enabled,
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = "EN → EN uses grammar/vocab fix (same LLM). " +
                        "Disabled by default; uploads are gated by Incognito/Censor.",
                ),
            ),
        )
    }

    @Composable
    private fun getProviderGroup(prefs: exh.yakuyomi.TranslationPreferences): Preference.PreferenceGroup {
        val enabled by prefs.enabled().collectAsState()
        val provider by prefs.provider().collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_yakuyomi_provider),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.provider(),
                    entries = persistentMapOf(
                        "openrouter" to "OpenRouter",
                        "gemini" to "Gemini",
                    ),
                    title = stringResource(KMR.strings.pref_yakuyomi_provider),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.apiKey(),
                    title = stringResource(KMR.strings.pref_yakuyomi_api_key),
                    subtitle = if (prefs.apiKey().get().isBlank()) "Not set — will use offline fallback" else "••••••••",
                    enabled = enabled,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.model(),
                    title = stringResource(KMR.strings.pref_yakuyomi_model),
                    subtitle = if (provider == "gemini") "Gemini model (e.g. gemini-1.5-flash)" else "OpenRouter model (default: google/gemma-2-9b-it:free)",
                    enabled = enabled,
                ),
            ),
        )
    }

    @Composable
    private fun getBehaviorGroup(
        prefs: exh.yakuyomi.TranslationPreferences,
        cache: exh.yakuyomi.TranslationCache,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val enabled by prefs.enabled().collectAsState()
        val cacheBytes = remember { cache.sizeBytes() }
        return Preference.PreferenceGroup(
            title = "Behavior & Cache",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.offlineFallback(),
                    title = stringResource(KMR.strings.pref_yakuyomi_offline_fallback),
                    subtitle = "Use offline ML Kit when API key is empty or call fails",
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.cacheEnabled(),
                    title = stringResource(KMR.strings.pref_yakuyomi_cache_enabled),
                    subtitle = "Cache translated pages per model (WEBP, 32MB cap, 30d expiry)",
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.autoTranslateOnDownload(),
                    title = stringResource(KMR.strings.pref_yakuyomi_auto_download),
                    subtitle = "Prewarm translation when chapters are downloaded (respects per-manga toggle)",
                    enabled = enabled,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.breadcrumbWindowSize(),
                    entries = persistentMapOf(
                        3 to "3 chapters",
                        5 to "5 chapters",
                        8 to "8 chapters",
                        10 to "10 chapters",
                    ),
                    title = "Context window",
                    subtitle = "Sliding window for translation consistency: %s",
                    enabled = enabled,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Clear translation cache",
                    subtitle = "Current: ${cacheBytes / 1024} KB / 32768 KB",
                    onClick = {
                        cache.clearAll()
                        context.toast("Translation cache cleared")
                    },
                    enabled = enabled,
                ),
            ),
        )
    }
}
