package exh.yakuyomi

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.log.xLogD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Owns the lifecycle of the on-device ("local") LLM provider: resolves the selected model from
 * [LocalLlmCatalog] (the best-fit model is presented as the default, but only the RAM gate is
 * enforced), lazily builds and caches the native backend for that model, and runs generations.
 * Only one backend is kept alive at a time — switching models closes the previous one.
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

    @Volatile
    private var current: Pair<LocalLlmModel, LocalLlmBackend>? = null

    fun isLocalProvider(): Boolean = prefs.provider().get().equals("local", ignoreCase = true)

    /** Resolves the selected model; falls back to the best-fitting one when nothing is selected. */
    fun resolveModel(): LocalLlmModel? {
        val selected = LocalLlmCatalog.byId(prefs.localModel().get())
        if (selected != null) return selected
        return LocalLlmCatalog.bestForDevice(DeviceMemory.totalRamBytes(context))
    }

    /** Whether the resolved model's weights have been downloaded. */
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

    fun clearModel() {
        val model = resolveModel() ?: return
        scope.launch {
            backendMutex.withLock {
                current?.second?.close()
                current = null
            }
        }
        downloadManager.clearModel(model)
    }

    /** Backend type actually in use for the resolved model (or null when unavailable). */
    fun activeBackendType(): LocalLlmBackendType? {
        val model = resolveModel() ?: return null
        return when {
            LocalLlmBackendType.MLC_GPU in model.backends -> LocalLlmBackendType.MLC_GPU
            model.backends.any { it == LocalLlmBackendType.EXECUTORCH_NPU } &&
                DeviceMemory.matchesSoc(DeviceMemory.socManufacturer(), model.etSoC ?: "") -> LocalLlmBackendType.EXECUTORCH_NPU
            LocalLlmBackendType.EXECUTORCH_CPU in model.backends -> LocalLlmBackendType.EXECUTORCH_CPU
            else -> null
        }
    }

    /** Whether the native runtime for the resolved model is bundled/available in this build. */
    fun isRuntimeAvailable(): Boolean {
        val type = activeBackendType() ?: return false
        return when (type) {
            LocalLlmBackendType.MLC_GPU -> MlcLlmBackend.isAvailable()
            LocalLlmBackendType.EXECUTORCH_NPU,
            LocalLlmBackendType.EXECUTORCH_CPU,
            -> ExecutorchLlmBackend.isAvailable()
        }
    }

    /**
     * Runs one on-device generation. [imageBytes] is passed to vision-capable models as page
     * context (augments, never replaces, the OCR lines). Returns null when the runtime is
     * unavailable, the model isn't downloaded, or generation failed.
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
        val dir = downloadManager.modelDir(model)
        val libFile: File? = dir.listFiles()?.firstOrNull { it.name.endsWith(".so") }
        val backend = when {
            LocalLlmBackendType.MLC_GPU in model.backends && MlcLlmBackend.isAvailable() ->
                MlcLlmBackend.create(model, dir, libFile) { msg -> xLogD("MLC backend: $msg") }
            model.backends.any { it == LocalLlmBackendType.EXECUTORCH_NPU } &&
                DeviceMemory.matchesSoc(DeviceMemory.socManufacturer(), model.etSoC ?: "") ->
                ExecutorchLlmBackend.create(context, model, dir) { msg -> xLogD("ExecuTorch NPU backend: $msg") }
            LocalLlmBackendType.EXECUTORCH_CPU in model.backends ->
                ExecutorchLlmBackend.create(context, model, dir) { msg -> xLogD("ExecuTorch CPU backend: $msg") }
            else -> null
        }
        if (backend != null) current = model to backend
        backend
    }

    fun closeAll() {
        scope.launch {
            backendMutex.withLock {
                current?.second?.close()
                current = null
            }
        }
    }
}
