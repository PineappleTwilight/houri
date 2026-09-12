package exh.yakuyomi

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.log.xLogD
import exh.log.xLogW
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
private data class IchigoTranslateRequest(
    @SerialName("base64Images") val base64Images: List<String>,
    @SerialName("targetLangCode") val targetLangCode: String,
    @SerialName("fingerprint") val fingerprint: String,
    @SerialName("clientUuid") val clientUuid: String,
    @SerialName("translationModel") val translationModel: String? = null,
)

@Serializable
private data class IchigoTranslateResponse(
    val images: List<List<IchigoTranslation>> = emptyList(),
)

@Serializable
data class IchigoTranslation(
    @SerialName("originalLanguage") val originalLanguage: String = "",
    @SerialName("translatedText") val translatedText: String = "",
    @SerialName("minX") val minX: Int = 0,
    @SerialName("minY") val minY: Int = 0,
    @SerialName("maxX") val maxX: Int = 0,
    @SerialName("maxY") val maxY: Int = 0,
)

@SingleIn(AppScope::class)
@Inject
class MangaTranslatorService(
    private val context: Context,
    private val prefs: TranslationPreferences,
    private val client: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun baseUrl(): String {
        val raw = prefs.mangaTranslatorBaseUrl().get().trim().trimEnd('/')
        if (raw.isBlank()) return "https://ichigo.moe"
        val lower = raw.lowercase()
        if (!lower.startsWith("https://")) return "https://ichigo.moe"
        return raw
    }

    private fun clientUuid(): String {
        val store = prefs.mangaTranslatorClientUuid().get()
        if (store.isNotBlank() && store.length >= 32) return store
        val fresh = UUID.randomUUID().toString()
        prefs.mangaTranslatorClientUuid().set(fresh)
        return fresh
    }

    fun fingerprint(): String {
        val cached = prefs.mangaTranslatorFingerprint().get()
        if (cached.isNotBlank()) return cached
        val fresh = buildFingerprint()
        prefs.mangaTranslatorFingerprint().set(fresh)
        return fresh
    }

    private fun buildFingerprint(): String {
        val webGl = "android-gpu-${Build.HARDWARE}-unknown"
        val hardware = "${Runtime.getRuntime().availableProcessors()}-${deviceMemoryBucket()}"
        val connection = "unknown-unknown-unknown-unknown-false"
        val timezone = java.util.TimeZone.getDefault().rawOffset / 60000
        val screen = screenInfo()
        val canvas = canvasHash()
        val browser = "sw,ls,ss,idb,geo,notif,perm,cookie,online,conn"
        val language = "${java.util.Locale.getDefault().language}-${java.util.Locale.getDefault()}"
        val touch = "0-false-false-false"
        val orientation = "portrait-primary-0-false"
        val ua = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.VERSION.SDK_INT}"
        val perf = "0-0-0-0-unknown-0-0"
        val components = listOf(webGl, hardware, connection, timezone.toString(), screen, canvas, browser, language, touch, orientation, ua, perf)
        return hashString(components.joinToString("-"))
    }

    private fun deviceMemoryBucket(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            val totalGb = mi.totalMem / (1024L * 1024L * 1024L)
            when {
                totalGb >= 8 -> "8"
                totalGb >= 6 -> "6"
                totalGb >= 4 -> "4"
                else -> totalGb.toString()
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun screenInfo(): String {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getMetrics(dm)
            val w = dm.widthPixels
            val h = dm.heightPixels
            val d = dm.densityDpi
            val ratio = dm.density
            "$w×$h-$d-$d-$w×$h-$ratio"
        } catch (_: Exception) {
            "screen-unavailable"
        }
    }

    private fun canvasHash(): String {
        return try {
            val payload = "${Build.BOARD}${Build.BRAND}${Build.DEVICE}${Build.DISPLAY}${Build.FINGERPRINT}"
            var hash = 0
            for (c in payload) {
                hash = (hash shl 5) - hash + c.code
                hash = hash and hash
            }
            hash.toString()
        } catch (_: Exception) {
            "canvas-error"
        }
    }

    private fun hashString(input: String): String {
        var hash = 5381
        for (c in input) {
            hash = (hash * 33) xor c.code
        }
        val hex = (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        var extended = hex
        val chunkSize = kotlin.math.ceil(input.length / 4.0).toInt().coerceAtLeast(1)
        for (i in 0 until 4) {
            val chunk = input.substring((i * chunkSize).coerceAtMost(input.length), ((i + 1) * chunkSize).coerceAtMost(input.length))
            var chunkHash = 5381
            for (c in chunk) chunkHash = (chunkHash * 33) xor c.code
            extended += (chunkHash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        }
        return extended
    }

    suspend fun translateImage(
        imageBytes: ByteArray,
        targetLang: String,
        translationModel: String? = null,
    ): List<IchigoTranslation>? {
        if (imageBytes.isEmpty() || imageBytes.size > 8 * 1024 * 1024) return null
        val base64 = "data:image/jpeg;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val body = IchigoTranslateRequest(
            base64Images = listOf(base64),
            targetLangCode = targetLang.lowercase().take(5),
            fingerprint = fingerprint(),
            clientUuid = clientUuid(),
            translationModel = translationModel?.ifBlank { null },
        )
        val url = "${baseUrl()}/translate"
        val reqJson = json.encodeToString(IchigoTranslateRequest.serializer(), body)
        val request = Request.Builder()
            .url(url)
            .post(reqJson.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Client-Version", "1.0.1")
            .header("X-Client-Version", "1.0.1")
            .apply {
                val token = prefs.mangaTranslatorApiKey().get().trim()
                    .ifBlank { prefs.effectiveApiKey().trim() }
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()
        val callClient = client.newBuilder()
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        return try {
            callClient.newCall(request).execute().use { resp ->
                val txt = resp.body.string()
                when (resp.code) {
                    200 -> {
                        val parsed = json.decodeFromString(IchigoTranslateResponse.serializer(), txt)
                        parsed.images.firstOrNull()
                    }
                    429 -> {
                        xLogW("MangaTranslator 429 rate-limited: $txt")
                        throw TranslationException("MangaTranslator rate-limited (429) — try again later or log in at ichigo.moe")
                    }
                    401, 403 -> {
                        xLogW("MangaTranslator auth failed ${resp.code}: $txt")
                        throw TranslationException("MangaTranslator auth failed — check API key / log in at ichigo.moe")
                    }
                    else -> {
                        xLogW("MangaTranslator HTTP ${resp.code}: $txt")
                        throw TranslationException("MangaTranslator HTTP ${resp.code}")
                    }
                }
            }
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            xLogD("MangaTranslator call failed: ${e.message}")
            throw TranslationException("MangaTranslator failed: ${e.message}", e)
        }
    }

    suspend fun translateImageToWebP(
        imageBytes: ByteArray,
        targetLang: String,
        translationModel: String? = null,
    ): ByteArray? {
        val translations = translateImage(imageBytes, targetLang, translationModel) ?: return null
        if (translations.isEmpty()) return null
        return renderTranslationsToWebP(imageBytes, translations)
    }

    private fun renderTranslationsToWebP(
        originalBytes: ByteArray,
        translations: List<IchigoTranslation>,
    ): ByteArray? {
        return try {
            val decoded = android.graphics.BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size) ?: return null
            val bitmap = decoded.copy(android.graphics.Bitmap.Config.ARGB_8888, true) ?: decoded
            if (bitmap !== decoded) decoded.recycle()
            val canvas = android.graphics.Canvas(bitmap)
            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            for (t in translations) {
                if (t.translatedText.isBlank()) continue
                var left = t.minX.toFloat().coerceIn(0f, bitmap.width.toFloat())
                var top = t.minY.toFloat().coerceIn(0f, bitmap.height.toFloat())
                var right = t.maxX.toFloat().coerceIn(left, bitmap.width.toFloat())
                var bottom = t.maxY.toFloat().coerceIn(top, bitmap.height.toFloat())
                val boxW = right - left
                val boxH = bottom - top
                if (boxW < 8f || boxH < 8f) continue
                canvas.drawRoundRect(left, top, right, bottom, 4f, 4f, bgPaint)
                val rawText = t.translatedText.trim().replace(Regex("\\s+"), " ")
                if (rawText.isBlank()) continue
                var textSize = (boxH * 0.45f).coerceIn(10f, 36f)
                textPaint.textSize = textSize
                val maxTextW = boxW - 8f
                var fitted = rawText
                while (textPaint.measureText(fitted) > maxTextW && textSize > 10f) {
                    textSize -= 1f
                    textPaint.textSize = textSize
                }
                if (textPaint.measureText(fitted) > maxTextW) {
                    val avgCharW = textPaint.measureText("M")
                    val maxChars = (maxTextW / avgCharW.coerceAtLeast(1f)).toInt().coerceAtLeast(6)
                    fitted = fitted.take(maxChars - 1) + "…"
                }
                val fm = textPaint.fontMetrics
                val textH = fm.descent - fm.ascent
                val centerX = (left + right) / 2f
                val centerY = (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f
                val lines = splitToLines(fitted, textPaint, maxTextW)
                val lineH = textH * 1.1f
                val totalH = lineH * lines.size
                var startY = centerY - totalH / 2f + textH / 2f
                if (lines.size == 1) startY = centerY - (fm.ascent + fm.descent) / 2f
                for (line in lines) {
                    canvas.drawText(line, centerX, startY.coerceIn(top + textH, bottom - 4f), textPaint)
                    startY += lineH
                    if (startY > bottom - 2f) break
                }
            }
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            xLogW("MangaTranslator render failed: ${e.message}")
            null
        }
    }

    private fun splitToLines(text: String, paint: android.graphics.Paint, maxW: Float): List<String> {
        if (paint.measureText(text) <= maxW) return listOf(text)
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in words) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(candidate) <= maxW) {
                if (cur.isNotEmpty()) cur.append(" ")
                cur.append(w)
            } else {
                if (cur.isNotEmpty()) {
                    lines.add(cur.toString())
                    cur = StringBuilder(w)
                    if (paint.measureText(w) > maxW) {
                        var truncated = w
                        while (paint.measureText(truncated + "…") > maxW && truncated.length > 2) truncated = truncated.dropLast(1)
                        lines[lines.size - 1] = truncated + "…"
                        cur = StringBuilder()
                    }
                } else {
                    var truncated = w
                    while (paint.measureText(truncated + "…") > maxW && truncated.length > 2) truncated = truncated.dropLast(1)
                    lines.add(truncated + "…")
                }
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines.take(4)
    }
}
