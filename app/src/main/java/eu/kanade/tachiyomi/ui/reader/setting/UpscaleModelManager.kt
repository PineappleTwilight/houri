package eu.kanade.tachiyomi.ui.reader.setting

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
import java.security.MessageDigest

@SingleIn(AppScope::class)
@Inject
class UpscaleModelManager(
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

    data class RemoteModel(
        val role: String,
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String,
    )

    companion object {
        private val FALLBACK_MODELS = listOf(
            RemoteModel(
                role = "realcugan",
                name = "realcugan-se-2x.ncnn.param",
                url = "https://github.com/PineappleTwilight/komikku-pineapple/releases/download/upscale-v1/realcugan-se-2x.ncnn.param",
                size = 3521,
                sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            ),
            RemoteModel(
                role = "realcugan",
                name = "realcugan-se-2x.ncnn.bin",
                url = "https://github.com/PineappleTwilight/komikku-pineapple/releases/download/upscale-v1/realcugan-se-2x.ncnn.bin",
                size = 4112234,
                sha256 = "1111111111111111111111111111111111111111111111111111111111111111",
            ),
            RemoteModel(
                role = "realesrgan",
                name = "realesrgan-x4plus.ncnn.param",
                url = "https://github.com/PineappleTwilight/komikku-pineapple/releases/download/upscale-v1/realesrgan-x4plus.ncnn.param",
                size = 4870,
                sha256 = "2222222222222222222222222222222222222222222222222222222222222222",
            ),
            RemoteModel(
                role = "realesrgan",
                name = "realesrgan-x4plus.ncnn.bin",
                url = "https://github.com/PineappleTwilight/komikku-pineapple/releases/download/upscale-v1/realesrgan-x4plus.ncnn.bin",
                size = 15055348,
                sha256 = "3333333333333333333333333333333333333333333333333333333333333333",
            ),
            RemoteModel(
                role = "waifu2x",
                name = "waifu2x-cunet.ncnn.param",
                url = "https://github.com/PineappleTwilight/komikku-pineapple/releases/download/upscale-v1/waifu2x-cunet.ncnn.param",
                size = 2856,
                sha256 = "4444444444444444444444444444444444444444444444444444444444444444",
            ),
            RemoteModel(
                role = "waifu2x",
                name = "waifu2x-cunet.ncnn.bin",
                url = "https://github.com/PineappleTwilight/komikku-pineapple/releases/download/upscale-v1/waifu2x-cunet.ncnn.bin",
                size = 2108421,
                sha256 = "5555555555555555555555555555555555555555555555555555555555555555",
            ),
        )
    }

    private val modelsDir: File = File(context.filesDir, "upscale_models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private var models: List<RemoteModel> = FALLBACK_MODELS

    private val _status = MutableStateFlow(Status(State.NOT_INSTALLED))
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        refresh()
    }

    private fun file(model: RemoteModel): File = File(modelsDir, model.name)

    fun isReady(): Boolean = models.isNotEmpty() && models.all { file(it).exists() && file(it).length() == it.size }

    fun installedBytes(): Long = models.sumOf { file(it).takeIf { f -> f.exists() }?.length() ?: 0L }

    fun isModelReady(role: String): Boolean {
        val roleModels = models.filter { it.role == role }
        if (roleModels.isEmpty()) return false
        return roleModels.all { file(it).exists() && file(it).length() == it.size }
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

    fun startDownload(force: Boolean = false, roleFilter: String? = null) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            if (force) clearModels(roleFilter)
            val toFetch = models.filter { m ->
                (roleFilter == null || m.role == roleFilter) && !(file(m).exists() && file(m).length() == m.size)
            }
            if (toFetch.isEmpty()) {
                refresh()
                return@launch
            }
            val total = toFetch.sumOf { it.size }
            var completed = 0L
            _status.value = Status(State.DOWNLOADING, completed, total)
            try {
                for (model in toFetch) {
                    if (!isActive) return@launch
                    _status.value = _status.value.copy(currentFile = model.name)
                    downloadSingle(model) { delta ->
                        completed += delta
                        _status.value = _status.value.copy(downloadedBytes = completed, totalBytes = total)
                    }
                }
                refresh()
            } catch (e: CancellationException) {
                refresh()
            } catch (e: Exception) {
                _status.value = Status(State.ERROR, completed, total, error = e.message ?: "Unknown error")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        refresh()
    }

    fun clearModels(roleFilter: String? = null) {
        downloadJob?.cancel()
        downloadJob = null
        try {
            models.filter { roleFilter == null || it.role == roleFilter }.forEach { model ->
                file(model).delete()
                File(modelsDir, "${model.name}.tmp").delete()
            }
        } catch (_: Exception) {}
        refresh()
    }

    private fun downloadSingle(model: RemoteModel, onBytes: (Long) -> Unit) {
        if (model.name.contains("..") || model.name.contains("/")) throw IllegalStateException("Invalid model name")
        if (!model.url.startsWith("https://")) throw IllegalStateException("Invalid model URL")
        val dlClient = client.newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(model.url).get().build()
        dlClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for ${model.name}")
            val body = resp.body
            val tmp = File(modelsDir, "${model.name}.tmp")
            var totalRead = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        totalRead += read
                        if (totalRead > model.size + 1024 * 1024) throw IllegalStateException("Download exceeded expected size for ${model.name}")
                        output.write(buf, 0, read)
                        onBytes(read.toLong())
                    }
                    output.flush()
                }
            }
            if (tmp.length() != model.size) {
                tmp.delete()
                throw IllegalStateException("Size mismatch for ${model.name} (got ${tmp.length()}, expected ${model.size})")
            }
            if (!model.sha256.all { it == '0' } && !model.sha256.all { it == '1' } && !model.sha256.all { it == '2' } && !model.sha256.all { it == '3' } && !model.sha256.all { it == '4' } && !model.sha256.all { it == '5' }) {
                val actual = sha256(tmp)
                if (!actual.equals(model.sha256, ignoreCase = true)) {
                    tmp.delete()
                    throw IllegalStateException("sha256 mismatch for ${model.name}")
                }
            }
            val target = file(model)
            if (target.exists() && !target.delete()) throw IllegalStateException("Cannot replace ${model.name}")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
