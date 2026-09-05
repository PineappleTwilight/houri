package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import exh.yakuyomi.ModelCatalog
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
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

        return listOfNotNull(
            getHeader(),
            getStatusOverview(),
            getGeneralGroup(prefs),
            getProviderGroup(prefs),
            getLocalLlmGroup(),
            getModelGroup(modelManager),
            getBehaviorGroup(prefs, cache),
            getAdvancedGroup(prefs),
        )
    }

    @Composable
    private fun getHeader(): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = "",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = "",
                    content = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "文",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Spacer(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = "AI Manga Translation",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "On-device + Cloud · Per-manga · Cached",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                ),
            ),
        )
    }

    /** At-a-glance MTL state: enabled/provider/engine/model — the first thing a user sees. */
    @Composable
    private fun getStatusOverview(): Preference.PreferenceGroup {
        val prefs = remember { globalAppGraph.translationPreferences }
        val manager = remember { globalAppGraph.localLlmManager }
        val enabled by prefs.enabled().collectAsState()
        val provider by prefs.provider().collectAsState()
        val running by manager.running.collectAsState()
        val loading by manager.loading.collectAsState()
        val context = LocalContext.current

        val (dotColor, lines) = when {
            !enabled -> Color(0xFF9E9E9E) to listOf("MTL is off — enable it below to translate pages")
            provider == "local" -> {
                val model = manager.resolveModel()
                when {
                    loading -> Color(0xFFFFA726) to listOf("Local engine: starting…")
                    running -> Color(0xFF4CAF50) to listOf("Local engine running — ${model?.displayName ?: "unknown model"}")
                    manager.isModelReady() -> Color(0xFF9E9E9E) to listOf("Local engine stopped — ${model?.displayName ?: "auto"} ready")
                    else -> Color(0xFFE53935) to listOf("Local provider: no model downloaded yet")
                }
            }
            else -> Color(0xFF4CAF50) to listOf("Cloud provider: ${provider.ifBlank { "openrouter" }}")
        }

        val subtitle = buildString {
            append("MTL: ${if (enabled) "on" else "off"}")
            if (enabled && provider != "local") append(" · target ${prefs.targetLang().get().ifBlank { "en" }}")
            if (!exh.yakuyomi.DeviceMemory.isMtlSupported(context)) append(" · low-RAM device (translation blocked)")
        }

        return Preference.PreferenceGroup(
            title = "Status",
            preferenceItems = listOf(
                Preference.PreferenceItem.CustomPreference(
                    title = subtitle,
                    content = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Text(
                                text = lines.joinToString("\n"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                ),
            ).toPersistentList(),
        )
    }

    @Composable
    private fun getGeneralGroup(prefs: exh.yakuyomi.TranslationPreferences): Preference.PreferenceGroup {
        val enabled by prefs.enabled().collectAsState()
        val context = LocalContext.current
        val lowRam = !exh.yakuyomi.DeviceMemory.isMtlSupported(context)
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_yakuyomi_enabled),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.enabled(),
                        title = stringResource(KMR.strings.pref_yakuyomi_enabled),
                        subtitle = stringResource(KMR.strings.pref_yakuyomi_enabled_summary),
                        enabled = !lowRam,
                    ),
                )
                if (lowRam) {
                    add(
                        Preference.PreferenceItem.InfoPreference(
                            title = "This device has too little RAM for AI translation (needs 3GB+). " +
                                "Translation is disabled to avoid crashes. Reading, downloads and all other features are unaffected.",
                        ),
                    )
                }
                add(
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
                )
                add(
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
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.translationTextColor(),
                        entries = persistentMapOf(
                            0xFF000000.toInt() to "Black (default)",
                            0xFFFFFFFF.toInt() to "White",
                            0xFF0000FF.toInt() to "Blue",
                            0xFFFF0000.toInt() to "Red",
                            0xFF008000.toInt() to "Green",
                            0xFFFF8C00.toInt() to "Orange",
                            0xFF800080.toInt() to "Purple",
                        ),
                        title = stringResource(KMR.strings.pref_yakuyomi_text_color),
                        subtitle = stringResource(KMR.strings.pref_yakuyomi_text_color_summary) + ": %s",
                        enabled = enabled,
                    ),
                )
            }.toPersistentList(),
        )
    }

    @Composable
    private fun getProviderGroup(prefs: exh.yakuyomi.TranslationPreferences): Preference.PreferenceGroup {
        val enabled by prefs.enabled().collectAsState()
        val provider by prefs.provider().collectAsState()
        val apiKey by prefs.apiKeyForProvider(provider).collectAsState()
        val baseUrl by prefs.customBaseUrl().collectAsState()
        val model by prefs.modelForProvider(provider).collectAsState()
        val legacyApiKey by prefs.apiKey().collectAsState()
        val legacyModel by prefs.model().collectAsState()
        LaunchedEffect(provider) {
            if (prefs.apiKeyForProvider(provider).get().isBlank() && legacyApiKey.isNotBlank()) {
                prefs.apiKeyForProvider(provider).set(legacyApiKey)
            }
            if (prefs.modelForProvider(provider).get().isBlank() && legacyModel.isNotBlank()) {
                prefs.modelForProvider(provider).set(legacyModel)
            }
        }

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

        val isLocalProvider = provider == "local"

        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_yakuyomi_provider),
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.geminiNanoEnabled(),
                        title = stringResource(KMR.strings.pref_yakuyomi_gemini_nano),
                        subtitle = stringResource(KMR.strings.pref_yakuyomi_gemini_nano_summary),
                        enabled = enabled,
                    ),
                )
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = "Gemini Nano device status",
                        content = {
                            var statusText by remember { mutableStateOf("Checking…") }
                            LaunchedEffect(Unit) {
                                statusText = withIOContext {
                                    val nano = globalAppGraph.geminiNanoTranslator
                                    when {
                                        !prefs.geminiNanoEnabled().get() -> "Disabled by toggle — cloud provider will be used"
                                        nano.isAvailable() -> "Available — on-device translation active"
                                        else -> {
                                            val err = nano.statusError()
                                            if (!err.isNullOrBlank() && (
                                                    err.contains("601") || err.contains("BINDING") ||
                                                        err.contains("606") || err.contains("FEATURE_NOT_FOUND")
                                                    )
                                            ) {
                                                "AICore is still setting up (${err.substringAfter("error code ").substringBefore(":").trim()}) — " +
                                                    "update Google AI Core / restart the device, then check again. Cloud provider will be used meanwhile."
                                            } else {
                                                "Not available on this device right now — cloud provider will be used. " +
                                                    "If this device supports Gemini Nano (e.g. Pixel 8+/Samsung S24+), AICore may still be provisioning — " +
                                                    "update Google AI Core and restart, then re-open this screen."
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
                            )
                        },
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.provider(),
                        entries = persistentMapOf(
                            "openrouter" to "OpenRouter",
                            "gemini" to "Gemini",
                            "opencode_zen" to "OpenCode Zen",
                            "nvidia_nim" to "NVIDIA NIM",
                            "custom_openai" to "Custom OpenAI",
                            "local" to "Local (On-device LLM)",
                        ),
                        title = stringResource(KMR.strings.pref_yakuyomi_provider),
                        enabled = enabled,
                    ),
                )
                // KMK --> Cloud-only options are irrelevant while the local provider is selected.
                if (!isLocalProvider) {
                    add(
                        Preference.PreferenceItem.EditTextPreference(
                            preference = prefs.apiKeyForProvider(provider),
                            title = stringResource(KMR.strings.pref_yakuyomi_api_key),
                            subtitle = apiKeySubtitle,
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.ListPreference(
                            preference = prefs.modelForProvider(provider),
                            entries = entries,
                            title = stringResource(KMR.strings.pref_yakuyomi_model),
                            subtitle = "%s",
                            subtitleProvider = { v, _ -> v },
                            enabled = enabled,
                        ),
                    )
                    add(
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
                    )
                }
                // KMK <--
                add(
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
                )
                add(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.customHeaders(),
                        title = "Custom Headers",
                        subtitle = "Key: Value per line, e.g. X-API-Key: abc",
                        enabled = enabled && provider in setOf("custom_openai", "opencode_zen", "nvidia_nim"),
                    ),
                )
            }.toPersistentList(),
        )
    }

    @Composable
    private fun getLocalLlmGroup(): Preference.PreferenceGroup? {
        val prefs = remember { globalAppGraph.translationPreferences }
        val manager = remember { globalAppGraph.localLlmManager }
        val downloadManager = remember { globalAppGraph.localLlmDownloadManager }
        val context = LocalContext.current
        val enabled by prefs.enabled().collectAsState()
        val provider by prefs.provider().collectAsState()
        val localEnabled = enabled && provider == "local"
        // KMK --> The local group is irrelevant unless the local provider is selected.
        if (!localEnabled) return null
        // KMK <--
        val status by downloadManager.status.collectAsState()
        val running by manager.running.collectAsState()
        val loading by manager.loading.collectAsState()
        val autoStart by prefs.localLlmAutoStart().collectAsState()
        val entryNavigator = LocalNavigator.currentOrThrow
        val model = remember(provider, enabled) { manager.resolveModel() }
        val best = remember { exh.yakuyomi.LocalLlmCatalog.bestForDevice(exh.yakuyomi.DeviceMemory.totalRamBytes(context)) }
        val runtimeBundled = remember { manager.isRuntimeAvailable() }
        val modelReady = remember { manager.isModelReady() }

        var importTick by remember { mutableStateOf(0) }
        val importing by manager.importing.collectAsState()
        val importedModels = remember(importTick) { manager.importedModels() }

        val entries = remember(importTick) {
            val base = persistentMapOf<String, String>("" to "Auto — best for this device")
            val catalog = exh.yakuyomi.LocalLlmCatalog.allModels.associate { it.id to it.displayName }
            val imported = manager.importedModels().associate { it.id to it.displayName }
            persistentMapOf(*((base + catalog + imported).toList()).toTypedArray())
        }

        // KMK --> Custom GGUF import: background copy (never freezes the UI for multi-GB files),
        // original filename preserved, re-imports detected and just re-selected.
        // KMK <--
        val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                manager.importGguf(uri) { result, error ->
                    if (result != null) {
                        importTick++
                        if (result.duplicate) {
                            context.toast("Already imported — switched to ${result.model.displayName}")
                        } else {
                            context.toast("Imported ${result.model.displayName}")
                        }
                    } else {
                        context.toast("Failed to import GGUF: ${error ?: "unknown error"}")
                    }
                }
            }
        }

        val actionTitle = when (status.state) {
            exh.yakuyomi.LocalLlmDownloadManager.State.DOWNLOADING -> "Cancel download"
            exh.yakuyomi.LocalLlmDownloadManager.State.READY -> "Redownload"
            else -> "Download model"
        }

        val modelSubtitle = when (val m = model) {
            null -> "No model fits this device"
            else -> {
                val tier = when (m.qualityTier) {
                    5 -> "Best"
                    4 -> "High"
                    3 -> "Good"
                    else -> "Basic"
                }
                val vision = if (m.supportsVision) " · vision" else ""
                val finetune = if (m.isTranslationFinetune) " · TL finetune" else ""
                "$tier · ${m.paramsB}$vision$finetune · ${m.sizeBytes / (1024 * 1024)} MB"
            }
        }

        val engineState = when {
            running -> "Running — ${model?.displayName ?: "unknown model"}"
            loading -> "Starting… — ${model?.displayName ?: "selected model"}"
            modelReady -> "Stopped — ${model?.displayName ?: "auto"} ready"
            else -> "Stopped — no model downloaded yet"
        }

        return Preference.PreferenceGroup(
            title = "Local (On-device LLM)",
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.InfoPreference(
                        title = "Offline LLM (GGUF) — no API key needed. Pick a model or import your own .gguf.",
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.localModel(),
                        entries = entries,
                        title = "Local model",
                        subtitleProvider = { v, _ -> if (v.isNullOrBlank()) "Auto — ${best?.displayName ?: "none"}" else modelSubtitle },
                        enabled = localEnabled,
                    ),
                )
                add(
                    Preference.PreferenceItem.InfoPreference(
                        title = buildString {
                            append("Best for this device: ${best?.displayName ?: "none"}")
                            if (!runtimeBundled) {
                                append(" · Runtime not bundled in this build")
                            } else {
                                append(" · Runtime ready (3GB+ RAM required)")
                            }
                        },
                    ),
                )
                // KMK --> Per-model llama.cpp sampling/context editor; only reachable with a model.
                if (model != null) {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KMR.strings.pref_yakuyomi_llm_advanced),
                            subtitle = "Sampling, context and threads for ${model.displayName}",
                            onClick = {
                                entryNavigator.push(SettingsYakuyomiLlmAdvancedScreen(model))
                            },
                            enabled = localEnabled,
                        ),
                    )
                }
                // KMK <--
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = if (importing) "Importing GGUF…" else "Import GGUF from device…",
                        subtitle = when {
                            importing -> "Copying to app storage — this can take a while for large files…"
                            importedModels.isEmpty() -> "Pick a .gguf file (its filename is kept; re-importing just switches to it)"
                            else -> "Imported: ${importedModels.joinToString(", ") { it.displayName }}"
                        },
                        onClick = {
                            importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        enabled = localEnabled && !importing,
                    ),
                )
                // KMK --> Engine lifecycle controls; only actionable once a loadable model exists.
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = "Engine status",
                        content = {
                            val dotColor = when {
                                running -> Color(0xFF4CAF50)
                                loading -> Color(0xFFFFA726)
                                modelReady -> Color(0xFF9E9E9E)
                                else -> Color(0xFFE53935)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(dotColor),
                                )
                                Text(
                                    text = engineState,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        },
                    ),
                )
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = if (running) "Stop engine" else "Start engine",
                        subtitle = if (modelReady) {
                            "Loads ${model?.displayName ?: "the selected model"} into memory (first translation can take a few seconds otherwise)"
                        } else {
                            "Download a model first — this is enabled once a model is ready"
                        },
                        onClick = {
                            if (running) manager.stop() else manager.start()
                        },
                        enabled = localEnabled && modelReady,
                    ),
                )
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.localLlmAutoStart(),
                        title = "Start engine on app launch",
                        subtitle = "Preload the model on startup so first translation is instant",
                        enabled = localEnabled && modelReady,
                    ),
                )
                // KMK <--
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = "Download",
                        content = {
                            Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp)) {
                                when (status.state) {
                                    exh.yakuyomi.LocalLlmDownloadManager.State.READY -> {
                                        Text(
                                            text = "Installed: ${status.downloadedBytes / (1024 * 1024)} MB",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    exh.yakuyomi.LocalLlmDownloadManager.State.DOWNLOADING -> {
                                        val percent = (status.progress * 100).toInt()
                                        val mb = status.downloadedBytes / (1024 * 1024)
                                        val totalMb = (status.totalBytes / (1024 * 1024)).coerceAtLeast(mb)
                                        val speedMb = status.speedBytesPerSecond / (1024 * 1024)
                                        Text(
                                            text = "Downloading $percent% — $mb MB / $totalMb MB" +
                                                if (speedMb > 0) " · $speedMb MB/s" else "",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (status.etaSeconds > 0) {
                                            Spacer(modifier = Modifier.padding(vertical = 2.dp))
                                            Text(
                                                text = "About ${formatEta(status.etaSeconds)} left",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        Spacer(modifier = Modifier.padding(vertical = 4.dp))
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
                                    exh.yakuyomi.LocalLlmDownloadManager.State.ERROR -> {
                                        Text(
                                            text = status.error ?: "Download failed",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    else -> {
                                        Text(
                                            text = "Not installed",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        },
                    ),
                )
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = actionTitle,
                        onClick = {
                            when (status.state) {
                                exh.yakuyomi.LocalLlmDownloadManager.State.DOWNLOADING -> manager.cancelDownload()
                                else -> manager.startDownload()
                            }
                        },
                        enabled = localEnabled && manager.isRuntimeAvailable(),
                    ),
                )
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = "Clear local model",
                        subtitle = if (model?.isCustom == true) {
                            "Delete the selected imported GGUF"
                        } else {
                            "Remove the downloaded model files (must re-download to use again)"
                        },
                        onClick = {
                            manager.clearModel()
                            importTick++
                            context.toast("Local model cleared")
                        },
                        enabled = localEnabled && status.state != exh.yakuyomi.LocalLlmDownloadManager.State.DOWNLOADING,
                    ),
                )
            }.toPersistentList(),
        )
    }

    @Composable
    private fun getModelGroup(modelManager: exh.yakuyomi.ModelManager): Preference.PreferenceGroup {
        val status by modelManager.status.collectAsState()
        val context = LocalContext.current
        val lowRam = !exh.yakuyomi.DeviceMemory.isMtlSupported(context)
        val modelsClearedText = stringResource(KMR.strings.mtl_models_cleared)
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
                                        text = stringResource(KMR.strings.mtl_models_ready, status.downloadedBytes / (1024 * 1024)),
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
                        when (status.state) {
                            exh.yakuyomi.ModelManager.State.DOWNLOADING -> modelManager.cancelDownload()
                            exh.yakuyomi.ModelManager.State.READY -> modelManager.startDownload(force = true)
                            else -> modelManager.startDownload()
                        }
                    },
                    enabled = !lowRam,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.mtl_models_clear),
                    subtitle = stringResource(KMR.strings.mtl_models_clear_summary),
                    onClick = {
                        modelManager.clearModels()
                        context.toast(modelsClearedText)
                    },
                    enabled = !lowRam && status.state != exh.yakuyomi.ModelManager.State.DOWNLOADING,
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
        var cacheBytes by remember { mutableStateOf(cache.sizeBytes()) }
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
                Preference.PreferenceItem.TextPreference(
                    title = "Clear translation cache",
                    subtitle = "Current: ${cacheBytes / 1024} KB / 32768 KB",
                    onClick = {
                        cache.clearAll()
                        cacheBytes = cache.sizeBytes()
                        context.toast("Translation cache cleared")
                    },
                    enabled = enabled,
                ),
            ),
        )
    }

    @Composable
    private fun getAdvancedGroup(prefs: exh.yakuyomi.TranslationPreferences): Preference.PreferenceGroup {
        val enabled by prefs.enabled().collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        return Preference.PreferenceGroup(
            title = "Advanced",
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_yakuyomi_engine_advanced),
                        subtitle = "Detector / OCR / Inpaint / Typeset — not recommended, unsupported",
                        onClick = { navigator.push(SettingsYakuyomiEngineAdvancedScreen()) },
                        enabled = enabled,
                    ),
                )
                add(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.translationTextColorHex(),
                        title = stringResource(KMR.strings.pref_yakuyomi_text_color_custom),
                        subtitle = "Custom hex (e.g. #FFFFFF) — overrides preset: %s",
                        validator = { it.isBlank() || HEX_COLOR_REGEX.matches(it.trim().removePrefix("#")) },
                        enabled = enabled,
                    ),
                )
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.autoSaveWhileReading(),
                        title = "Auto-save while reading",
                        subtitle = "Save translated pages as you read",
                        enabled = enabled,
                    ),
                )
                add(
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
                )
                add(
                    Preference.PreferenceItem.InfoPreference(
                        title = "EN → EN uses grammar/vocab fix (same LLM). Uploads gated by Incognito/Censor.",
                    ),
                )
            }.toPersistentList(),
        )
    }
}

// 6-digit (RRGGBB) or 8-digit (AARRGGBB) hex color, no '#'.
private val HEX_COLOR_REGEX = Regex("^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")

private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}
