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
internal data class HfTreeEntry(
    val path: String = "",
    val size: Long = 0L,
)

/**
 * Downloads the selected on-device LLM (weights + compiled artifacts) from HuggingFace into a
 * per-model directory under `filesDir/local_llm_models/<modelId>/`. MLC weight repos are fetched
 * wholesale from the repo tree (weights + tokenizer + config); ExecuTorch entries download their
 * `.pte` + tokenizer files. The optional compiled MLC model lib (`<modelLib>-android-<abi>.so`)
 * is fetched from [LocalLlmModel.mlcLibRepo] when configured — the "bundling" step for the
 * native runtime the app needs.
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

    fun downloadedBytes(model: LocalLlmModel): Long =
        modelDir(model).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun isDownloaded(model: LocalLlmModel): Boolean {
        val files = modelDir(model).listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return false
        val required = requiredFiles(model)
        if (required.isEmpty()) return false
        val have = files.map { it.name }.toSet()
        return required.all { it.first in have }
    }

    private fun requiredFiles(model: LocalLlmModel): List<Pair<String, Long>> {
        return when {
            LocalLlmBackendType.MLC_GPU in model.backends -> cachedTree(model.mlcHfRepo ?: return emptyList())
            else -> {
                val files = buildList {
                    model.etPteFile?.let { add(it to 0L) }
                    model.etTokenizerFile?.let { add(it to 0L) }
                }
                files
            }
        }
    }

    private val treeCache = mutableMapOf<String, List<Pair<String, Long>>>()

    private fun cachedTree(repo: String): List<Pair<String, Long>> {
        treeCache[repo]?.let { return it }
        val entries = runCatching {
            val url = "https://huggingface.co/api/models/$repo/tree/main?recursive=true&expand=false"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body.string()
                json.decodeFromString<List<HfTreeEntry>>(body)
            }
        }.getOrDefault(emptyList())
        val result = entries
            .filter { it.size > 0L && !it.path.endsWith("/") && it.path != ".gitattributes" }
            .map { it.path to it.size }
        treeCache[repo] = result
        return result
    }

    fun startDownload(model: LocalLlmModel) {
        if (downloadJob?.isActive == true) downloadJob?.cancel()
        downloadJob = scope.launch {
            val files = requiredFiles(model)
            if (files.isEmpty()) {
                _status.value = Status(
                    State.ERROR,
                    error = if (model.requiresArtifacts) {
                        "${model.displayName} artifacts are not published yet — run the model-conversion build pipeline first (see module README)."
                    } else {
                        "Could not read model repository listing for ${model.displayName}."
                    },
                )
                return@launch
            }
            val dir = modelDir(model)
            val total = files.sumOf { it.second }
            var completed = 0L
            _status.value = Status(State.DOWNLOADING, completed, total)

            try {
                for ((name, size) in files) {
                    if (!isActive) return@launch
                    _status.value = _status.value.copy(currentFile = name)
                    downloadFile(model, name, size) { delta ->
                        completed += delta
                        _status.value = _status.value.copy(downloadedBytes = completed, totalBytes = total)
                    }
                }
                downloadModelLib(model, dir) { delta ->
                    completed += delta
                    _status.value = _status.value.copy(downloadedBytes = completed, totalBytes = total + delta)
                }
                _status.value = if (isDownloaded(model)) {
                    val bytes = downloadedBytes(model)
                    Status(State.READY, downloadedBytes = bytes, totalBytes = bytes)
                } else {
                    Status(State.NOT_INSTALLED, downloadedBytes = downloadedBytes(model))
                }
            } catch (e: CancellationException) {
                _status.value = Status(State.NOT_INSTALLED, downloadedBytes = downloadedBytes(model))
            } catch (e: Exception) {
                _status.value = Status(State.ERROR, completed, total, error = e.message ?: "Download failed")
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

    private fun downloadFile(model: LocalLlmModel, name: String, expectedSize: Long, onBytes: (Long) -> Unit) {
        val dir = modelDir(model)
        val target = File(dir, name)
        if (target.exists() && (expectedSize == 0L || target.length() == expectedSize)) return
        target.parentFile?.mkdirs()
        val repo = model.mlcHfRepo ?: model.etHfRepo ?: return
        val url = "https://huggingface.co/$repo/resolve/main/${name.replace(" ", "%20")}"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $name")
            val tmp = File(dir, "$name.tmp")
            resp.body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        onBytes(read.toLong())
                    }
                    output.flush()
                }
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                throw IllegalStateException("Empty download for $name")
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun downloadModelLib(model: LocalLlmModel, dir: File, onBytes: (Long) -> Unit) {
        val repo = model.mlcLibRepo ?: return
        val modelLib = model.mlcModelLib ?: return
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it.contains("64") } ?: "arm64-v8a"
        val name = "$modelLib-android-$abi.so"
        val target = File(dir, name)
        if (target.exists() && target.length() > 1_000_000L) return
        val url = "https://huggingface.co/$repo/resolve/main/$name"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                xLogW("MLC model lib not published yet ($url): ${resp.code} — falling back to bundled lib")
                return
            }
            val tmp = File(dir, "$name.tmp")
            resp.body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        onBytes(read.toLong())
                    }
                    output.flush()
                }
            }
            tmp.renameTo(target)
        }
    }
}
