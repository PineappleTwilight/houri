package eu.kanade.tachiyomi.ui.reader.setting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.yakuyomi.NativeUpscaleSession
import exh.yakuyomi.NativeUpscaler
import exh.yakuyomi.OrtUpscaleSession
import exh.yakuyomi.TranslationPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.achievement.service.AchievementManager
import tachiyomi.domain.achievement.service.AchievementPreferences
import tachiyomi.domain.achievement.service.RotatingAchievementPool

@SingleIn(AppScope::class)
@Inject
class UpscaleEngine(
    private val context: android.content.Context,
    private val prefs: UpscalePreferences,
    private val translationPreferences: TranslationPreferences,
    private val modelManager: UpscaleModelManager,
    private val achievementManager: AchievementManager,
    private val achievementPrefs: AchievementPreferences,
    private val rotatingPool: RotatingAchievementPool,
) {
    private data class InferenceCandidate(
        val backend: UpscalePreferences.Backend,
        val model: ResolvedUpscaleModel,
    )

    private val backendDetector by lazy { UpscaleBackendDetector(context) }
    private val cacheManager by lazy { UpscaleCacheManager(context.cacheDir) }
    private val semaphore = Semaphore(1)
    private val sessionLock = Any()
    private val ncnnSessions = mutableMapOf<String, NativeUpscaleSession>()
    private val ortSessions = mutableMapOf<String, OrtUpscaleSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private companion object {
        const val MAX_INPUT_BYTES = 30 * 1024 * 1024
        const val MAX_INPUT_PIXELS = 16L * 1024 * 1024
        const val SECRET_SAME_PAGE_THRESHOLD = 5
        const val MAX_PNG_BYTES = 12 * 1024 * 1024
    }

    init {
        modelManager.status
            .onEach { status ->
                if (status.state == UpscaleModelManager.State.READY ||
                    status.state == UpscaleModelManager.State.NOT_INSTALLED ||
                    status.state == UpscaleModelManager.State.ERROR
                ) {
                    backendDetector.invalidateCache()
                    clearSessions()
                }
            }
            .launchIn(scope)
    }

    fun isAvailable(): Boolean = if (prefs.isSimpleMode()) true else backendDetector.isAvailable()

    fun effectiveBackend(): UpscalePreferences.Backend = backendDetector.effectiveBackend(prefs.effectiveBackend())

    fun effectiveBackendFor(family: String, factor: Float): UpscalePreferences.Backend =
        resolveCandidates(family, factor, prefs.effectiveBackend()).firstOrNull()?.backend
            ?: effectiveBackend()

    fun selectedModel(): ResolvedUpscaleModel? =
        resolveCandidates(prefs.effectiveModel().name, prefs.upscaleFactor().get(), prefs.effectiveBackend())
            .firstOrNull()
            ?.model

    fun availableBackends(): List<UpscalePreferences.Backend> = backendDetector.availableBackends()

    fun isNativeAvailable(): Boolean = backendDetector.isAvailable()

    fun backendDiagnostics(): String = buildString {
        append("mode=${prefs.effectiveMode().name}")
        append(" avail=${isAvailable()}")
        append(" effBackend=${effectiveBackend().name}")
        append(" selected=${selectedModel()?.id ?: "none"}")
        append(" runtime=[${backendDetector.diagnostics()}]")
        append(" models=${modelManager.status.value.readyModelIds.joinToString(",")}")
    }

    private fun reportUpscaleServed(
        mangaId: Long,
        backend: UpscalePreferences.Backend?,
        cacheKey: String,
    ) {
        if (mangaId <= 0 || cacheKey.isBlank()) return
        try {
            achievementManager.tryUnlockDirect("upscale_first")
            val total = achievementPrefs.incrementUpscalesServed()
            achievementManager.onUpscaled(total)
            if (backend == UpscalePreferences.Backend.VULKAN) {
                achievementManager.tryUnlockDirect("upscale_vulkan")
            } else if (backend == UpscalePreferences.Backend.NPU) {
                achievementManager.tryUnlockDirect("upscale_npu")
            }
            rotatingPool.markProgress("rotating_weekly_upscale_20")
            val pageCount = achievementPrefs.incrementUpscalePageCount(cacheKey)
            if (pageCount >= SECRET_SAME_PAGE_THRESHOLD) {
                achievementManager.tryUnlockDirect("secret_upscale_4k")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun upscaleIfNeeded(mangaId: Long, bytes: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        if (bytes.isEmpty() || bytes.size > MAX_INPUT_BYTES) return@withContext null
        if (!prefs.isEnabledForManga(mangaId)) return@withContext null
        val factorRaw = prefs.upscaleFactor().get()
        val factor = factorRaw.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 2f
        if (factor < 1.02f) return@withContext null

        if (prefs.isSimpleMode()) {
            val algo = prefs.effectiveSimpleAlgo()
            val cacheModel = "simple_${algo.name}"
            val cacheKey = cacheManager.cacheKeyDetailed(bytes, factor, cacheModel, algo.name, prefs.effectiveMode().name)
            if (prefs.cacheEnabled().get()) {
                withContext(Dispatchers.IO) { cacheManager.getCached(cacheKey) }?.let {
                    reportUpscaleServed(mangaId, null, cacheKey)
                    return@withContext it
                }
            }
            val result = semaphore.withPermit {
                runCatching { runUpscaleSimple(bytes, factor, algo) }.getOrElse {
                    logcat(LogPriority.ERROR, it) { "Upscale simple failed factor=$factor algo=$algo" }
                    null
                }
            } ?: return@withContext null
            if (prefs.cacheEnabled().get()) {
                withContext(Dispatchers.IO) { cacheManager.putCached(cacheKey, result) }
            }
            reportUpscaleServed(mangaId, null, cacheKey)
            return@withContext result
        }

        if (!translationPreferences.enabled().get()) return@withContext null
        val preset = prefs.effectivePreset()
        val candidates = resolveCandidates(prefs.effectiveModel().name, factor, prefs.effectiveBackend())
        if (candidates.isEmpty()) return@withContext null

        for (candidate in candidates) {
            val model = candidate.model
            val cacheKey = cacheManager.cacheKeyDetailed(
                bytes = bytes,
                factor = factor,
                model = model.id,
                extra1 = preset.name,
                extra2 = buildString {
                    append(candidate.backend.name)
                    append(':')
                    append(model.format.name)
                    append(':')
                    append(model.artifacts.joinToString(",") { "${it.name}:${it.sha256.take(12)}" })
                },
            )
            if (prefs.cacheEnabled().get()) {
                withContext(Dispatchers.IO) { cacheManager.getCached(cacheKey) }?.let {
                    reportUpscaleServed(mangaId, candidate.backend, cacheKey)
                    return@withContext it
                }
            }

            val result = semaphore.withPermit {
                runCatching { runNative(bytes, factor, preset, candidate) }.getOrElse { error ->
                    logcat(LogPriority.WARN, error) {
                        "Upscale ${candidate.backend}/${model.id} failed; trying next backend"
                    }
                    null
                }
            }
            if (result != null) {
                if (prefs.cacheEnabled().get()) {
                    withContext(Dispatchers.IO) { cacheManager.putCached(cacheKey, result) }
                }
                reportUpscaleServed(mangaId, candidate.backend, cacheKey)
                return@withContext result
            }
        }
        null
    }

    private fun resolveCandidates(
        family: String,
        factor: Float,
        requested: UpscalePreferences.Backend,
    ): List<InferenceCandidate> {
        val formatPairs = when (requested) {
            UpscalePreferences.Backend.AUTO -> listOf(
                UpscalePreferences.Backend.VULKAN to UpscaleModelFormat.NCNN,
                UpscalePreferences.Backend.NPU to UpscaleModelFormat.ONNX,
                UpscalePreferences.Backend.CPU to UpscaleModelFormat.NCNN,
                UpscalePreferences.Backend.CPU to UpscaleModelFormat.ONNX,
            )
            UpscalePreferences.Backend.VULKAN -> listOf(
                UpscalePreferences.Backend.VULKAN to UpscaleModelFormat.NCNN,
                UpscalePreferences.Backend.CPU to UpscaleModelFormat.NCNN,
                UpscalePreferences.Backend.CPU to UpscaleModelFormat.ONNX,
            )
            UpscalePreferences.Backend.NPU -> listOf(
                UpscalePreferences.Backend.NPU to UpscaleModelFormat.ONNX,
                UpscalePreferences.Backend.CPU to UpscaleModelFormat.ONNX,
            )
            UpscalePreferences.Backend.CPU -> listOf(
                UpscalePreferences.Backend.CPU to UpscaleModelFormat.NCNN,
                UpscalePreferences.Backend.CPU to UpscaleModelFormat.ONNX,
            )
        }
        return formatPairs
            .filter { (backend, _) -> backendDetector.isBackendAvailable(backend) }
            .mapNotNull { (backend, format) ->
                modelManager.resolvedModel(family, factor, format)?.let { InferenceCandidate(backend, it) }
            }
            .distinctBy { "${it.backend.name}:${it.model.id}:${it.model.format.name}" }
    }

    private fun runNative(
        bytes: ByteArray,
        factor: Float,
        preset: UpscalePreferences.Preset,
        candidate: InferenceCandidate,
    ): ByteArray? {
        val bitmap = decodeBitmap(bytes) ?: return null
        var nativeBitmap: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val requestedScale = UpscaleScalingStrategy.computeScale(factor, preset)
            val (targetWidth, targetHeight) = UpscaleScalingStrategy.scaledDimensions(
                bitmap.width,
                bitmap.height,
                requestedScale,
            )
            val (nativeWidth, nativeHeight) = UpscaleScalingStrategy.scaledDimensions(
                bitmap.width,
                bitmap.height,
                candidate.model.nativeScale.toFloat(),
            )
            val output = when (candidate.model.format) {
                UpscaleModelFormat.NCNN -> {
                    val param = candidate.model.artifact("param") ?: return null
                    val bin = candidate.model.artifact("bin") ?: return null
                    val session = ncnnSession(candidate, modelManager.fileFor(param).absolutePath, modelManager.fileFor(bin).absolutePath)
                        ?: return null
                    session.process(bitmap, nativeWidth, nativeHeight, candidate.model.tileSize, candidate.model.padding)
                }
                UpscaleModelFormat.ONNX -> {
                    val model = candidate.model.artifact("model") ?: return null
                    val session = ortSession(candidate, modelManager.fileFor(model).absolutePath) ?: return null
                    session.process(bitmap, nativeWidth, nativeHeight)
                }
            } ?: return null
            nativeBitmap = output
            scaled = if (output.width == targetWidth && output.height == targetHeight) {
                output
            } else {
                Bitmap.createScaledBitmap(output, targetWidth, targetHeight, true)
            }
            compressForCache(scaled)
        } finally {
            if (scaled !== null && scaled !== nativeBitmap) scaled.recycle()
            nativeBitmap?.recycle()
            bitmap.recycle()
        }
    }

    private fun ncnnSession(
        candidate: InferenceCandidate,
        paramPath: String,
        binPath: String,
    ): NativeUpscaleSession? = synchronized(sessionLock) {
        val key = "${candidate.backend}:${candidate.model.id}:${candidate.model.nativeScale}"
        ncnnSessions[key]?.let { return@synchronized it }
        val backend = if (candidate.backend == UpscalePreferences.Backend.VULKAN) {
            NativeUpscaler.Backend.VULKAN
        } else {
            NativeUpscaler.Backend.CPU
        }
        NativeUpscaler.create(
            paramPath = paramPath,
            binPath = binPath,
            backend = backend,
            nativeScale = candidate.model.nativeScale,
            inputOrder = candidate.model.inputOrder,
            outputOrder = candidate.model.outputOrder,
        )?.also { ncnnSessions[key] = it }
    }

    private fun ortSession(candidate: InferenceCandidate, modelPath: String): OrtUpscaleSession? = synchronized(sessionLock) {
        val key = "${candidate.backend}:${candidate.model.id}:${candidate.model.nativeScale}"
        ortSessions[key]?.let { return@synchronized it }
        OrtUpscaleSession.open(
            modelPath = modelPath,
            config = OrtUpscaleSession.Config(
                nativeScale = candidate.model.nativeScale,
                tileSize = candidate.model.tileSize,
                padding = candidate.model.padding,
                inputOrder = candidate.model.inputOrder,
                outputOrder = candidate.model.outputOrder,
            ),
            preferNnapi = candidate.backend == UpscalePreferences.Backend.NPU,
        )?.also { ortSessions[key] = it }
    }

    private fun clearSessions() {
        synchronized(sessionLock) {
            ncnnSessions.values.forEach { runCatching { it.close() } }
            ortSessions.values.forEach { runCatching { it.close() } }
            ncnnSessions.clear()
            ortSessions.clear()
        }
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) null else opts.outWidth to opts.outHeight
    }.getOrNull()

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        val (width, height) = decodeBounds(bytes) ?: return null
        if (width.toLong() * height > MAX_INPUT_PIXELS) {
            logcat(LogPriority.WARN) { "Upscale input too large ${width}x$height, skipping" }
            return null
        }
        return try {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
        } catch (e: OutOfMemoryError) {
            System.gc()
            logcat(LogPriority.ERROR, e) { "Upscale OOM decode ${bytes.size} bytes" }
            null
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Upscale decode failed" }
            null
        }
    }

    private fun compressForCache(bitmap: Bitmap): ByteArray? = runCatching {
        val pngOut = java.io.ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOut)) return null
        val png = pngOut.toByteArray().takeIf { it.isNotEmpty() }
        if (png != null && png.size <= MAX_PNG_BYTES) return png
        val webpOut = java.io.ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 95, webpOut)) return null
        webpOut.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun runUpscaleSimple(bytes: ByteArray, factor: Float, algo: UpscalePreferences.SimpleAlgo): ByteArray? {
        val bitmap = decodeBitmap(bytes) ?: return null
        var scaled: Bitmap? = null
        return try {
            if (UpscaleScalingStrategy.isNearIdentityScale(factor)) return null
            scaled = UpscaleScalingStrategy.upscaleBitmapWithAlgo(bitmap, factor, algo) ?: return null
            compressForCache(scaled)
        } finally {
            if (scaled !== null && scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    fun clearCache() = cacheManager.clear()

    fun cacheSizeBytes(): Long = cacheManager.sizeBytes()

    fun cacheFileCount(): Int = cacheManager.fileCount()

    fun pruneExpired() = cacheManager.pruneExpired()
}
