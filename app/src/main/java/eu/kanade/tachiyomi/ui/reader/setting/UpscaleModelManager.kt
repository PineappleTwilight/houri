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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs

/** A single backend-specific model file in the upscaler manifest. */
@Serializable
data class UpscaleArtifact(
    val format: String,
    val role: String,
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val inputOrder: Int? = null,
    val outputOrder: Int? = null,
)

/** A logical upscaler model and the metadata needed to run it safely. */
@Serializable
data class UpscaleModelSpec(
    val id: String,
    val family: String,
    val label: String,
    val nativeScale: Int,
    val tileSize: Int = 128,
    val padding: Int = 16,
    val inputOrder: Int = 0,
    val outputOrder: Int = 1,
    val artifacts: List<UpscaleArtifact> = emptyList(),
)

@Serializable
private data class UpscaleManifest(
    val version: Int = 0,
    val models: List<UpscaleModelSpec> = emptyList(),
)

enum class UpscaleModelFormat {
    NCNN,
    ONNX,
}

data class ResolvedUpscaleModel(
    val spec: UpscaleModelSpec,
    val format: UpscaleModelFormat,
    val artifacts: List<UpscaleArtifact>,
) {
    val id: String get() = spec.id
    val family: String get() = spec.family
    val nativeScale: Int get() = spec.nativeScale
    val tileSize: Int get() = spec.tileSize
    val padding: Int get() = spec.padding

    val inputOrder: Int
        get() = artifacts.firstOrNull { it.inputOrder != null }?.inputOrder ?: spec.inputOrder

    val outputOrder: Int
        get() = artifacts.firstOrNull { it.outputOrder != null }?.outputOrder ?: spec.outputOrder

    fun artifact(role: String): UpscaleArtifact? = artifacts.firstOrNull { it.role == role }
}

/** Downloads and verifies the model artifacts consumed by the native/ORT upscalers. */
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
        val readyModelIds: Set<String> = emptySet(),
    ) {
        val progress: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    private companion object {
        const val MANIFEST_ASSET = "upscale_models.json"
        const val MAX_MANIFEST_MODELS = 32
        const val MAX_ARTIFACT_BYTES = 512L * 1024L * 1024L
        const val MIN_ARTIFACT_BYTES = 256L
        val json = Json { ignoreUnknownKeys = true }
    }

    private val modelsDir: File = File(context.filesDir, "upscale_models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var models: List<UpscaleModelSpec> = emptyList()
    private var manifestVersionValue: Int = 0
    private var manifestError: String? = null

    private val _status = MutableStateFlow(Status(State.NOT_INSTALLED))
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        models = loadManifest()
        refresh()
        verifyInstalled()
    }

    fun catalog(): List<UpscaleModelSpec> = models

    fun manifestVersion(): Int = manifestVersionValue

    fun totalBytes(): Long = models.asSequence().flatMap { it.artifacts.asSequence() }.sumOf { it.size }

    fun isReady(): Boolean = readyModels().isNotEmpty()

    fun isModelReady(modelId: String, format: UpscaleModelFormat? = null): Boolean {
        val spec = models.firstOrNull { it.id == modelId } ?: return false
        val artifacts = artifactsFor(spec, format)
        return artifacts.isNotEmpty() && artifacts.all { hasValidSize(it) }
    }

    fun isModelReady(role: String): Boolean {
        val normalized = role.trim().uppercase()
        return models.asSequence()
            .filter { it.family.uppercase() == normalized }
            .any { spec -> formatsFor(spec).any { format -> isModelReady(spec.id, format) } }
    }

    fun installedBytes(): Long = models.asSequence()
        .flatMap { it.artifacts.asSequence() }
        .filter { hasValidSize(it) }
        .sumOf { file(it).length() }

    fun resolvedModel(
        family: String,
        targetScale: Float,
        format: UpscaleModelFormat,
    ): ResolvedUpscaleModel? {
        val normalizedFamily = family.trim().uppercase()
        val wantedScale = targetScale.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 2f
        return models.asSequence()
            .filter { it.family.uppercase() == normalizedFamily }
            .filter { formatsFor(it).contains(format) }
            .sortedWith(
                compareBy<UpscaleModelSpec> { abs(it.nativeScale - wantedScale) }
                    .thenBy { it.nativeScale },
            )
            .mapNotNull { spec -> resolve(spec, format)?.takeIf { isResolvedReady(it) } }
            .firstOrNull()
    }

    fun resolvedModels(family: String, format: UpscaleModelFormat): List<ResolvedUpscaleModel> {
        val normalizedFamily = family.trim().uppercase()
        return models.asSequence()
            .filter { it.family.uppercase() == normalizedFamily }
            .mapNotNull { resolve(it, format)?.takeIf { isResolvedReady(it) } }
            .toList()
    }

    fun fileFor(artifact: UpscaleArtifact): File = file(artifact)

    fun refresh() {
        val ready = readyModels()
        val installed = installedBytes()
        _status.value = when {
            manifestError != null -> Status(State.ERROR, error = manifestError)
            ready.isNotEmpty() -> Status(
                state = State.READY,
                downloadedBytes = installed,
                totalBytes = installed,
                readyModelIds = ready.mapTo(linkedSetOf()) { it.id },
            )
            else -> Status(State.NOT_INSTALLED, downloadedBytes = installed)
        }
    }

    fun startDownload(
        force: Boolean = false,
        modelId: String? = null,
        format: UpscaleModelFormat? = null,
    ) {
        if (downloadJob?.isActive == true) return
        val selected = selectSpecs(modelId, format)
        if (selected.isEmpty()) {
            _status.value = Status(State.ERROR, error = "No upscaler model matches the selected backend")
            return
        }
        downloadJob = scope.launch {
            if (force) clearModelFiles(selected)
            val toFetch = selected.flatMap { spec -> artifactsFor(spec, format) }
                .filterNot { hasValidSize(it) }
            if (toFetch.isEmpty()) {
                verifyInstalled()
                return@launch
            }

            val total = toFetch.sumOf { it.size }
            var completed = 0L
            _status.value = Status(State.DOWNLOADING, downloadedBytes = 0L, totalBytes = total)
            try {
                for (artifact in toFetch) {
                    if (!isActive) return@launch
                    _status.value = _status.value.copy(currentFile = artifact.name)
                    downloadSingle(artifact) { delta ->
                        completed += delta
                        _status.value = _status.value.copy(
                            downloadedBytes = completed,
                            totalBytes = total,
                        )
                    }
                }
                verifyInstalled()
            } catch (e: CancellationException) {
                refresh()
            } catch (e: Exception) {
                _status.value = Status(
                    State.ERROR,
                    downloadedBytes = completed,
                    totalBytes = total,
                    error = e.message ?: "Unknown error",
                    readyModelIds = readyModels().mapTo(linkedSetOf()) { it.id },
                )
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        runCatching { activeCall?.cancel() }
        downloadJob = null
        activeCall = null
        refresh()
    }

    fun clearModels(modelId: String? = null, format: UpscaleModelFormat? = null) {
        downloadJob?.cancel()
        runCatching { activeCall?.cancel() }
        downloadJob = null
        activeCall = null
        clearModelFiles(selectSpecs(modelId, format))
        refresh()
    }

    private fun loadManifest(): List<UpscaleModelSpec> {
        val raw = runCatching {
            context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse {
            manifestError = "Unable to read $MANIFEST_ASSET"
            return emptyList()
        }
        return runCatching {
            val manifest = json.decodeFromString<UpscaleManifest>(raw)
            manifestVersionValue = manifest.version
            require(manifest.models.size in 1..MAX_MANIFEST_MODELS)
            val valid = manifest.models.filter(::validSpec)
            require(valid.size == manifest.models.size)
            manifestError = null
            valid
        }.getOrElse {
            manifestError = "Invalid upscaler model manifest: ${it.message ?: "unknown error"}"
            emptyList()
        }
    }

    private fun validSpec(spec: UpscaleModelSpec): Boolean {
        if (spec.id.isBlank() || spec.id.length > 96 || spec.family.isBlank() || spec.label.isBlank()) return false
        if (spec.nativeScale !in 1..4 || spec.tileSize !in 32..512 || spec.padding !in 0..64) return false
        if (spec.artifacts.isEmpty() || spec.artifacts.size > 8) return false
        if (spec.artifacts.any { !validArtifact(it) }) return false
        return formatsFor(spec).isNotEmpty()
    }

    private fun validArtifact(artifact: UpscaleArtifact): Boolean {
        if (artifact.format !in setOf("NCNN", "ONNX")) return false
        if (artifact.role.isBlank() || artifact.name.isBlank() || artifact.name.length > 160) return false
        if (artifact.name.contains("..") || artifact.name.contains('/') || artifact.name.contains('\\')) return false
        if (artifact.size !in MIN_ARTIFACT_BYTES..MAX_ARTIFACT_BYTES) return false
        if (!artifact.sha256.matches(Regex("^[0-9a-fA-F]{64}$"))) return false
        return isHttpsUrl(artifact.url)
    }

    private fun isHttpsUrl(raw: String): Boolean = runCatching {
        val uri = java.net.URI(raw)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && raw.length <= 2048
    }.getOrDefault(false)

    private fun formatsFor(spec: UpscaleModelSpec): List<UpscaleModelFormat> = buildList {
        if (spec.artifacts.any { it.format == "NCNN" }) add(UpscaleModelFormat.NCNN)
        if (spec.artifacts.any { it.format == "ONNX" }) add(UpscaleModelFormat.ONNX)
    }

    private fun artifactsFor(
        spec: UpscaleModelSpec,
        format: UpscaleModelFormat?,
    ): List<UpscaleArtifact> = spec.artifacts.filter { artifact ->
        format == null || artifact.format == format.name
    }

    private fun resolve(spec: UpscaleModelSpec, format: UpscaleModelFormat): ResolvedUpscaleModel? {
        if (format !in formatsFor(spec)) return null
        return ResolvedUpscaleModel(spec, format, artifactsFor(spec, format))
    }

    private fun selectSpecs(modelId: String?, format: UpscaleModelFormat?): List<UpscaleModelSpec> =
        models.filter { spec ->
            (modelId == null || spec.id == modelId) &&
                (format == null || format in formatsFor(spec))
        }

    private fun readyModels(): List<ResolvedUpscaleModel> = models.flatMap { spec ->
        formatsFor(spec).mapNotNull { format -> resolve(spec, format)?.takeIf { isResolvedReady(it) } }
    }

    private fun isResolvedReady(model: ResolvedUpscaleModel): Boolean =
        model.artifacts.isNotEmpty() && model.artifacts.all { hasValidSize(it) }

    private fun file(artifact: UpscaleArtifact): File = File(modelsDir, artifact.name)

    private fun hasValidSize(artifact: UpscaleArtifact): Boolean = file(artifact).let {
        it.isFile && it.length() == artifact.size
    }

    private fun verifyInstalled() {
        scope.launch {
            val corrupt = mutableListOf<String>()
            for (artifact in models.flatMap { it.artifacts }) {
                if (!hasValidSize(artifact)) continue
                if (!verifyHash(artifact)) {
                    corrupt += artifact.name
                    runCatching { file(artifact).delete() }
                }
            }
            if (corrupt.isNotEmpty()) {
                logcat(LogPriority.WARN) { "Upscaler model files missing/corrupt: $corrupt" }
            }
            refresh()
        }
    }

    private fun verifyHash(artifact: UpscaleArtifact): Boolean = runCatching {
        sha256(file(artifact)).equals(artifact.sha256, ignoreCase = true)
    }.getOrDefault(false)

    private fun clearModelFiles(specs: List<UpscaleModelSpec>) {
        specs.flatMap { it.artifacts }.forEach { artifact ->
            runCatching { file(artifact).delete() }
            runCatching { File(modelsDir, "${artifact.name}.tmp").delete() }
        }
    }

    private fun downloadSingle(artifact: UpscaleArtifact, onBytes: (Long) -> Unit) {
        if (!isHttpsUrl(artifact.url)) throw IllegalStateException("Invalid model URL")
        if (artifact.name.contains("..") || artifact.name.contains('/')) throw IllegalStateException("Invalid model name")
        val downloadClient = client.newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val call = downloadClient.newCall(Request.Builder().url(artifact.url).get().build())
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} for ${artifact.name}")
                val body = response.body
                val contentLength = response.header("Content-Length")?.toLongOrNull()
                if (contentLength != null && contentLength != artifact.size) {
                    throw IllegalStateException("Content-Length mismatch for ${artifact.name}")
                }
                val temp = File(modelsDir, "${artifact.name}.tmp")
                var totalRead = 0L
                try {
                    body.byteStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                totalRead += read
                                if (totalRead > artifact.size) {
                                    throw IllegalStateException("Download exceeded expected size for ${artifact.name}")
                                }
                                output.write(buffer, 0, read)
                                onBytes(read.toLong())
                            }
                            output.fd.sync()
                        }
                    }
                    if (temp.length() != artifact.size) {
                        throw IllegalStateException("Size mismatch for ${artifact.name}")
                    }
                    if (!sha256(temp).equals(artifact.sha256, ignoreCase = true)) {
                        throw IllegalStateException("sha256 mismatch for ${artifact.name}")
                    }
                    val target = file(artifact)
                    if (target.exists() && !target.delete()) throw IllegalStateException("Cannot replace ${artifact.name}")
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        if (target.length() != artifact.size) throw IllegalStateException("Copy failed for ${artifact.name}")
                    }
                } finally {
                    if (temp.exists()) runCatching { temp.delete() }
                }
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
