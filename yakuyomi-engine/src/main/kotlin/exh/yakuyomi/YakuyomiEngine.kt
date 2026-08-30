package exh.yakuyomi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
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
) {
    private fun modelsDir(): File = File(context.filesDir, "yakuyomi_models")

    private fun libModelSet(): ModelSet? {
        val dir = modelsDir()
        if (!dir.exists()) return null
        val files = dir.listFiles()?.map { it.name to it.absolutePath } ?: return null
        return ModelSet.resolve(files)
    }

    private class Components(
        val detector: Detector,
        val ocr: Ocr,
        val inpainter: Inpainter,
    )

    @Volatile
    private var components: Components? = null

    private val pipelineMutex = Mutex()

    private val defaultConfig = li.joye.yakuyomi.engine.EngineConfig()
    private val horizontalConfig = defaultConfig.copy(
        render = defaultConfig.render.copy(orientation = li.joye.yakuyomi.engine.TextOrientation.HORIZONTAL),
    )

    private fun loadAlphabet(): List<String> =
        context.assets.open("yakuyomi_alphabet.txt").bufferedReader().readLines()

    private fun ensureComponents(): Components? {
        components?.let { return it }
        return synchronized(this) {
            components ?: buildComponents()?.also { components = it } ?: return@synchronized null
        }
    }

    private fun buildComponents(): Components? {
        val set = libModelSet() ?: run {
            logcat { "Yakuyomi models not ready" }
            return null
        }
        return try {
            val detector = Detector(set.detectorNcnn ?: error("missing detector .param"))
            val ocr = Ocr(set.ocr, loadAlphabet())
            val inpainter = Inpainter(set.aotInpainterNcnn ?: error("missing inpainter .param"))
            detector.warmUp()
            ocr.warmUp()
            inpainter.warmUp()
            Components(detector, ocr, inpainter)
        } catch (e: Exception) {
            logcat { "Yakuyomi engine init failed: ${e.message}" }
            null
        }
    }

    /**
     * Best-effort warm-up: builds the native components (downloading nothing) so the first page
     * translation does not pay the cold-start cost. Idempotent and cheap after first build.
     */
    fun prewarm(): Boolean = ensureComponents() != null

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
        val c = ensureComponents() ?: return@withContext PageResult.Failed("models not ready")
        pipelineMutex.withLock {
            val cfg = if (shouldForceHorizontal(targetLang)) horizontalConfig else defaultConfig
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
