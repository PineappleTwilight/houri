package exh.yakuyomi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
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
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.engine.Translator
import mihon.core.concurrency.AppDispatchersHolder
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
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchersHolder.get().default)

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
                prefs.inpainterUniformFastPath().changes(),
            ).collect { invalidateComponents() }
        }
    }

    /** Human-readable reason reported when the device has too little RAM for the native pipeline. */
    val notEnoughMemoryReason: String
        get() = "not enough memory on this device — AI translation requires at least 3GB of RAM"

    fun isHardwareSupported(): Boolean = DeviceMemory.isMtlSupported(context)

    fun isStorageSupported(): Boolean {
        val dir = modelsDir()
        val usable = try {
            dir.usableSpace
        } catch (_: Exception) {
            -1L
        }
        if (usable in 1..(150L * 1024 * 1024)) return false
        return true
    }

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
                val ok = bin.isFile && bin.length() >= 1_000_000L
                if (!ok) logcat { "Model missing bin for $p" }
                !ok
            }
        ) {
            return null
        }
        if (!File(set.ocr).let { it.isFile && it.length() > 1_000_000L }) {
            logcat { "Model missing ocr ${set.ocr}" }
            return null
        }
        val totalBytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
        if (totalBytes > 0) logcat { "Models ready total ${totalBytes / (1024 * 1024)} MB" }
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
            colTrim = 1,
            rowTrim = 1,
            lineSpacing = 1.05f,
            adaptiveStroke = true,
            rtlSupport = false,
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
                adaptiveConcurrency = true,
            ),
            inpainter = defaults.inpainter.copy(
                method = prefs.inpainterMethod().get().takeIf { it in setOf("aot", "boxfill") } ?: "aot",
                tileSize = prefs.inpainterTileSize().get().coerceIn(256, 1024),
                maskDilate = prefs.inpainterMaskDilate().get().coerceIn(4f, 48f),
                bboxPad = prefs.inpainterBboxPad().get().coerceIn(0, 32),
                uniformFastPath = prefs.inpainterUniformFastPath().get(),
                featherRadius = 1,
                preserveAspect = true,
            ),
            translator = defaults.translator,
            pipeline = defaults.pipeline,
        )
    }

    private fun horizontalConfig(): li.joye.yakuyomi.engine.EngineConfig =
        defaultConfig().copy(
            render = defaultConfig().render.copy(orientation = li.joye.yakuyomi.engine.TextOrientation.HORIZONTAL),
        )

    private fun loadAlphabet(): List<String> = runCatching {
        context.assets.open("yakuyomi_alphabet.txt").bufferedReader().use { it.readLines().filter { l -> l.isNotBlank() } }
    }.getOrElse { emptyList() }.takeIf { it.isNotEmpty() } ?: listOf(" ")

    private fun buildIfNeeded(): Components? {
        components?.let { return it }
        return buildComponents()?.also { components = it }
    }

    private suspend fun buildIfNeededLocked(): Components? {
        components?.let { return it }
        return buildComponents()?.also { components = it }
    }

    private fun invalidateComponents() {
        scope.launch {
            pipelineMutex.withLock {
                val old = components.also { components = null }
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
    suspend fun translatePage(bitmap: Bitmap, translator: Translator?, targetLang: String? = null): PageResult =
        translatePage(bitmap, { _: Bitmap -> translator }, targetLang)

    /**
     * Tall-page entry point: slices the original [bitmap] at low-variance gutters before the
     * library's internal 4000px pre-scale, runs the existing [Pipeline] sequentially per slice
     * (still under [pipelineMutex]), then stitches one full-size output. The factory receives
     * each slice bitmap so providers get per-slice image context (vision) instead of the whole
     * page. Any [PageResult.Failed] slice fails the whole page (no partial output escapes);
     * [PageResult.Skipped] slices keep their original art; only an all-skipped page returns
     * Skipped. [PageStats] are summed and [PageAnalysis] regions are offset to page coordinates,
     * keeping only regions whose center lies in the slice's owned core (upper owns the overlap).
     */
    suspend fun translatePage(
        bitmap: Bitmap,
        translatorFactory: suspend (Bitmap) -> Translator?,
        targetLang: String? = null,
    ): PageResult = withContext(AppDispatchersHolder.get().default) {
        if (!isHardwareSupported()) {
            return@withContext PageResult.Failed(notEnoughMemoryReason, li.joye.yakuyomi.engine.PipelineErrorCode.INVALID_BITMAP)
        }
        if (!isStorageSupported()) {
            return@withContext PageResult.Failed("not enough storage for translation", li.joye.yakuyomi.engine.PipelineErrorCode.UNKNOWN)
        }
        pipelineMutex.withLock {
            val c = buildIfNeededLocked() ?: return@withContext PageResult.Failed("models not ready", li.joye.yakuyomi.engine.PipelineErrorCode.UNKNOWN)
            val cfg = if (shouldForceHorizontal(targetLang)) horizontalConfig() else defaultConfig()
            val errs = cfg.validate()
            if (errs.isNotEmpty()) return@withContext PageResult.Failed("invalid config: ${errs.first()}", li.joye.yakuyomi.engine.PipelineErrorCode.UNKNOWN)
            if (!prefs.longPageSlicingEnabled().get() || !LongPageSlicer.shouldSlice(bitmap.width, bitmap.height)) {
                return@withContext Pipeline(c.detector, c.ocr, translatorFactory(bitmap), c.inpainter, cfg, resolveTypeface()).translatePage(bitmap)
            }
            translateSliced(c, cfg, bitmap, translatorFactory)
        }
    }

    private suspend fun translateSliced(
        c: Components,
        cfg: li.joye.yakuyomi.engine.EngineConfig,
        page: Bitmap,
        translatorFactory: suspend (Bitmap) -> Translator?,
    ): PageResult {
        val width = page.width
        val height = page.height
        val slices = LongPageSlicer.buildSlices(height, LongPageSlicer.planCuts(page))
        if (slices.size < 2) {
            return Pipeline(c.detector, c.ocr, translatorFactory(page), c.inpainter, cfg, resolveTypeface()).translatePage(page)
        }
        val pieces = mutableListOf<SlicePiece>()
        var failure: PageResult.Failed? = null
        for (slice in slices) {
            val input = try {
                Bitmap.createBitmap(page, 0, slice.y, width, slice.height)
            } catch (t: Throwable) {
                failure = PageResult.Failed("slice crop failed: ${t.message}", li.joye.yakuyomi.engine.PipelineErrorCode.UNKNOWN)
                break
            }
            val result = try {
                val translator = try {
                    translatorFactory(input)
                } catch (t: Throwable) {
                    failure = PageResult.Failed("slice translator failed: ${t.message}", li.joye.yakuyomi.engine.PipelineErrorCode.TRANSLATE_FAILED)
                    break
                }
                Pipeline(c.detector, c.ocr, translator, c.inpainter, cfg, resolveTypeface()).translatePage(input)
            } finally {
                runCatching { input.recycle() }
            }
            when (result) {
                is PageResult.Failed -> {
                    failure = result
                    break
                }
                is PageResult.Skipped -> {
                    val art = try {
                        Bitmap.createBitmap(page, 0, slice.y, width, slice.height)
                    } catch (t: Throwable) {
                        failure = PageResult.Failed("slice art copy failed: ${t.message}", li.joye.yakuyomi.engine.PipelineErrorCode.UNKNOWN)
                        break
                    }
                    pieces.add(SlicePiece(slice, art, result.stats, null, result.reason))
                }
                is PageResult.Translated -> {
                    pieces.add(SlicePiece(slice, result.page, result.stats, result.analysis, null))
                }
            }
        }
        if (failure != null) {
            recyclePieces(pieces)
            return failure
        }
        if (pieces.size != slices.size) {
            recyclePieces(pieces)
            return PageResult.Failed("sliced translation incomplete", li.joye.yakuyomi.engine.PipelineErrorCode.UNKNOWN)
        }
        if (pieces.all { it.analysis == null }) {
            val stats = sumStats(pieces.map { it.stats })
            val reason = pieces.mapNotNull { it.skipReason }.distinct().take(2).joinToString("; ").ifBlank { "No text detected" }
            recyclePieces(pieces)
            return PageResult.Skipped(reason, stats, li.joye.yakuyomi.engine.PipelineErrorCode.DETECT_FAILED)
        }
        val stitched = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            recyclePieces(pieces)
            return PageResult.Failed("slice stitch failed: ${t.message}", li.joye.yakuyomi.engine.PipelineErrorCode.UNKNOWN)
        }
        try {
            val canvas = Canvas(stitched)
            // Lower slices first, upper slices last: the upper slice owns the overlap band.
            for (i in pieces.indices.reversed()) {
                val piece = pieces[i]
                val dst = Rect(0, piece.slice.y, width, piece.slice.y + piece.slice.height)
                canvas.drawBitmap(piece.bitmap, null, dst, null)
            }
        } catch (t: Throwable) {
            recyclePieces(pieces)
            runCatching { stitched.recycle() }
            return PageResult.Failed("slice stitch failed: ${t.message}", li.joye.yakuyomi.engine.PipelineErrorCode.UNKNOWN)
        }
        val mask = stitchMask(pieces, width, height)
        val regions = offsetRegions(pieces, slices)
        pieces.forEach { runCatching { it.bitmap.recycle() } }
        val stats = sumStats(pieces.map { it.stats })
        val analysis = if (mask != null) {
            PageAnalysis(mask, regions)
        } else {
            regions.takeIf { it.isNotEmpty() }?.let {
                val fallbackMask = try {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                } catch (_: Throwable) {
                    null
                } ?: return PageResult.Translated(stitched, stats, null)
                PageAnalysis(fallbackMask, it)
            }
        }
        return PageResult.Translated(stitched, stats, analysis)
    }

    private class SlicePiece(
        val slice: LongPageSlicer.Slice,
        val bitmap: Bitmap,
        val stats: PageStats,
        val analysis: PageAnalysis?,
        val skipReason: String?,
    )

    private fun recyclePieces(pieces: List<SlicePiece>) {
        pieces.forEach { piece ->
            runCatching { piece.bitmap.recycle() }
            piece.analysis?.mask?.let { mask -> runCatching { mask.recycle() } }
        }
    }

    private fun sumStats(all: List<PageStats>): PageStats = PageStats(
        lines = all.sumOf { it.lines },
        regions = all.sumOf { it.regions },
        kept = all.sumOf { it.kept },
        detectMs = all.sumOf { it.detectMs },
        ocrMs = all.sumOf { it.ocrMs },
        translateMs = all.sumOf { it.translateMs },
        inpaintMs = all.sumOf { it.inpaintMs },
        renderMs = all.sumOf { it.renderMs },
        wallMs = all.sumOf { it.wallMs },
        promptTokens = all.sumOf { it.promptTokens },
        completionTokens = all.sumOf { it.completionTokens },
    )

    private fun recycleAnalysisMasks(pieces: List<SlicePiece>) {
        pieces.mapNotNull { it.analysis?.mask }.forEach { mask -> runCatching { mask.recycle() } }
    }

    private fun stitchMask(pieces: List<SlicePiece>, width: Int, height: Int): Bitmap? {
        if (pieces.none { it.analysis != null }) return null
        val mask = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            recycleAnalysisMasks(pieces)
            return null
        }
        return try {
            val canvas = Canvas(mask)
            for (i in pieces.indices.reversed()) {
                val piece = pieces[i]
                val sliceMask = piece.analysis?.mask ?: continue
                if (sliceMask.isRecycled) continue
                val dst = Rect(0, piece.slice.y, width, piece.slice.y + piece.slice.height)
                canvas.drawBitmap(sliceMask, null, dst, null)
            }
            mask
        } catch (_: Throwable) {
            runCatching { mask.recycle() }
            null
        } finally {
            recycleAnalysisMasks(pieces)
        }
    }

    private fun offsetRegions(pieces: List<SlicePiece>, slices: List<LongPageSlicer.Slice>): List<TextRegion> {
        val out = mutableListOf<TextRegion>()
        for (i in pieces.indices) {
            val piece = pieces[i]
            val analysis = piece.analysis ?: continue
            for (region in analysis.regions) {
                val cyPage = region.cy + piece.slice.y
                if (!LongPageSlicer.ownsCenter(i, slices, cyPage)) continue
                val lines = region.lines.map { line ->
                    TextLine(line.quad.map { Pt(it.x, it.y + piece.slice.y) }, line.score).also {
                        it.direction = line.direction
                        it.text = line.text
                        it.translatedText = line.translatedText
                    }
                }
                TextRegion(lines, region.direction, region.angle, region.cx, cyPage, region.boxW, region.boxH).also {
                    it.translatedText = region.translatedText
                    it.onArt = region.onArt
                    it.dbgStd = region.dbgStd
                    it.dbgWhite = region.dbgWhite
                    out.add(it)
                }
            }
        }
        return out
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
