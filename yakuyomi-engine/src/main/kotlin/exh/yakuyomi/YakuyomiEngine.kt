package exh.yakuyomi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer

data class TextBlock(
    val text: String,
    val bounds: Rect,
    val angle: Float = 0f,
)

@SingleIn(AppScope::class)
@Inject
class YakuyomiEngine {
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    suspend fun detectTextBlocks(bitmap: Bitmap): List<TextBlock> = withContext(Dispatchers.Default) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext emptyList()
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            // Use line-level granularity for better inpaint/typeset alignment
            val blocks = mutableListOf<TextBlock>()
            for (block in result.textBlocks) {
                // Prefer lines; fallback to block if no lines
                val lines = block.lines
                if (lines.isNotEmpty()) {
                    for (line in lines) {
                        val box = line.boundingBox ?: continue
                        val txt = line.text?.trim().orEmpty()
                        // Keep even empty text blocks for detection, OCR will filter later
                        // But skip zero-area boxes
                        if (box.width() <= 0 || box.height() <= 0) continue
                        // Clamp to bitmap bounds
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
                    val clamped = Rect(
                        box.left.coerceIn(0, bitmap.width),
                        box.top.coerceIn(0, bitmap.height),
                        box.right.coerceIn(0, bitmap.width),
                        box.bottom.coerceIn(0, bitmap.height),
                    )
                    if (clamped.width() <= 0 || clamped.height() <= 0) continue
                    blocks.add(TextBlock(text = block.text?.trim().orEmpty(), bounds = clamped))
                }
            }
            // If ML Kit found nothing, return empty so caller can SKIPP (matches original stub behavior for blank pages)
            blocks
        } catch (e: Exception) {
            logcat { "Yakuyomi detect failed: ${e.message}" }
            emptyList()
        }
    }

    suspend fun ocrBlocks(bitmap: Bitmap, blocks: List<TextBlock>): List<TextBlock> = withContext(Dispatchers.Default) {
        // ML Kit already OCR'd during detection; just trim and filter empty
        // Keep blocks with non-empty text after trim; if all empty, return empty to signal SKIPP
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
