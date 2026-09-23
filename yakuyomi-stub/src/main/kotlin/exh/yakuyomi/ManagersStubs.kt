package exh.yakuyomi

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** No-op stub of [ModelManager] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class ModelManager {

    enum class State { NOT_INSTALLED, DOWNLOADING, READY, ERROR }

    data class Status(
        val state: State,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val currentFile: String? = null,
        val error: String? = null,
    ) {
        val progress: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    private val _status = MutableStateFlow(Status(State.NOT_INSTALLED))
    val status: StateFlow<Status> = _status.asStateFlow()

    fun isReady(): Boolean = false
    fun installedBytes(): Long = 0L
    fun refresh() = Unit
    fun verify(): Map<String, Boolean> = emptyMap()
    fun startDownload(force: Boolean = false) = Unit
    fun cancelDownload() = Unit
    fun clearModels() = Unit
    fun customUrlsActive(): Boolean = false
}

/** No-op stub of [LocalLlmDownloadManager] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class LocalLlmDownloadManager(
    @Suppress("unused") private val context: Context,
) {
    enum class State { NOT_INSTALLED, DOWNLOADING, READY, ERROR }

    data class Status(
        val state: State,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val currentFile: String? = null,
        val error: String? = null,
        val speedBytesPerSecond: Long = 0L,
        val etaSeconds: Long = -1L,
    ) {
        val progress: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    private val _status = MutableStateFlow(Status(State.NOT_INSTALLED))
    val status: StateFlow<Status> = _status.asStateFlow()

    fun modelDir(model: LocalLlmModel): File = File(context.filesDir, "local_llm_models/${model.id}")
    fun downloadedBytes(model: LocalLlmModel): Long = 0L
    fun isDownloaded(model: LocalLlmModel): Boolean = false
    fun startDownload(model: LocalLlmModel) = Unit
    fun cancelDownload() = Unit
    fun clearModel(model: LocalLlmModel) = Unit
}

/** Stub of [GgufImportResult] for the no-MTL APK variant. */
data class GgufImportResult(
    val model: LocalLlmModel,
    val duplicate: Boolean,
)

/** No-op stub of [LocalLlmManager] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class LocalLlmManager {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun isLocalProvider(): Boolean = false

    fun isRunning(): Boolean = false

    fun resolveModel(): LocalLlmModel? = null

    fun isModelReady(): Boolean = false

    fun status(): LocalLlmDownloadManager.Status = LocalLlmDownloadManager.Status(LocalLlmDownloadManager.State.NOT_INSTALLED)

    fun samplingFor(model: LocalLlmModel): LocalLlmSamplingConfig = LocalLlmSamplingConfig()

    fun setSampling(modelId: String, config: LocalLlmSamplingConfig) = Unit

    fun resetSampling(modelId: String) = Unit

    fun modelById(id: String): LocalLlmModel? = null

    fun importedModels(): List<LocalLlmModel> = emptyList()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    fun importGguf(uri: android.net.Uri, onResult: (GgufImportResult?, error: String?) -> Unit) {
        onResult(null, "No-MTL build")
    }

    fun start() = Unit

    fun stop() = Unit

    fun startDownload() = Unit

    fun cancelDownload() = Unit

    fun clearModel() = Unit

    fun activeBackendType(): LocalLlmBackendType? = null

    fun isRuntimeAvailable(): Boolean = false

    suspend fun generate(prompt: String, imageBytes: ByteArray? = null): String? = null

    fun closeAll() = Unit
}

/** Stub of [MangaInfoTranslation] — same shape as the real one. */
// KMK --> Mirrors the engine identity fields so the app compiles for both flavors.
@Serializable
data class MangaInfoTranslation(
    val title: String,
    val description: String? = null,
    val sourceFingerprint: String = "",
    val targetLanguage: String = "",
    val provider: String = "",
    val model: String = "",
) {
    fun isValidFor(
        sourceFingerprint: String,
        targetLanguage: String,
        provider: String,
        model: String,
    ): Boolean {
        if (this.sourceFingerprint.isBlank() || this.targetLanguage.isBlank()) return false
        if (this.provider.isBlank() || this.model.isBlank()) return false
        return this.sourceFingerprint == sourceFingerprint &&
            this.targetLanguage == targetLanguage &&
            this.provider == provider &&
            this.model == model
    }
}

const val MANGA_INFO_DEFAULT_SOURCE_LANG = "JA"

enum class MangaInfoProviderState {
    READY,
    MANGA_TRANSLATOR_UNSUPPORTED,
    NOT_CONFIGURED,
}

@Serializable
data class MangaInfoIdentity(
    val provider: String = "",
    val model: String = "",
)

fun resolveMangaInfoSourceLang(declaredLang: String?): String {
    val clean = declaredLang?.trim()?.take(20)?.takeIf { it.isNotBlank() } ?: return MANGA_INFO_DEFAULT_SOURCE_LANG
    return clean.uppercase()
}

fun buildMangaInfoFingerprint(
    sourceId: Long?,
    title: String,
    description: String?,
    sourceLang: String,
): String {
    fun norm(s: String): String = s.replace(Regex("\\s+"), " ").trim()
    val raw = "${sourceId ?: -1}|${norm(title)}|${norm(description ?: "")}|${norm(sourceLang).uppercase()}"
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
// KMK <--

/** No-op stub of [MangaInfoTranslationStore] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class MangaInfoTranslationStore(
    @Suppress("unused") private val context: Context,
) {
    fun get(mangaId: Long): MangaInfoTranslation? = null

    // KMK --> Signature parity with the engine store; always stale in no-MTL builds.
    fun getValidated(
        mangaId: Long,
        sourceFingerprint: String,
        targetLanguage: String,
        provider: String,
        model: String,
    ): MangaInfoTranslation? = null
    // KMK <--

    fun put(mangaId: Long, translation: MangaInfoTranslation) = Unit
    fun clear(mangaId: Long) = Unit
}
