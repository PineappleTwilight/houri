package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import exh.yakuyomi.ModelCatalog
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.withIOContext
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
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.fontFamily(),
                    entries = persistentMapOf(
                        "casual" to "Manga (Casual)",
                        "sans-serif" to "Sans-serif",
                        "sans-serif-condensed" to "Sans-serif Condensed",
                        "serif" to "Serif",
                        "serif-monospace" to "Serif Monospace",
                        "monospace" to "Monospace",
                        "cursive" to "Cursive",
                        "default" to "System default",
                    ),
                    title = stringResource(KMR.strings.pref_yakuyomi_font_family),
                    subtitle = stringResource(KMR.strings.pref_yakuyomi_font_family_summary) + ": %s",
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
        val baseUrl by prefs.customBaseUrl().collectAsState()
        val model by prefs.model().collectAsState()

        var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
        var fetchingModels by remember { mutableStateOf(false) }
        var modelFetchFailed by remember { mutableStateOf(false) }
        var refreshTick by remember { mutableStateOf(0) }

        // Populate the model selector automatically from the provider's models endpoint.
        LaunchedEffect(provider, apiKey, baseUrl, refreshTick) {
            fetchingModels = true
            val models = withIOContext {
                runCatching {
                    ModelCatalog.fetchModels(provider, apiKey, baseUrl, globalAppGraph.networkHelper.client)
                }.getOrDefault(emptyList())
            }
            if (models.isNotEmpty()) {
                fetchedModels = models
                modelFetchFailed = false
            } else {
                modelFetchFailed = true
            }
            fetchingModels = false
        }

        val entries = remember(fetchedModels, provider, model) {
            // Always seed with the curated list so the selector works offline; fetched models
            // from the provider endpoint extend it. The current value is always included.
            val base = ModelCatalog.fallbackModels[provider].orEmpty()
            val all = (fetchedModels + base + listOfNotNull(model)).distinct().sorted()
            persistentMapOf(*all.map { it to it }.toTypedArray())
        }

        val apiKeySubtitle = when {
            apiKey.isBlank() -> stringResource(KMR.strings.pref_yakuyomi_api_key_missing)
            else -> apiKey.take(4) + "••••••••"
        }

        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_yakuyomi_provider),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.provider(),
                    entries = persistentMapOf(
                        "openrouter" to "OpenRouter",
                        "gemini" to "Gemini",
                        "opencode_zen" to "OpenCode Zen",
                        "nvidia_nim" to "NVIDIA NIM",
                        "custom_openai" to "Custom OpenAI",
                    ),
                    title = stringResource(KMR.strings.pref_yakuyomi_provider),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.apiKey(),
                    title = stringResource(KMR.strings.pref_yakuyomi_api_key),
                    subtitle = apiKeySubtitle,
                    enabled = enabled,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.model(),
                    entries = entries,
                    title = stringResource(KMR.strings.pref_yakuyomi_model),
                    subtitle = "%s",
                    subtitleProvider = { v, _ -> v },
                    enabled = enabled,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_yakuyomi_model_refresh),
                    subtitle = when {
                        fetchingModels -> stringResource(KMR.strings.pref_yakuyomi_model_fetching)
                        modelFetchFailed -> stringResource(KMR.strings.pref_yakuyomi_model_fetch_failed)
                        fetchedModels.isNotEmpty() ->
                            stringResource(KMR.strings.pref_yakuyomi_model_fetch_done, fetchedModels.size)
                        else -> null
                    },
                    onClick = {
                        refreshTick++
                    },
                    enabled = enabled,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.customBaseUrl(),
                    title = "Custom API Base URL",
                    subtitle = if (provider == "custom_openai" && baseUrl.isBlank()) {
                        stringResource(KMR.strings.pref_yakuyomi_base_url_required)
                    } else {
                        "Base URL for OpenAI-compatible endpoint, e.g. https://api.example.com/v1"
                    },
                    enabled = enabled && provider == "custom_openai",
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.customHeaders(),
                    title = "Custom Headers",
                    subtitle = "Key: Value per line, e.g. X-API-Key: abc",
                    enabled = enabled && provider in setOf("custom_openai", "opencode_zen", "nvidia_nim"),
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
                                    // Padding on both sides so the bar doesn't touch the screen edges.
                                    LinearProgressIndicator(
                                        progress = { status.progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
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