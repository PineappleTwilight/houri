package exh.yakuyomi

import android.content.Context
import android.os.Environment
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
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@Serializable
internal data class HfFileEntry(
    val path: String = "",
    val size: Long = 0L,
)

/**
 * Downloads the selected on-device LLM (a single GGUF file) from HuggingFace into
 * `filesDir/local_llm_models/<modelId>/`. Custom user-supplied GGUFs are not downloaded.
 *
 * Hardened for large files: progress emissions are throttled (~5/s) so a multi-GB download
 * doesn't freeze the UI with a state update per 64 KiB chunk; the transfer uses a dedicated
 * client with a sane read timeout so a stalled connection fails loudly instead of hanging;
 * interrupted downloads resume from the partial file via HTTP Range; and the finished file
 * is size-verified against the remote before it is accepted.
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
        /** Recent transfer rate (bytes/s); 0 when idle or unknown. */
        val speedBytesPerSecond: Long = 0L,
        /** Seconds remaining at the current rate; -1 when unknown. */
        val etaSeconds: Long = -1L,
    ) {
        val progress: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    // Downloads use their own client: the shared one may have timeouts tuned for page
    // fetches, and a multi-GB transfer should never be killed by an aggressive setting.
    private val downloadClient = client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

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
        val dir = modelDir(model)
        val ggufOk = model.ggufFile?.let { f -> File(dir, f).length() > 1_000_000L } ?: false
        if (!ggufOk) return false
        // Vision models also need the multimodal projector next to the GGUF.
        val mmprojOk = if (model.mmprojFile.isNullOrBlank()) {
            true
        } else {
            File(dir, model.mmprojFile).length() > 1_000_000L
        }
        return mmprojOk
    }

    /** Free space on the app's data partition, in bytes. */
    private fun freeSpaceBytes(): Long = runCatching {
        Environment.getDataDirectory().let { android.os.StatFs(it.path).availableBytes }
    }.getOrDefault(Long.MAX_VALUE)

    /** Remote size of a file from the HF tree API (0 when unknown). */
    private fun remoteSize(repo: String, file: String): Long {
        if (repo.isBlank() || file.isBlank()) return 0L
        return runCatching {
            val url = "https://huggingface.co/api/models/$repo/tree/main?recursive=true&expand=false"
            val request = Request.Builder().url(url).get().build()
            downloadClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return 0L
                val body = resp.body.string()
                json.decodeFromString<List<HfFileEntry>>(body)
                    .firstOrNull { it.path == file }?.size ?: 0L
            }
        }.getOrDefault(0L)
    }

    fun startDownload(model: LocalLlmModel) {
        if (model.isCustom) return
        if (downloadJob?.isActive == true) downloadJob?.cancel()
        downloadJob = scope.launch {
            val ggufFile = model.ggufFile
            val ggufRepo = model.ggufRepo
            if (ggufFile.isNullOrBlank() || ggufRepo.isNullOrBlank()) {
                _status.value = Status(State.ERROR, error = "No GGUF configured for ${model.displayName}.")
                return@launch
            }
            val dir = modelDir(model)
            if (isDownloaded(model)) {
                val bytes = downloadedBytes(model)
                _status.value = Status(State.READY, downloadedBytes = bytes, totalBytes = bytes)
                return@launch
            }
            val mmproj = model.mmprojFile?.takeIf { !model.mmprojRepo.isNullOrBlank() }
            val ggufSize = remoteSize(ggufRepo, ggufFile).coerceAtLeast(model.sizeBytes)
            val mmprojSize = mmproj?.let { remoteSize(model.mmprojRepo!!, it) } ?: 0L
            val total = ggufSize + mmprojSize
            if (total > freeSpaceBytes()) {
                _status.value = Status(
                    State.ERROR,
                    totalBytes = total,
                    error = "Not enough free storage (needs ${total / (1024 * 1024)} MB).",
                )
                return@launch
            }
            _status.value = Status(State.DOWNLOADING, 0L, total, currentFile = ggufFile)
            try {
                var completed = downloadWithRetries(dir, ggufRepo, ggufFile, ggufSize, total, 0L)
                if (mmproj != null && completed < total) {
                    completed = downloadWithRetries(dir, model.mmprojRepo!!, mmproj, mmprojSize, total, completed)
                }
                if (!isDownloaded(model)) {
                    File(dir, ggufFile).delete()
                    mmproj?.let { File(dir, it).delete() }
                    throw IllegalStateException("Download incomplete for ${model.displayName}")
                }
                val bytes = downloadedBytes(model)
                _status.value = Status(State.READY, downloadedBytes = bytes, totalBytes = bytes)
            } catch (e: CancellationException) {
                // Keep partial files for resume; reflect how far the download got.
                _status.value = Status(State.NOT_INSTALLED, downloadedBytes = downloadedBytes(model), totalBytes = total)
                throw e
            } catch (e: Exception) {
                xLogW("GGUF download failed: ${e.message}")
                _status.value = Status(
                    State.ERROR,
                    downloadedBytes = downloadedBytes(model),
                    totalBytes = total,
                    error = e.message ?: "Download failed",
                )
            }
        }
    }

    /**
     * Downloads one file (GGUF or mmproj) into [dir] with resume, throttled progress and a
     * size check, returning the cumulative bytes completed across the whole model.
     */
    private suspend fun downloadOne(
        dir: File,
        repo: String,
        fileName: String,
        expectedSize: Long,
        totalBytes: Long,
        beforeBytes: Long,
    ): Long {
        val target = File(dir, fileName)
        if (target.length() > 1_000_000L) return beforeBytes + target.length()
        val tmp = File(dir, "$fileName.tmp")
        val startAt = tmp.length().takeIf { it > 0L } ?: 0L
        val url = "https://huggingface.co/$repo/resolve/main/${fileName.replace(" ", "%20")}"
        val request = Request.Builder().url(url).apply {
            if (startAt > 0L) header("Range", "bytes=$startAt-")
        }.get().build()

        var completed = beforeBytes + startAt
        var lastEmit = 0L
        var lastTick = System.nanoTime()
        downloadClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $fileName")
            // Server ignored the Range request (200 instead of 206) -> restart the file.
            if (startAt > 0L && resp.code != 206) {
                tmp.delete()
                completed = beforeBytes
            }
            resp.body.byteStream().use { input ->
                FileOutputStream(tmp, true).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        // Cancellation must propagate as an exception so the partial file
                        // is kept (and never renamed over the target) by the outer catch.
                        if (!coroutineContext.isActive) throw CancellationException("Download cancelled")
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        completed += read
                        val now = System.nanoTime()
                        val dtMs = (now - lastTick) / 1_000_000
                        // Throttle status emissions to ~5/s so a multi-GB download
                        // doesn't spam recompositions (the old per-64KiB emit froze the UI).
                        if (dtMs >= 200) {
                            val speed = (completed - lastEmit) * 1_000L / dtMs
                            val eta = if (speed > 0 && totalBytes > completed) {
                                (totalBytes - completed) / speed
                            } else {
                                -1L
                            }
                            _status.value = Status(
                                State.DOWNLOADING,
                                downloadedBytes = completed,
                                totalBytes = totalBytes,
                                currentFile = fileName,
                                speedBytesPerSecond = speed,
                                etaSeconds = eta,
                            )
                            lastEmit = completed
                            lastTick = now
                        }
                    }
                    output.flush()
                }
            }
            if (tmp.length() < 1_000_000L) {
                tmp.delete()
                throw IllegalStateException("Download too small for $fileName")
            }
            if (expectedSize > 0L && tmp.length() != expectedSize) {
                tmp.delete()
                throw IllegalStateException("Size mismatch for $fileName (got ${tmp.length()}, expected $expectedSize)")
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        return completed
    }

    private suspend fun downloadWithRetries(
        dir: File,
        repo: String,
        fileName: String,
        expectedSize: Long,
        totalBytes: Long,
        beforeBytes: Long,
    ): Long {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return downloadOne(dir, repo, fileName, expectedSize, totalBytes, beforeBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                val isTimeout = e.message?.contains("timeout", true) == true || e is java.net.SocketTimeoutException
                if (attempt < 2 && isTimeout) {
                    kotlinx.coroutines.delay(1000L shl attempt)
                } else if (attempt < 2) {
                    kotlinx.coroutines.delay(500L * (attempt + 1))
                }
            }
        }
        throw last ?: IllegalStateException("Download failed for $fileName")
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val partial = _status.value.downloadedBytes
        _status.value = Status(State.NOT_INSTALLED, downloadedBytes = partial)
    }

    fun clearModel(model: LocalLlmModel) {
        downloadJob?.cancel()
        downloadJob = null
        modelDir(model).listFiles()?.forEach { it.delete() }
        _status.value = Status(State.NOT_INSTALLED)
    }
}
