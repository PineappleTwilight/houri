package exh.yakuyomi

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.system.logcat
import java.io.File

/** Result of importing a custom GGUF: the model to select and whether it already existed. */
data class GgufImportResult(
    val model: LocalLlmModel,
    val duplicate: Boolean,
)

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

    // ------------------------------------------------------------------ custom GGUF imports
    private val customDir = File(context.filesDir, "local_llm_models/custom")

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Imported custom GGUFs (one [LocalLlmModel] per file in the custom dir). */
    fun importedModels(): List<LocalLlmModel> {
        val dir = customDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.length() > 1_000_000L && it.extension.equals("gguf", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { customModelFor(it) }
    }

    private fun customModelFor(file: File): LocalLlmModel {
        val mmprojCandidate = File(file.parentFile, "${file.nameWithoutExtension}.mmproj")
            .takeIf { it.exists() && it.length() > 1_000_000L }
            ?: File(file.parentFile, "${file.nameWithoutExtension}_mmproj.gguf")
                .takeIf { it.exists() && it.length() > 1_000_000L }
        return LocalLlmModel(
            id = "custom:${file.name}",
            displayName = file.nameWithoutExtension,
            description = "User-imported GGUF" + if (mmprojCandidate != null) " + vision" else "",
            paramsB = "?",
            qualityTier = 3,
            isTranslationFinetune = false,
            supportsVision = mmprojCandidate != null,
            sizeBytes = file.length() + (mmprojCandidate?.length() ?: 0L),
            minRamBytes = 3L * 1024 * 1024 * 1024,
            ggufFile = file.absolutePath,
            mmprojFile = mmprojCandidate?.absolutePath,
            mmprojRepo = if (mmprojCandidate != null) "custom" else null,
            isCustom = true,
        )
    }

    private fun sanitizeGgufName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "model.gguf" }
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "model.gguf" }
    }

    private fun uniquifyGgufName(dir: File, name: String): File {
        val stem = name.replace(Regex("\\.gguf$"), "")
        var i = 2
        while (true) {
            val candidate = File(dir, "$stem-$i.gguf")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "model.gguf"

    /**
     * Imports a GGUF picked from device storage: copies it into the custom dir on a background
     * thread (so a multi-GB file never freezes the UI), preserves the original filename, and
     * detects re-imports of an already-imported file (same name + size) — in that case it just
     * switches the selection instead of copying again. Selects the model either way.
     */
    fun importGguf(uri: Uri, onResult: (GgufImportResult?, error: String?) -> Unit) {
        if (_importing.value) return
        _importing.value = true
        scope.launch {
            val result = runCatching {
                val name = sanitizeGgufName(displayName(uri))
                val dir = customDir.apply { mkdirs() }
                val target = File(dir, name)
                if (target.exists() && target.length() > 1_000_000L) {
                    // Same file already imported (name + size match): reuse, just switch to it.
                    GgufImportResult(customModelFor(target), duplicate = true)
                } else {
                    val tmp = File(dir, "$name.tmp")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                    } ?: throw IllegalStateException("Cannot open the selected file")
                    if (tmp.length() < 1_000_000L) {
                        tmp.delete()
                        throw IllegalStateException("Not a valid GGUF (file too small)")
                    }
                    // A different file colliding with an existing name: keep both.
                    val final = if (target.exists()) uniquifyGgufName(dir, name) else target
                    if (!tmp.renameTo(final)) {
                        tmp.copyTo(final, overwrite = true)
                        tmp.delete()
                    }
                    GgufImportResult(customModelFor(final), duplicate = false)
                }
            }.onFailure { e ->
                logcat { "GGUF import failed: ${e.message}" }
            }
            _importing.value = false
            val ok = result.getOrNull()
            ok?.let { prefs.localModel().set(it.model.id) }
            // Callers (UI toasts) need the main looper; the copy ran on a background thread.
            Handler(Looper.getMainLooper()).post {
                onResult(ok, result.exceptionOrNull()?.message)
            }
        }
    }
    // ------------------------------------------------------------------
    private val samplingJson = Json { ignoreUnknownKeys = true }
    private val samplingMapSerializer = MapSerializer(String.serializer(), LocalLlmSamplingConfig.serializer())

    private fun samplingOverrides(): Map<String, LocalLlmSamplingConfig> {
        val raw = prefs.localLlmSamplingOverrides().get()
        if (raw.isBlank()) return emptyMap()
        return runCatching { samplingJson.decodeFromString(samplingMapSerializer, raw) }.getOrDefault(emptyMap())
    }

    private fun persistSampling(overrides: Map<String, LocalLlmSamplingConfig>) {
        prefs.localLlmSamplingOverrides().set(
            if (overrides.isEmpty()) "" else samplingJson.encodeToString(samplingMapSerializer, overrides),
        )
    }

    /** Effective llama.cpp sampling config for [model]: stored override, else the model's own
     *  recommended defaults, else sensible generics. */
    fun samplingFor(model: LocalLlmModel): LocalLlmSamplingConfig {
        samplingOverrides()[model.id]?.let { return it }
        model.defaultSampling?.let { return it }
        return LocalLlmSamplingConfig(
            temperature = if (model.isTranslationFinetune) 0.0f else 0.3f,
            contextLength = model.contextLength,
        )
    }

    /** Persist a per-model sampling override (all fields; [LocalLlmSamplingConfig] is a value object). */
    fun setSampling(modelId: String, config: LocalLlmSamplingConfig) {
        persistSampling(samplingOverrides() + (modelId to config))
    }

    /** Drop the per-model override so the model falls back to defaults. */
    fun resetSampling(modelId: String) {
        persistSampling(samplingOverrides() - modelId)
    }

    /** Look up any selectable model: catalog first, then imported custom GGUFs. */
    fun modelById(id: String): LocalLlmModel? {
        LocalLlmCatalog.byId(id)?.let { return it }
        return importedModels().find { it.id == id }
    }
    // ------------------------------------------------------------------

    /** Resolves the selected model; custom imports win, then the catalog preference, then best-fit. */
    fun resolveModel(): LocalLlmModel? {
        val selected = prefs.localModel().get()
        if (selected.startsWith("custom:")) {
            val file = File(customDir, selected.removePrefix("custom:"))
            if (file.exists() && file.length() > 1_000_000L) return customModelFor(file)
            // The imported file is gone; clear the selection and fall through.
            prefs.localModel().set("")
        }
        LocalLlmCatalog.byId(selected)?.let { return it }
        // One-time migration from the old single-custom importer (path stored in localModelFile).
        if (selected.isBlank()) {
            val legacy = prefs.localModelFile().get()
            if (legacy.isNotBlank()) {
                val file = File(legacy)
                if (file.exists() && file.length() > 1_000_000L) {
                    val model = customModelFor(file)
                    prefs.localModelFile().set("")
                    prefs.localModel().set(model.id)
                    return model
                }
            }
        }
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
            val toClose = backendMutex.withLock {
                val c = current
                current = null
                _running.value = false
                c
            }
            toClose?.second?.let { backend ->
                runCatching { backend.close() }.onFailure { logcat { "LocalLlm stop failed: ${it.message}" } }
            }
        }
    }

    fun clearModel() {
        val model = resolveModel() ?: return
        scope.launch {
            val toClose = backendMutex.withLock {
                val c = current
                current = null
                _running.value = false
                c
            }
            toClose?.second?.let { backend ->
                runCatching { backend.close() }.onFailure { logcat { "LocalLlm clear close failed: ${it.message}" } }
            }
        }
        if (model.isCustom) {
            model.ggufFile?.let { File(it).delete() }
            prefs.localModel().set("")
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
        val temperature = samplingFor(model).temperature
        return backend.generate(LocalGenerateRequest(prompt = prompt, maxTokens = 1024, temperature = temperature, imageBytes = imageBytes))
    }

    private suspend fun backendFor(model: LocalLlmModel): LocalLlmBackend? = backendMutex.withLock {
        current?.let { (m, b) -> if (m.id == model.id) return b }
        current?.second?.close()
        current = null
        _running.value = false
        val dir = downloadManager.modelDir(model)
        val backend = LlamaCppLlmBackend.create(model, dir, samplingFor(model)) { msg -> logcat { "llama.cpp: $msg" } }
        if (backend != null) {
            current = model to backend
            _running.value = true
        }
        backend
    }

    fun closeAll() {
        scope.launch {
            val toClose = backendMutex.withLock {
                val c = current
                current = null
                _running.value = false
                c
            }
            toClose?.second?.let { backend ->
                runCatching { backend.close() }.onFailure { logcat { "LocalLlm closeAll failed: ${it.message}" } }
            }
        }
    }
}
