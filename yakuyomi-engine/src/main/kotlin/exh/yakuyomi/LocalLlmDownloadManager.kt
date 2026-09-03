package exh.yakuyomi

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.log.xLogW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable
internal data class HfFileEntry(
    val path: String = "",
    val size: Long = 0L,
)

/**
 * Downloads the selected on-device LLM (a single GGUF file) from HuggingFace into
 * `filesDir/local_llm_models/<modelId>/`. Custom user-supplied GGUFs are not downloaded.
 */
@SingleIn(AppScope::class)
@Inject
class LocalLlmDownloadManager(
    private val context: Context,
    private val client: OkHttpClient,
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

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _status = MutableStateFlow(Status(State.NOT_INSTALLED))
    val status: StateFlow<Status> = _status.asStateFlow()

    fun modelDir(model: LocalLlmModel): File =
        File(context.filesDir, "local_llm_models/${model.id}").apply { mkdirs() }

    fun downloadedBytes(model: LocalLlmModel): Long {
        val dir = modelDir(model)
        return dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    fun isDownloaded(model: LocalLlmModel): Boolean {
        if (model.isCustom) return true
        val file = model.ggufFile ?: return false
        val target = File(modelDir(model), file)
        return target.exists() && target.length() > 1_000_000L
    }

    /** Remote size of the GGUF from the HF tree API (0 when unknown). */
    private fun remoteSize(model: LocalLlmModel): Long {
        val repo = model.ggufRepo ?: return model.sizeBytes
        val file = model.ggufFile ?: return model.sizeBytes
        return runCatching {
            val url = "https://huggingface.co/api/models/$repo/tree/main?recursive=true&expand=false"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return model.sizeBytes
                val body = resp.body.string()
                json.decodeFromString<List<HfFileEntry>>(body)
                    .firstOrNull { it.path == file }?.size ?: model.sizeBytes
            }
        }.getOrDefault(model.sizeBytes)
    }

    fun startDownload(model: LocalLlmModel) {
        if (model.isCustom) return
        if (downloadJob?.isActive == true) downloadJob?.cancel()
        downloadJob = scope.launch {
            val file = model.ggufFile
            val repo = model.ggufRepo
            if (file.isNullOrBlank() || repo.isNullOrBlank()) {
                _status.value = Status(State.ERROR, error = "No GGUF configured for ${model.displayName}.")
                return@launch
            }
            val dir = modelDir(model)
            val target = File(dir, file)
            if (target.exists() && target.length() > 1_000_000L) {
                val bytes = target.length()
                _status.value = Status(State.READY, downloadedBytes = bytes, totalBytes = bytes)
                return@launch
            }
            val total = remoteSize(model).coerceAtLeast(model.sizeBytes)
            _status.value = Status(State.DOWNLOADING, 0L, total, currentFile = file)
            try {
                var completed = 0L
                val url = "https://huggingface.co/$repo/resolve/main/${file.replace(" ", "%20")}"
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $file")
                    val tmp = File(dir, "$file.tmp")
                    resp.body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (!isActive) return@use
                                val read = input.read(buf)
                                if (read == -1) break
                                output.write(buf, 0, read)
                                completed += read
                                _status.value = _status.value.copy(downloadedBytes = completed)
                            }
                            output.flush()
                        }
                    }
                    if (tmp.length() < 1_000_000L) {
                        tmp.delete()
                        throw IllegalStateException("Download too small for $file")
                    }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                }
                val bytes = target.length()
                _status.value = Status(State.READY, downloadedBytes = bytes, totalBytes = bytes)
            } catch (e: CancellationException) {
                _status.value = Status(State.NOT_INSTALLED, downloadedBytes = downloadedBytes(model))
            } catch (e: Exception) {
                xLogW("GGUF download failed: ${e.message}")
                _status.value = Status(State.ERROR, downloadedBytes = 0L, totalBytes = total, error = e.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _status.value = Status(State.NOT_INSTALLED)
    }

    fun clearModel(model: LocalLlmModel) {
        downloadJob?.cancel()
        downloadJob = null
        modelDir(model).listFiles()?.forEach { it.delete() }
        _status.value = Status(State.NOT_INSTALLED)
    }
}
