package exh.yakuyomi

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.util.system.logcat

/**
 * Owns the lifecycle of the on-device ("local") LLM provider: resolves the selected model from
 * [LocalLlmCatalog] (best-fit presented, only RAM enforced) or a user-supplied custom GGUF,
 * lazily builds and caches the llama.cpp backend for that model, and runs generations.
 */
@SingleIn(AppScope::class)
@Inject
class LocalLlmManager(
    private val context: Context,
    private val prefs: TranslationPreferences,
    private val downloadManager: LocalLlmDownloadManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backendMutex = Mutex()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    @Volatile
    private var current: Pair<LocalLlmModel, LocalLlmBackend>? = null

    fun isLocalProvider(): Boolean = prefs.provider().get().equals("local", ignoreCase = true)

    /** Whether a model is loaded in memory (the engine is warm). */
    fun isRunning(): Boolean = _running.value

    /** Custom user-supplied GGUF (absolute path), or null. */
    private fun customModel(): LocalLlmModel? {
        val path = prefs.localModelFile().get()
        if (path.isBlank()) return null
        return LocalLlmModel(
            id = "custom",
            displayName = "Custom GGUF",
            description = "User-supplied GGUF",
            paramsB = "?",
            qualityTier = 3,
            isTranslationFinetune = false,
            supportsVision = false,
            sizeBytes = 0L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            ggufFile = path,
            isCustom = true,
        )
    }

    /** Resolves the selected model; custom GGUF wins, then the preference, then best-fit. */
    fun resolveModel(): LocalLlmModel? {
        customModel()?.let { return it }
        LocalLlmCatalog.byId(prefs.localModel().get())?.let { return it }
        return LocalLlmCatalog.bestForDevice(DeviceMemory.totalRamBytes(context))
    }

    /** Whether the resolved model's weights are downloaded (custom files count as ready). */
    fun isModelReady(): Boolean {
        val model = resolveModel() ?: return false
        return downloadManager.isDownloaded(model)
    }

    fun status(): LocalLlmDownloadManager.Status = downloadManager.status.value

    fun startDownload() {
        val model = resolveModel() ?: return
        downloadManager.startDownload(model)
    }

    fun cancelDownload() = downloadManager.cancelDownload()

    /** Warms the engine: eagerly loads the resolved model. No-op when it is already loaded. */
    fun start() {
        val model = resolveModel() ?: return
        if (!downloadManager.isDownloaded(model)) return
        _loading.value = true
        scope.launch {
            backendFor(model)
            _loading.value = false
        }
    }

    /** Unloads the model and frees the native memory. */
    fun stop() {
        _loading.value = false
        scope.launch {
            backendMutex.withLock {
                current?.second?.close()
                current = null
                _running.value = false
            }
        }
    }

    fun clearModel() {
        val model = resolveModel() ?: return
        scope.launch {
            backendMutex.withLock {
                current?.second?.close()
                current = null
                _running.value = false
            }
        }
        if (model.isCustom) {
            prefs.localModelFile().set("")
        } else {
            downloadManager.clearModel(model)
        }
    }

    /** Backend type actually in use for the resolved model (or null when unavailable). */
    fun activeBackendType(): LocalLlmBackendType? =
        if (resolveModel() != null) LocalLlmBackendType.LLAMACPP else null

    /** Whether the llama.cpp runtime is bundled in this build. */
    fun isRuntimeAvailable(): Boolean = LlamaCppLlmBackend.isAvailable()

    /**
     * Runs one on-device generation. Vision input is not supported by llama.cpp yet, so the
     * page image is ignored. Returns null when the runtime is unavailable, the model isn't
     * downloaded, or generation failed.
     */
    suspend fun generate(prompt: String, imageBytes: ByteArray? = null): String? {
        val model = resolveModel() ?: return null
        if (!downloadManager.isDownloaded(model)) {
            logcat { "Local LLM ${model.id} not downloaded yet" }
            return null
        }
        val backend = backendFor(model) ?: return null
        val temperature = if (model.isTranslationFinetune) 0.0f else 0.3f
        return backend.generate(LocalGenerateRequest(prompt = prompt, maxTokens = 1024, temperature = temperature, imageBytes = imageBytes))
    }

    private suspend fun backendFor(model: LocalLlmModel): LocalLlmBackend? = backendMutex.withLock {
        current?.let { (m, b) -> if (m.id == model.id) return b }
        current?.second?.close()
        current = null
        _running.value = false
        val dir = downloadManager.modelDir(model)
        val backend = LlamaCppLlmBackend.create(model, dir) { msg -> logcat { "llama.cpp: $msg" } }
        if (backend != null) {
            current = model to backend
            _running.value = true
        }
        backend
    }

    fun closeAll() {
        scope.launch {
            backendMutex.withLock {
                current?.second?.close()
                current = null
                _running.value = false
            }
        }
    }
}
