package exh.yakuyomi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.Translator
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Thin wrapper over the joyeli/yakuyomi-engine library. Owns and lazily warms the native NCNN
 * detector / ONNX OCR / NCNN inpainter sessions (App-scoped, closed with the app) and runs the
 * library [Pipeline] per page. The translation stage is injected by the caller so Komikku's
 * breadcrumb-aware LLM stays in the loop.
 */
@SingleIn(AppScope::class)
@Inject
class YakuyomiEngine(
    private val context: Context,
    private val prefs: TranslationPreferences,
    private val modelManager: ModelManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Serializes native-session access (build/close/translate) because the NCNN backends are not
     * thread-safe. MUST be declared before the [init] block: the status collector launched there
     * may run on a worker thread while the constructor is still executing the later field
     * initializers, and [invalidateComponents] reads this field — a not-yet-initialized mutex
     * crashes with NPE on `MutexImpl.lock`.
     */
    private val pipelineMutex = Mutex()

    init {
        // Keep the cached native sessions aligned with the on-disk model state: rebuild (or
        // drop) them when models are (re)downloaded or cleared, so the engine never serves
        // sessions loaded from deleted files and honestly reports "not ready" when they're gone.
        modelManager.status
            .onEach { s ->
                if (s.state == ModelManager.State.READY || s.state == ModelManager.State.NOT_INSTALLED) {
                    invalidateComponents()
                }
            }
            .launchIn(scope)
        // Tuning changes require rebuilding native sessions that were constructed with the old config.
        scope.launch {
            kotlinx.coroutines.flow.merge(
                prefs.detectorInputSize().changes(),
                prefs.detectorBoxThreshold().changes(),
                prefs.detectorSegThreshold().changes(),
                prefs.ocrMinProb().changes(),
                prefs.ocrBicubic().changes(),
                prefs.ocrUnsharp().changes(),
                prefs.inpainterMethod().changes(),
                prefs.inpainterTileSize().changes(),
                prefs.inpainterMaskDilate().changes(),
                prefs.inpainterBboxPad().changes(),
            ).collect { invalidateComponents() }
        }
    }

    /** Human-readable reason reported when the device has too little RAM for the native pipeline. */
    val notEnoughMemoryReason: String
        get() = "not enough memory on this device — AI translation requires at least 3GB of RAM"

    /**
     * Whether the on-device native pipeline can run on this device at all. Currently gates on total
     * RAM: the detector + OCR + inpainter sessions together need more address space than low-RAM
     * devices have, and a failed native allocation SIGSEGVs the process (uncatchable in Kotlin).
     */
    fun isHardwareSupported(): Boolean = DeviceMemory.isMtlSupported(context)

    private fun modelsDir(): File = File(context.filesDir, "yakuyomi_models")

    private fun libModelSet(): ModelSet? {
        val dir = modelsDir()
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 1024 }?.map { it.name to it.absolutePath } ?: return null
        if (files.isEmpty()) return null
        val set = ModelSet.resolve(files) ?: return null
        val params = listOfNotNull(set.detectorNcnn, set.aotInpainterNcnn)
        if (params.any { p ->
                val bin = File(p.removeSuffix(".param") + ".bin")
                !bin.isFile || bin.length() < 1_000_000L
            }
        ) {
            return null
        }
        if (set.ocr != null && !File(set.ocr).let { it.isFile && it.length() > 1_000_000L }) return null
        return set
    }

    private class Components(
        val detector: Detector,
        val ocr: Ocr,
        val inpainter: Inpainter,
    ) {
        fun closeAll() {
            runCatching { detector.close() }
            runCatching { ocr.close() }
            runCatching { inpainter.close() }
        }
    }

    @Volatile
    private var components: Components? = null

    private fun renderConfig(): li.joye.yakuyomi.engine.RenderConfig {
        val defaults = li.joye.yakuyomi.engine.EngineConfig().render
        val max = prefs.renderFontSizeMax().get().coerceIn(30, 100)
        val min = prefs.renderFontSizeMin().get().coerceIn(6, 20).coerceAtMost(max - 2)
        return defaults.copy(
            colorMode = "fixed",
            fixedTextColor = resolveTextColor(),
            fontScale = prefs.renderFontScale().get().coerceIn(0.6f, 1.2f),
            expandW = prefs.renderExpandW().get().coerceIn(1.0f, 2.0f),
            expandH = prefs.renderExpandH().get().coerceIn(1.0f, 2.0f),
            tateChuYoko = prefs.renderTateChuYoko().get(),
            fontSizeMax = max,
            fontSizeMin = min,
        )
    }

    /** Custom hex override wins when parseable; otherwise the preset int preference. */
    private fun resolveTextColor(): Int {
        val hex = prefs.translationTextColorHex().get().trim().removePrefix("#")
        if (hex.isNotEmpty()) {
            val v = hex.toLongOrNull(16) ?: return prefs.translationTextColor().get()
            return when (hex.length) {
                6 -> 0xFF000000.toInt() or v.toInt() // RRGGBB → opaque
                8 -> v.toInt() // AARRGGBB
                else -> prefs.translationTextColor().get()
            }
        }
        return prefs.translationTextColor().get()
    }

    private fun defaultConfig(): li.joye.yakuyomi.engine.EngineConfig {
        val defaults = li.joye.yakuyomi.engine.EngineConfig()
        return li.joye.yakuyomi.engine.EngineConfig(
            render = renderConfig(),
            detector = defaults.detector.copy(
                dbnetInputSize = prefs.detectorInputSize().get().coerceIn(512, 1536),
                dbBoxThreshold = prefs.detectorBoxThreshold().get().coerceIn(0.3f, 0.9f),
                segThreshold = prefs.detectorSegThreshold().get().coerceIn(0.05f, 0.4f),
            ),
            ocr = defaults.ocr.copy(
                minProb = prefs.ocrMinProb().get().coerceIn(0.2f, 0.9f),
                useBicubic = prefs.ocrBicubic().get(),
                ocrUnsharp = prefs.ocrUnsharp().get(),
            ),
            inpainter = defaults.inpainter.copy(
                method = prefs.inpainterMethod().get().takeIf { it in setOf("aot", "boxfill") } ?: "aot",
                tileSize = prefs.inpainterTileSize().get().coerceIn(256, 1024),
                maskDilate = prefs.inpainterMaskDilate().get().coerceIn(4f, 48f),
                bboxPad = prefs.inpainterBboxPad().get().coerceIn(0, 32),
            ),
            translator = defaults.translator,
        )
    }

    private fun horizontalConfig(): li.joye.yakuyomi.engine.EngineConfig =
        defaultConfig().copy(
            render = defaultConfig().render.copy(orientation = li.joye.yakuyomi.engine.TextOrientation.HORIZONTAL),
        )

    private fun loadAlphabet(): List<String> = runCatching {
        context.assets.open("yakuyomi_alphabet.txt").bufferedReader().use { it.readLines().filter { l -> l.isNotBlank() } }
    }.getOrElse { emptyList() }.takeIf { it.isNotEmpty() } ?: listOf(" ")

    private fun buildIfNeeded(): Components? = synchronized(this) {
        components ?: buildComponents()?.also { components = it }
    }

    /**
     * Drops the cached native sessions so the next [translatePage] rebuilds from the current
     * on-disk models. Runs the close under [pipelineMutex] — the same lock that guards pipeline
     * execution — so an in-flight translatePage can never touch a closed session.
     */
    private fun invalidateComponents() {
        scope.launch {
            pipelineMutex.withLock {
                val old = synchronized(this@YakuyomiEngine) {
                    components.also { components = null }
                }
                if (old != null) old.closeAll()
            }
        }
    }

    @Volatile
    private var lastBuildFailureMs = 0L

    private fun buildComponents(): Components? {
        if (!isHardwareSupported()) {
            logcat { "Yakuyomi engine skipped: device has too little RAM" }
            return null
        }
        if (System.currentTimeMillis() - lastBuildFailureMs < 5000) return null
        val set = libModelSet() ?: run {
            logcat { "Yakuyomi models not ready" }
            return null
        }
        return try {
            val alphabet = loadAlphabet()
            if (alphabet.size < 10) {
                logcat { "Yakuyomi alphabet load failed, size=${alphabet.size}" }
                return null
            }
            val cfg = defaultConfig()
            val detector = Detector(set.detectorNcnn ?: error("missing detector .param"), cfg.detector)
            val ocr = Ocr(set.ocr, alphabet, cfg.ocr)
            val inpainter = Inpainter(set.aotInpainterNcnn ?: error("missing inpainter .param"), cfg.inpainter)
            try {
                detector.warmUp()
                ocr.warmUp()
                inpainter.warmUp()
            } catch (e: Throwable) {
                runCatching { detector.close() }
                runCatching { ocr.close() }
                runCatching { inpainter.close() }
                throw e
            }
            Components(detector, ocr, inpainter)
        } catch (e: Throwable) {
            lastBuildFailureMs = System.currentTimeMillis()
            logcat { "Yakuyomi engine init failed: ${e.message}" }
            null
        }
    }

    /**
     * Best-effort warm-up: builds the native components (downloading nothing) so the first page
     * translation does not pay the cold-start cost. Idempotent and cheap after first build.
     */
    fun prewarm(): Boolean = buildIfNeeded() != null

    /**
     * Resolves the user-selected typeset font to an Android [Typeface]. Returns null for
     * "default" so the engine falls back to the system default. Resolved per call (cheap)
     * so preference changes take effect without restarting.
     */
    private fun resolveTypeface(): Typeface? {
        val name = prefs.fontFamily().get()
        if (name.isBlank() || name.equals("default", ignoreCase = true)) return null
        return try {
            Typeface.create(name, Typeface.NORMAL)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Runs the full library pipeline (detect → OCR → group → translate → inpaint → render) on one
     * page. [translator] is the breadcrumb-aware LLM stage; pass null to skip translation (debug).
     * Serialized via Mutex because NCNN native backends are not thread-safe.
     */
    suspend fun translatePage(bitmap: Bitmap, translator: Translator?, targetLang: String? = null): PageResult = withContext(Dispatchers.Default) {
        if (!isHardwareSupported()) {
            return@withContext PageResult.Failed(notEnoughMemoryReason)
        }
        // Build and run under the same lock [invalidateComponents] uses to close stale
        // sessions, so this call can never race a close of the components it captured.
        pipelineMutex.withLock {
            val c = buildIfNeeded() ?: return@withContext PageResult.Failed("models not ready")
            val cfg = if (shouldForceHorizontal(targetLang)) horizontalConfig() else defaultConfig()
            Pipeline(c.detector, c.ocr, translator, c.inpainter, cfg, resolveTypeface()).translatePage(bitmap)
        }
    }

    private fun shouldForceHorizontal(lang: String?): Boolean {
        if (lang == null) return false
        val l = lang.trim().lowercase()
        // Keep AUTO for CJK targets where vertical layout is expected; force horizontal for Latin/other LTR targets
        val cjkTargets = setOf("ja", "zh", "ko")
        if (cjkTargets.any { l.startsWith(it) }) return false
        // For Latin and other LTR languages, force horizontal to avoid vertical rendering inherited from source
        return true
    }

    fun bitmapToWebP(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }
}
