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

/** No-op stub of [LocalLlmManager] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class LocalLlmManager {

    fun isLocalProvider(): Boolean = false

    fun resolveModel(): LocalLlmModel? = null

    fun isModelReady(): Boolean = false

    fun status(): LocalLlmDownloadManager.Status = LocalLlmDownloadManager.Status(LocalLlmDownloadManager.State.NOT_INSTALLED)

    fun startDownload() = Unit

    fun cancelDownload() = Unit

    fun clearModel() = Unit

    fun activeBackendType(): LocalLlmBackendType? = null

    fun isRuntimeAvailable(): Boolean = false

    suspend fun generate(prompt: String, imageBytes: ByteArray? = null): String? = null

    fun closeAll() = Unit
}

/** Stub of [MangaInfoTranslation] — same shape as the real one. */
@Serializable
data class MangaInfoTranslation(
    val title: String,
    val description: String? = null,
)

/** No-op stub of [MangaInfoTranslationStore] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class MangaInfoTranslationStore(
    @Suppress("unused") private val context: Context,
) {
    fun get(mangaId: Long): MangaInfoTranslation? = null
    fun put(mangaId: Long, translation: MangaInfoTranslation) = Unit
    fun clear(mangaId: Long) = Unit
}
