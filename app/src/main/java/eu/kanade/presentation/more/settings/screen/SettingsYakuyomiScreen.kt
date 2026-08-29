package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsYakuyomiScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_yakuyomi_enabled

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { globalAppGraph.translationPreferences }
        val cache = remember { globalAppGraph.translationCache }
        val modelManager = remember { globalAppGraph.modelManager }

        return listOf(
            getGeneralGroup(prefs),
            getProviderGroup(prefs),
            getModelGroup(modelManager),
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
                        "en" to "English (EN)",
                        "es" to "Español (ES)",
                        "fr" to "Français (FR)",
                        "de" to "Deutsch (DE)",
                        "pt" to "Português (PT)",
                        "ru" to "Русский (RU)",
                        "it" to "Italiano (IT)",
                        "pl" to "Polski (PL)",
                        "tr" to "Türkçe (TR)",
                        "id" to "Indonesia (ID)",
                        "ar" to "العربية (AR)",
                        "zh" to "中文 (ZH)",
                        "ja" to "日本語 (JA)",
                        "ko" to "한국어 (KO)",
                        "th" to "ไทย (TH)",
                        "vi" to "Tiếng Việt (VI)",
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
        val apiKey by prefs.apiKey().collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_yakuyomi_provider),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.provider(),
                    entries = persistentMapOf(
                        "openrouter" to "OpenRouter",
                        "gemini" to "Gemini",
                        "custom_openai" to "Custom OpenAI",
                    ),
                    title = stringResource(KMR.strings.pref_yakuyomi_provider),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.apiKey(),
                    title = stringResource(KMR.strings.pref_yakuyomi_api_key),
                    subtitle = if (apiKey.isBlank()) "Not set — will use offline fallback" else "••••••••",
                    enabled = enabled,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.model(),
                    title = stringResource(KMR.strings.pref_yakuyomi_model),
                    subtitle = if (provider == "gemini") "Gemini model (e.g. gemini-1.5-flash)" else "OpenRouter/Custom model (default: google/gemma-2-9b-it:free)",
                    enabled = enabled,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.customBaseUrl(),
                    title = "Custom API Base URL",
                    subtitle = "Base URL for OpenAI-compatible endpoint, e.g. https://api.example.com/v1",
                    enabled = enabled && provider == "custom_openai",
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.customHeaders(),
                    title = "Custom Headers",
                    subtitle = "Key: Value per line, e.g. X-API-Key: abc",
                    enabled = enabled && provider == "custom_openai",
                ),
            ),
        )
    }

    @Composable
    private fun getModelGroup(modelManager: exh.yakuyomi.ModelManager): Preference.PreferenceGroup {
        val status by modelManager.status.collectAsState()
        val actionTitle = when (status.state) {
            exh.yakuyomi.ModelManager.State.DOWNLOADING -> stringResource(KMR.strings.mtl_models_cancel)
            exh.yakuyomi.ModelManager.State.READY -> stringResource(KMR.strings.mtl_models_redownload)
            else -> stringResource(KMR.strings.mtl_models_download)
        }
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.mtl_models_title),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(KMR.strings.mtl_models_title),
                    content = {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            when (status.state) {
                                exh.yakuyomi.ModelManager.State.READY -> {
                                    Text(
                                        text = stringResource(KMR.strings.mtl_models_ready, status.downloadedBytes / 1024),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                exh.yakuyomi.ModelManager.State.DOWNLOADING -> {
                                    val percent = (status.progress * 100).toInt()
                                    Text(
                                        text = stringResource(KMR.strings.mtl_models_downloading, percent),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(modifier = Modifier.padding(vertical = 4.dp))
                                    LinearProgressIndicator(
                                        progress = { status.progress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (!status.currentFile.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.padding(vertical = 4.dp))
                                        Text(
                                            text = "File: ${status.currentFile}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                exh.yakuyomi.ModelManager.State.ERROR -> {
                                    Text(
                                        text = stringResource(KMR.strings.mtl_models_error, status.error ?: "unknown"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                else -> {
                                    Text(
                                        text = stringResource(KMR.strings.mtl_models_missing),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = actionTitle,
                    onClick = {
                        if (status.state == exh.yakuyomi.ModelManager.State.DOWNLOADING) {
                            modelManager.cancelDownload()
                        } else {
                            modelManager.startDownload()
                        }
                    },
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
                    subtitle = "Keep original art when the API call fails (otherwise pages are marked failed and retried)",
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
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.saveTranslatedPages(),
                    title = "Save translated pages to chapter folder",
                    subtitle = "Keep translated WEBP images alongside originals to avoid re-translating",
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.autoSaveWhileReading(),
                    title = "Auto-save while reading",
                    subtitle = "Save translated pages as you read to avoid re-translating later",
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
