package exh.yakuyomi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

data class TextBlock(
    val text: String,
    val bounds: Rect,
    val angle: Float = 0f,
)

@SingleIn(AppScope::class)
@Inject
class YakuyomiEngine {
    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val koreanRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /**
     * Multilingual detection: runs Latin, Japanese, Chinese and Korean recognizers.
     * If [sourceLangHint] is provided (JA/KO/ZH/EN), that recognizer is tried first and
     * early-exits on success to save work; otherwise all run in parallel and results are merged
     * with IoU-based deduplication.
     */
    suspend fun detectTextBlocks(bitmap: Bitmap, sourceLangHint: String? = null): List<TextBlock> =
        withContext(Dispatchers.Default) {
            if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext emptyList()
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val hint = sourceLangHint?.trim()?.uppercase()
                val ordered = when (hint) {
                    "JA", "JP", "JPN" -> listOf(
                        japaneseRecognizer to "JA",
                        chineseRecognizer to "ZH",
                        koreanRecognizer to "KO",
                        latinRecognizer to "EN",
                    )
                    "KO", "KR", "KOR" -> listOf(
                        koreanRecognizer to "KO",
                        japaneseRecognizer to "JA",
                        chineseRecognizer to "ZH",
                        latinRecognizer to "EN",
                    )
                    "ZH", "ZH-HANS", "ZH-HANT", "CN" -> listOf(
                        chineseRecognizer to "ZH",
                        japaneseRecognizer to "JA",
                        koreanRecognizer to "KO",
                        latinRecognizer to "EN",
                    )
                    "EN" -> listOf(
                        latinRecognizer to "EN",
                        japaneseRecognizer to "JA",
                        chineseRecognizer to "ZH",
                        koreanRecognizer to "KO",
                    )
                    else -> listOf(
                        japaneseRecognizer to "JA",
                        chineseRecognizer to "ZH",
                        koreanRecognizer to "KO",
                        latinRecognizer to "EN",
                    )
                }

                // If hinted, try hinted recognizer first and early-exit if it finds text
                if (hint != null) {
                    val primary = ordered.first()
                    val primaryBlocks = recognizeWith(primary.first, image, bitmap)
                    if (primaryBlocks.isNotEmpty()) return@withContext primaryBlocks
                    // Fall through to run remaining
                }

                // Run all in parallel and merge
                val deferred = ordered.map { (rec, _) ->
                    async { recognizeWith(rec, image, bitmap) }
                }
                val allLists = deferred.awaitAll()
                mergeBlocks(allLists.flatten(), bitmap.width, bitmap.height)
            } catch (e: Exception) {
                logcat { "Yakuyomi detect failed: ${e.message}" }
                emptyList()
            }
        }

    // Backwards-compatible overload
    suspend fun detectTextBlocks(bitmap: Bitmap): List<TextBlock> = detectTextBlocks(bitmap, null)

    private suspend fun recognizeWith(
        recognizer: TextRecognizer,
        image: InputImage,
        bitmap: Bitmap,
    ): List<TextBlock> {
        return try {
            val result = recognizer.process(image).await()
            val blocks = mutableListOf<TextBlock>()
            for (block in result.textBlocks) {
                val lines = block.lines
                if (lines.isNotEmpty()) {
                    for (line in lines) {
                        val box = line.boundingBox ?: continue
                        if (box.width() <= 0 || box.height() <= 0) continue
                        val txt = line.text?.trim().orEmpty()
                        if (txt.isEmpty()) continue
                        val clamped = Rect(
                            box.left.coerceIn(0, bitmap.width),
                            box.top.coerceIn(0, bitmap.height),
                            box.right.coerceIn(0, bitmap.width),
                            box.bottom.coerceIn(0, bitmap.height),
                        )
                        if (clamped.width() <= 0 || clamped.height() <= 0) continue
                        blocks.add(TextBlock(text = txt, bounds = clamped))
                    }
                } else {
                    val box = block.boundingBox ?: continue
                    if (box.width() <= 0 || box.height() <= 0) continue
                    val txt = block.text?.trim().orEmpty()
                    if (txt.isEmpty()) continue
                    val clamped = Rect(
                        box.left.coerceIn(0, bitmap.width),
                        box.top.coerceIn(0, bitmap.height),
                        box.right.coerceIn(0, bitmap.width),
                        box.bottom.coerceIn(0, bitmap.height),
                    )
                    if (clamped.width() <= 0 || clamped.height() <= 0) continue
                    blocks.add(TextBlock(text = txt, bounds = clamped))
                }
            }
            blocks
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mergeBlocks(blocks: List<TextBlock>, width: Int, height: Int): List<TextBlock> {
        if (blocks.isEmpty()) return emptyList()
        // Sort by area descending so large blocks keep priority
        val sorted = blocks.sortedByDescending { it.bounds.width() * it.bounds.height() }
        val kept = mutableListOf<TextBlock>()
        for (candidate in sorted) {
            var overlaps = false
            for (existing in kept) {
                if (iou(existing.bounds, candidate.bounds) > 0.5f) {
                    overlaps = true
                    break
                }
                // Also treat near-identical text at similar location as duplicate
                if (candidate.text == existing.text && iou(existing.bounds, candidate.bounds) > 0.3f) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) kept.add(candidate)
        }
        // Return in reading order (top-to-bottom, left-to-right)
        return kept.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun iou(a: Rect, b: Rect): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0)
        val interH = (interBottom - interTop).coerceAtLeast(0)
        val interArea = interW * interH
        if (interArea == 0) return 0f
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - interArea
        return if (union <= 0) 0f else interArea.toFloat() / union
    }

    suspend fun ocrBlocks(bitmap: Bitmap, blocks: List<TextBlock>): List<TextBlock> = withContext(Dispatchers.Default) {
        blocks.mapNotNull { b ->
            val t = b.text.trim()
            if (t.isEmpty()) null else b.copy(text = t)
        }
    }

    suspend fun removeText(bitmap: Bitmap, blocks: List<TextBlock>): Bitmap = withContext(Dispatchers.Default) {
        if (blocks.isEmpty()) return@withContext bitmap
        try {
            val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val paint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            for (b in blocks) {
                val left = b.bounds.left.coerceIn(0, out.width)
                val top = b.bounds.top.coerceIn(0, out.height)
                val right = b.bounds.right.coerceIn(0, out.width)
                val bottom = b.bounds.bottom.coerceIn(0, out.height)
                if (right > left && bottom > top) {
                    canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
                }
            }
            out
        } catch (e: Exception) {
            logcat { "Yakuyomi remove failed: ${e.message}" }
            bitmap
        }
    }

    suspend fun typeset(
        base: Bitmap,
        blocks: List<Pair<TextBlock, String>>,
        targetLang: String,
    ): Bitmap = withContext(Dispatchers.Default) {
        if (blocks.isEmpty()) return@withContext base
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            alpha = 220
        }
        val textFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            isFakeBoldText = true
        }
        val textStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isFakeBoldText = true
        }
        for ((block, translated) in blocks) {
            val clean = translated.trim()
            if (clean.isEmpty()) continue
            val bounds = block.bounds
            if (bounds.width() <= 0 || bounds.height() <= 0) continue
            val isCjkTarget = targetLang.uppercase() in setOf("JA", "KO", "ZH", "ZH-HANS", "ZH-HANT")
            val baseSize = bounds.height() * if (isCjkTarget) 0.5f else 0.6f
            val fontSize = baseSize.coerceIn(14f, 44f)
            textFillPaint.textSize = fontSize
            textStrokePaint.textSize = fontSize

            val words = if (clean.contains(" ")) clean.split(" ") else clean.map { it.toString() }
            var y = bounds.top + fontSize
            val maxY = bounds.bottom - 4
            val lines = mutableListOf<String>()
            var line = StringBuilder()
            for (w in words) {
                if (w.isEmpty()) continue
                val test = if (line.isEmpty()) {
                    w
                } else if (clean.contains(" ")) {
                    "$line $w"
                } else {
                    line.toString() + w
                }
                val widthNeeded = textFillPaint.measureText(test)
                val available = bounds.width() - 8
                if (widthNeeded > available && line.isNotEmpty()) {
                    lines.add(line.toString())
                    line = StringBuilder(w)
                    if (y + fontSize > maxY && lines.size >= 4) break
                } else {
                    if (line.isNotEmpty() && clean.contains(" ")) line.append(" ")
                    line.append(w)
                }
            }
            if (line.isNotEmpty() && y <= maxY) lines.add(line.toString())
            val maxLines = ((bounds.height() / (fontSize + 4)).toInt()).coerceAtLeast(1)
            val displayLines = if (lines.size > maxLines) lines.take(maxLines - 1) + listOf(lines[maxLines - 1].take(20) + "…") else lines
            var curY = bounds.top + fontSize
            for (text in displayLines) {
                if (curY > maxY) break
                val x = bounds.left + (bounds.width() - textFillPaint.measureText(text)) / 2
                val bgLeft = (bounds.left - 4).coerceAtLeast(0).toFloat()
                val bgRight = (bounds.right + 4).coerceAtMost(out.width).toFloat()
                val bgTop = (curY - fontSize - 4).coerceAtLeast(0f)
                val bgBottom = (curY + 6).coerceAtMost(out.height.toFloat())
                canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, 8f, 8f, bgPaint)
                canvas.drawText(text, x, curY, textStrokePaint)
                canvas.drawText(text, x, curY, textFillPaint)
                curY += fontSize + 4
            }
        }
        out
    }

    fun bitmapToWebP(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        val q = quality.coerceIn(1, 100)
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, stream)
        return stream.toByteArray()
    }

    fun webPToImageBytes(webp: ByteArray): ByteBuffer = ByteBuffer.wrap(webp)
}
