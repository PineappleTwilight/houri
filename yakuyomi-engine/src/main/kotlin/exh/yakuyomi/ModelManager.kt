package exh.yakuyomi

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class YakuyomiModel(
    val id: String,
    val fileName: String,
    val url: String,
)

/**
 * Downloads and tracks the on-device Yakuyomi model packs (text detection, OCR, inpainting).
 *
 * The engine is currently a stub; there is no native runtime consuming these files yet, but
 * the download/install lifecycle and "models ready" gating are wired so translation can be
 * enabled once the runtime lands. See TODO.md "MTL".
 */
@SingleIn(AppScope::class)
@Inject
class ModelManager(
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

    companion object {
        // TODO: point at the real hosted model artifacts once Yakuyomi model packs are published.
        // Placeholder base URL; downloads won't resolve until maintained. See TODO.md "MTL".
        private const val MODELS_BASE_URL =
            "https://github.com/komikku-app/komikku/releases/download/yakuyomi-models"

        private val SPECS = listOf(
            YakuyomiModel("text-detector", "det.param", "$MODELS_BASE_URL/det.param"),
            YakuyomiModel("text-detector", "det.bin", "$MODELS_BASE_URL/det.bin"),
            YakuyomiModel("recognition", "rec.onnx", "$MODELS_BASE_URL/rec.onnx"),
            YakuyomiModel("inpainter", "inpaint.param", "$MODELS_BASE_URL/inpaint.param"),
            YakuyomiModel("inpainter", "inpaint.bin", "$MODELS_BASE_URL/inpaint.bin"),
        )
    }

    private val modelsDir: File = File(context.filesDir, "yakuyomi_models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _status = MutableStateFlow(Status(State.NOT_INSTALLED))
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        refresh()
    }

    private fun hasFile(spec: YakuyomiModel): Boolean =
        File(modelsDir, spec.fileName).takeIf { it.exists() }?.length()?.let { it > 0 } == true

    fun isReady(): Boolean = SPECS.all(::hasFile)

    fun installedBytes(): Long = SPECS.sumOf { spec ->
        File(modelsDir, spec.fileName).takeIf { it.exists() }?.length() ?: 0L
    }

    fun refresh() {
        _status.value = when {
            isReady() -> {
                val bytes = installedBytes()
                Status(State.READY, downloadedBytes = bytes, totalBytes = bytes)
            }
            else -> Status(State.NOT_INSTALLED, downloadedBytes = installedBytes())
        }
    }

    fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            val missing = SPECS.filterNot(::hasFile)
            if (missing.isEmpty()) {
                refresh()
                return@launch
            }

            var completed = installedBytes()
            var total = completed + resolveTotal(missing)

            _status.value = Status(State.DOWNLOADING, completed, total)

            try {
                for (spec in missing) {
                    if (!isActive) return@launch
                    _status.value = _status.value.copy(currentFile = spec.fileName)
                    download(spec) { delta ->
                        completed += delta
                        _status.value = _status.value.copy(downloadedBytes = completed, totalBytes = total)
                    }
                }
                refresh()
            } catch (e: CancellationException) {
                refresh()
            } catch (e: Exception) {
                _status.value = Status(State.ERROR, completed, total, error = e.message)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        refresh()
    }

    fun clearModels() {
        try {
            SPECS.forEach { spec ->
                File(modelsDir, spec.fileName).delete()
                File(modelsDir, "${spec.fileName}.tmp").delete()
            }
        } catch (_: Exception) {
        }
        refresh()
    }

    private fun resolveTotal(specs: List<YakuyomiModel>): Long {
        var total = 0L
        for (spec in specs) {
            try {
                val request = Request.Builder().url(spec.url).head().build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val len = resp.body?.contentLength()
                            ?: resp.header("Content-Length")?.toLongOrNull()
                            ?: 0L
                        total += len
                    }
                }
            } catch (_: Exception) {
            }
        }
        return total
    }

    private fun download(spec: YakuyomiModel, onBytes: (Long) -> Unit) {
        val request = Request.Builder().url(spec.url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} for ${spec.fileName}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty body for ${spec.fileName}")
            val tmp = File(modelsDir, "${spec.fileName}.tmp")
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        onBytes(read.toLong())
                    }
                    output.flush()
                }
            }
            val target = File(modelsDir, spec.fileName)
            if (tmp.length() > 0) {
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }
}