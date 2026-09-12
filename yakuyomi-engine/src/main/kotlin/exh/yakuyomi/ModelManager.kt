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
import java.security.MessageDigest

@Serializable
data class RemoteModel(
    val role: String = "",
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class ModelManifest(
    val version: String = "",
    val models: List<RemoteModel> = emptyList(),
)

/**
 * Downloads and tracks the on-device Yakuyomi model packs (detector, OCR, inpaint).
 *
 * Mirrors the upstream engine's lifecycle: fetch the versioned `models.json` manifest,
 * download each missing/mismatched file, and verify its sha256 before committing it into
 * the models directory. See docs at https://github.com/joyeli/yakuyomi-engine (MODELS.md).
 */
@SingleIn(AppScope::class)
@Inject
class ModelManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val prefs: TranslationPreferences? = null,
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
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/joyeli/yakuyomi-engine/main/models.json"

        private fun sanitizeCustomUrl(raw: String): String {
            val t = raw.trim()
            if (t.isBlank()) return ""
            if (t.length > 2048) return ""
            if (t.contains(" ") || t.contains("..") || t.contains("\n") || t.contains("\r")) return ""
            val lower = t.lowercase()
            if (!lower.startsWith("https://")) return ""
            return try {
                val uri = java.net.URI(t)
                if (uri.scheme != "https") return ""
                if (uri.host.isNullOrBlank()) return ""
                if (uri.host.contains("..")) return ""
                t
            } catch (_: Exception) {
                ""
            }
        }

        // Fallback when the manifest can't be fetched — pinned to models-v3.
        private val FALLBACK_MODELS = listOf(
            RemoteModel(
                role = "detector",
                name = "dbnet_detect.ncnn.param",
                url = "https://github.com/joyeli/yakuyomi-engine/releases/download/models-v3/dbnet_detect.ncnn.param",
                size = 13392,
                sha256 = "9e6db2f8c6b0662ab00eb2100b3373d3c984a235eaac0e61c0b2a484ee1ff7b5",
            ),
            RemoteModel(
                role = "detector",
                name = "dbnet_detect.ncnn.bin",
                url = "https://github.com/joyeli/yakuyomi-engine/releases/download/models-v3/dbnet_detect.ncnn.bin",
                size = 153010556,
                sha256 = "f57bdbede7764a534c56e88be0269602259a7fcd47e54e8b7d954fd0fcc55c3d",
            ),
            RemoteModel(
                role = "ocr",
                name = "ocr_int8.onnx",
                url = "https://github.com/joyeli/yakuyomi-engine/releases/download/models-v2/ocr_int8.onnx",
                size = 43625294,
                sha256 = "353e68a5506a6b8967905cd9b3c59e67708df1bc6812e105aa54d4e829fa4c5c",
            ),
            RemoteModel(
                role = "inpainter",
                name = "mit_aot_fixed512.ncnn.param",
                url = "https://github.com/joyeli/yakuyomi-engine/releases/download/models-v2/mit_aot_fixed512.ncnn.param",
                size = 33810,
                sha256 = "f21ef860d21a6cdf60dfb1742c08d1c0d98837bceeb2ee3fe9c2dbbeee7d32b5",
            ),
            RemoteModel(
                role = "inpainter",
                name = "mit_aot_fixed512.ncnn.bin",
                url = "https://github.com/joyeli/yakuyomi-engine/releases/download/models-v2/mit_aot_fixed512.ncnn.bin",
                size = 11366088,
                sha256 = "a52db45eafc1dd2aa4ce9a339c711917fa98fefb31ce4506d4c95e8b5e3560b6",
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val modelsDir: File = File(context.filesDir, "yakuyomi_models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    @Volatile
    private var models: List<RemoteModel> = FALLBACK_MODELS

    private val _status = MutableStateFlow(Status(State.NOT_INSTALLED))
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        refresh()
        verifyInBackground()
    }

    private fun file(model: RemoteModel): File = File(modelsDir, model.name)

    private fun hasValidSize(model: RemoteModel): Boolean =
        file(model).takeIf { it.exists() }?.length()?.let { it == model.size } == true

    fun isReady(): Boolean = models.isNotEmpty() && models.all(::hasValidSize)

    fun installedBytes(): Long = models.sumOf { model ->
        file(model).takeIf { it.exists() }?.length() ?: 0L
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

    /**
     * Verify each model's sha256 against the manifest. Returns name -> ok.
     */
    fun verify(): Map<String, Boolean> = models.associate { model ->
        model.name to verifyFile(model)
    }

    /**
     * Full sha256 pass over every model file, off the main thread. The size-only [refresh]
     * is fine for instant UI, but the engine's native loaders reject corrupt-but-right-size
     * files — flip to NOT_INSTALLED so the UI never claims READY for files the engine can't
     * load. Runs at startup and after each download (files skipped by [startDownload] are
     * only size-checked there).
     */
    private fun verifyInBackground() {
        scope.launch {
            val corrupt = verify().filterValues { !it }.keys
            if (corrupt.isNotEmpty()) {
                xLogW("Yakuyomi model files missing/corrupt: $corrupt")
                _status.value = Status(State.NOT_INSTALLED, downloadedBytes = installedBytes())
            } else {
                refresh()
            }
        }
    }

    private fun verifyFile(model: RemoteModel): Boolean {
        val f = file(model)
        if (!f.exists() || f.length() != model.size) return false
        return try {
            sha256(f).equals(model.sha256, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Downloads any missing or unverifiable model files.
     *
     * @param force when true, wipes the existing model files first so a full re-download
     *   happens even when every file currently verifies (the "Redownload" action).
     */
    fun startDownload(force: Boolean = false) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            if (force) clearModelFiles()
            refreshManifest()
            val toFetch = models.filterNot(::verifyFile)
            if (toFetch.isEmpty()) {
                refresh()
                verifyInBackground()
                return@launch
            }

            val total = toFetch.sumOf { it.size }
            // Count only bytes fetched this run; installedBytes() double-counts verified files.
            var completed = 0L
            _status.value = Status(State.DOWNLOADING, completed, total)

            try {
                for (model in toFetch) {
                    if (!isActive) return@launch
                    _status.value = _status.value.copy(currentFile = model.name)
                    downloadAndVerify(model) { delta ->
                        completed += delta
                        _status.value = _status.value.copy(downloadedBytes = completed, totalBytes = total)
                    }
                }
                refresh()
                verifyInBackground()
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

    fun clearModels() {
        downloadJob?.cancel()
        downloadJob = null
        clearModelFiles()
        refresh()
    }

    private fun clearModelFiles() {
        try {
            models.forEach { model ->
                file(model).delete()
                File(modelsDir, "${model.name}.tmp").delete()
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun refreshManifest() {
        val customManifest = prefs?.let { sanitizeCustomUrl(it.modelManifestUrl().get()) } ?: ""
        val manifestUrl = customManifest.ifBlank { MANIFEST_URL }
        val reqClient = client.newBuilder()
            .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        repeat(2) { attempt ->
            try {
                val request = Request.Builder().url(manifestUrl).get().build()
                reqClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return
                    val body = resp.body.string().take(200_000)
                    if (body.isBlank()) return
                    val manifest = json.decodeFromString<ModelManifest>(body)
                    if (manifest.models.isNotEmpty() && manifest.models.size < 20) {
                        val valid = manifest.models.filter { it.name.isNotBlank() && it.url.startsWith("https://") && it.size in 1024..500_000_000L && it.sha256.length == 64 }
                        if (valid.size == manifest.models.size) {
                            models = applyCustomModelUrls(valid)
                        }
                    }
                }
                return
            } catch (_: Exception) {
                if (attempt == 0) kotlinx.coroutines.delay(500)
            }
        }
        if (models === FALLBACK_MODELS || models == FALLBACK_MODELS) {
            models = applyCustomModelUrls(FALLBACK_MODELS)
        }
    }

    private fun applyCustomModelUrls(base: List<RemoteModel>): List<RemoteModel> {
        val p = prefs ?: return base
        val ocrUrl = sanitizeCustomUrl(p.customOcrModelUrl().get())
        val inpainterUrl = sanitizeCustomUrl(p.customInpainterModelUrl().get())
        val detectorUrl = sanitizeCustomUrl(p.customDetectorModelUrl().get())
        if (ocrUrl.isBlank() && inpainterUrl.isBlank() && detectorUrl.isBlank()) return base
        return base.map { model ->
            when (model.role) {
                "ocr" -> if (ocrUrl.isNotBlank() && model.name == "ocr_int8.onnx") model.copy(url = ocrUrl) else model
                "inpainter" -> if (inpainterUrl.isNotBlank()) {
                    when {
                        model.name == "mit_aot_fixed512.ncnn.param" && inpainterUrl.endsWith(".param") -> model.copy(url = inpainterUrl)
                        model.name == "mit_aot_fixed512.ncnn.bin" && inpainterUrl.endsWith(".bin") -> model.copy(url = inpainterUrl)
                        model.name == "mit_aot_fixed512.ncnn.param" && inpainterUrl.endsWith(".bin") -> model
                        model.name == "mit_aot_fixed512.ncnn.bin" && inpainterUrl.endsWith(".param") -> model
                        inpainterUrl.endsWith(".param") || inpainterUrl.endsWith(".bin") -> model
                        else -> model
                    }
                } else {
                    model
                }
                "detector" -> if (detectorUrl.isNotBlank()) {
                    when {
                        model.name == "dbnet_detect.ncnn.param" && detectorUrl.endsWith(".param") -> model.copy(url = detectorUrl)
                        model.name == "dbnet_detect.ncnn.bin" && detectorUrl.endsWith(".bin") -> model.copy(url = detectorUrl)
                        model.name == "dbnet_detect.ncnn.param" && detectorUrl.endsWith(".bin") -> model
                        model.name == "dbnet_detect.ncnn.bin" && detectorUrl.endsWith(".param") -> model
                        detectorUrl.endsWith(".param") || detectorUrl.endsWith(".bin") -> model
                        else -> model
                    }
                } else {
                    model
                }
                else -> model
            }
        }
    }

    fun customUrlsActive(): Boolean {
        val p = prefs ?: return false
        return sanitizeCustomUrl(p.modelManifestUrl().get()).isNotBlank() ||
            sanitizeCustomUrl(p.customOcrModelUrl().get()).isNotBlank() ||
            sanitizeCustomUrl(p.customInpainterModelUrl().get()).isNotBlank() ||
            sanitizeCustomUrl(p.customDetectorModelUrl().get()).isNotBlank()
    }

    private fun downloadAndVerify(model: RemoteModel, onBytes: (Long) -> Unit) {
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
            val contentLen = resp.header("Content-Length")?.toLongOrNull()
            if (contentLen != null && contentLen != model.size) throw IllegalStateException("Content-Length mismatch for ${model.name}")
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
            if (!sha256(tmp).equals(model.sha256, ignoreCase = true)) {
                tmp.delete()
                throw IllegalStateException("sha256 mismatch for ${model.name}")
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
