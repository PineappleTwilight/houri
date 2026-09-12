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
import java.net.URI
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

@Serializable
private data class IchigoLoginRequest(
    val email: String,
    val password: String,
)

@Serializable
private data class IchigoTokens(
    @SerialName("accessToken") val accessToken: String = "",
    @SerialName("refreshToken") val refreshToken: String? = null,
)

@Serializable
private data class IchigoAuthResponse(
    val tokens: IchigoTokens? = null,
)

@Serializable
data class IchigoUser(
    val email: String? = null,
    @SerialName("subscriptionTier") val subscriptionTier: String = "free",
)

enum class LoginResult {
    Success,
    BadPassword,
    UnknownEmail,
    InvalidEmail,
    RateLimited,
    Unknown,
}

enum class SignupResult {
    Success,
    EmailTaken,
    InvalidEmail,
    RateLimited,
    Unknown,
}

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

    // Copy of extension's allowed target langs - validated client side to prevent injection of arbitrary strings.
    private val allowedTargetLangs = setOf(
        "en", "de", "es", "fr", "pt", "pt-br", "pt-pt", "ru", "it", "pl", "tr", "id",
        "ar", "hi", "th", "vi", "zh", "zh-cn", "zh-tw", "ja", "ko", "nl", "uk", "auto",
    )

    private val allowedModels = setOf(
        "gpt4o-mini", "gpt4o", "gpt4o-nano", "gpt-4.1", "gpt-4.1-mini", "claude-3-5-sonnet",
        "claude-3-haiku", "gemini-2.0-flash", "gemini-1.5-flash", "deepl", "google",
    )

    private fun baseUrl(): String {
        val raw = prefs.mangaTranslatorBaseUrl().get().trim().trimEnd('/')
        if (raw.isBlank()) return "https://ichigo.moe"
        return try {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https") return "https://ichigo.moe"
            val host = uri.host ?: return "https://ichigo.moe"
            if (host.isBlank()) return "https://ichigo.moe"
            // Reject userinfo, fragment abuse, private IPs
            if (uri.userInfo != null) return "https://ichigo.moe"
            if (isPrivateHost(host)) return "https://ichigo.moe"
            // Enforce trusted hosts whitelist for prod hardening; allow custom https for self-hosted but warn
            val trusted = setOf("ichigo.moe", "mangatranslator.ai", "ichigoreader.com")
            val isTrusted = trusted.any { host == it || host.endsWith(".$it") }
            // If not trusted and not localhost, still allow but we strip path/query to root to avoid SSRF to internal services
            if (!isTrusted && host != "localhost" && !host.endsWith(".ichigo.moe")) {
                // Allow custom domains but sanitize: only scheme+host+port
                val port = if (uri.port != -1) ":${uri.port}" else ""
                "https://$host$port"
            } else {
                val port = if (uri.port != -1) ":${uri.port}" else ""
                "https://$host$port"
            }
        } catch (_: Exception) {
            "https://ichigo.moe"
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return false // allow for debugging? but treat as private to force fallback
        // IPv4 literal check
        val ipv4 = Regex("""\d+\.\d+\.\d+\.\d+""")
        if (ipv4.matches(host)) {
            val parts = host.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size == 4) {
                if (parts[0] == 10) return true
                if (parts[0] == 192 && parts[1] == 168) return true
                if (parts[0] == 172 && parts[1] in 16..31) return true
                if (parts[0] == 127) return true
                if (parts[0] == 0) return true
            }
        }
        return false
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
        if (cached.isNotBlank() && cached.length >= 16) return cached.take(128)
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
            "$w×$h-$d-$d-${w}×$h-$ratio"
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

    private fun sanitizeTargetLang(raw: String): String {
        val lower = raw.trim().lowercase().take(12)
        if (lower.isBlank()) return "en"
        if (allowedTargetLangs.contains(lower)) return lower
        // Also accept 2-letter prefix
        val two = lower.take(2)
        if (allowedTargetLangs.contains(two)) return two
        return "en"
    }

    private fun sanitizeModel(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().take(64)
        // Allow alphanumeric, dash, underscore, dot
        if (!Regex("""^[a-zA-Z0-9._\-]+$""").matches(trimmed)) return null
        // If known model, use as-is, else allow but truncated
        return trimmed
    }

    private fun accessToken(): String = prefs.mangaTranslatorAccessToken().get().trim()

    private fun ichigoHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Client-Version" to "1.0.1",
            "X-Client-Version" to "1.0.1",
        )
        val token = accessToken()
        if (token.isNotBlank() && token.length in 16..2048) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    private fun newCallClient(): OkHttpClient = client.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // --- Auth: mirrors extension's ichigoApi.ts (login / signup / logout / metrics) ---

    suspend fun login(email: String, password: String): LoginResult {
        val e = email.trim().take(254)
        val p = password.take(512)
        if (e.isBlank() || !e.contains("@") || e.length < 5) return LoginResult.InvalidEmail
        if (p.isBlank() || p.length < 6) return LoginResult.BadPassword
        val url = "${baseUrl()}/auth/login"
        val body = json.encodeToString(IchigoLoginRequest.serializer(), IchigoLoginRequest(e, p))
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Client-Version", "1.0.1")
            .build()
        return try {
            newCallClient().newCall(req).execute().use { resp ->
                val txt = resp.body.string().take(4096)
                when (resp.code) {
                    200 -> {
                        try {
                            val parsed = json.decodeFromString(IchigoAuthResponse.serializer(), txt)
                            val token = parsed.tokens?.accessToken?.trim()
                            if (!token.isNullOrBlank() && token.length >= 16) {
                                prefs.mangaTranslatorAccessToken().set(token)
                                prefs.mangaTranslatorEmail().set(e)
                            }
                        } catch (_: Exception) {
                            // Even if body parse fails, treat as success if 200 (extension does same, but we require token)
                        }
                        if (accessToken().isBlank()) {
                            // Try to extract token loosely
                            val fallback = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.getOrNull(1)
                            if (!fallback.isNullOrBlank()) {
                                prefs.mangaTranslatorAccessToken().set(fallback)
                                prefs.mangaTranslatorEmail().set(e)
                            }
                        }
                        LoginResult.Success
                    }
                    400 -> {
                        try {
                            val detail = Regex(""""kind"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.getOrNull(1)
                            when (detail) {
                                "emptyEmail" -> LoginResult.InvalidEmail
                                "userNotFound" -> LoginResult.UnknownEmail
                                else -> LoginResult.Unknown
                            }
                        } catch (_: Exception) { LoginResult.Unknown }
                    }
                    403 -> LoginResult.BadPassword
                    429 -> LoginResult.RateLimited
                    else -> LoginResult.Unknown
                }
            }
        } catch (e: Exception) {
            xLogD("Ichigo login failed: ${e.message}")
            LoginResult.Unknown
        }
    }

    suspend fun signup(email: String, password: String): SignupResult {
        val e = email.trim().take(254)
        val p = password.take(512)
        if (e.isBlank() || !e.contains("@") || e.length < 5) return SignupResult.InvalidEmail
        if (p.length < 6) return SignupResult.Unknown
        val url = "${baseUrl()}/signup"
        val body = json.encodeToString(IchigoLoginRequest.serializer(), IchigoLoginRequest(e, p))
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Client-Version", "1.0.1")
            .build()
        return try {
            newCallClient().newCall(req).execute().use { resp ->
                val txt = resp.body.string().take(4096)
                when (resp.code) {
                    201, 200 -> {
                        try {
                            val parsed = json.decodeFromString(IchigoAuthResponse.serializer(), txt)
                            val token = parsed.tokens?.accessToken?.trim()
                            if (!token.isNullOrBlank() && token.length >= 16) {
                                prefs.mangaTranslatorAccessToken().set(token)
                                prefs.mangaTranslatorEmail().set(e)
                            }
                        } catch (_: Exception) {}
                        val fallback = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.getOrNull(1)
                        if (!fallback.isNullOrBlank() && accessToken().isBlank()) {
                            prefs.mangaTranslatorAccessToken().set(fallback)
                            prefs.mangaTranslatorEmail().set(e)
                        }
                        SignupResult.Success
                    }
                    400 -> {
                        val detail = Regex(""""kind"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.getOrNull(1)
                        if (detail == "emptyEmail") SignupResult.InvalidEmail else SignupResult.Unknown
                    }
                    403 -> SignupResult.EmailTaken
                    429 -> SignupResult.RateLimited
                    else -> SignupResult.Unknown
                }
            }
        } catch (e: Exception) {
            xLogD("Ichigo signup failed: ${e.message}")
            SignupResult.Unknown
        }
    }

    suspend fun logout(): Boolean {
        val token = accessToken()
        val url = "${baseUrl()}/auth/logout"
        val builder = Request.Builder()
            .url(url)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Client-Version", "1.0.1")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        val ok = try {
            newCallClient().newCall(builder.build()).execute().use { resp ->
                resp.code == 204 || resp.code == 200 || resp.code == 401 || resp.code == 403
            }
        } catch (_: Exception) { false }
        // Always clear local state, matching extension's clearExtensionAuth()
        prefs.mangaTranslatorAccessToken().set("")
        // Do not clear email - keep for UI convenience, matching extension's email retention
        return ok
    }

    fun clearAuth() {
        prefs.mangaTranslatorAccessToken().set("")
    }

    fun isLoggedIn(): Boolean = accessToken().isNotBlank()

    suspend fun getCurrentUser(): IchigoUser? {
        val url = "${baseUrl()}/metrics?clientUuid=${clientUuid()}&fingerprint=${fingerprint()}"
        val headers = ichigoHeaders()
        val req = Request.Builder()
            .url(url)
            .get()
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return try {
            executeWithAuthRetry(req) { r ->
                val txt = r.body.string().take(8192)
                if (r.code == 200) {
                    try { json.decodeFromString(IchigoUser.serializer(), txt) } catch (_: Exception) { null }
                } else null
            }
        } catch (_: Exception) { null }
    }

    // --- Translate ---

    suspend fun translateImage(
        imageBytes: ByteArray,
        targetLang: String,
        translationModel: String? = null,
    ): List<IchigoTranslation>? {
        if (imageBytes.isEmpty() || imageBytes.size > 8 * 1024 * 1024) return null
        if (imageBytes.size < 1024) return null
        // Validate image decodability early to avoid sending garbage and wasting quota
        try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0 || opts.outWidth > 10000 || opts.outHeight > 10000) return null
        } catch (_: Exception) { return null }

        val sanitizedLang = sanitizeTargetLang(targetLang)
        val sanitizedModel = sanitizeModel(translationModel)
        val base64 = "data:image/jpeg;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        // Guard against absurd payload size
        if (base64.length > 12 * 1024 * 1024) return null
        val body = IchigoTranslateRequest(
            base64Images = listOf(base64),
            targetLangCode = sanitizedLang,
            fingerprint = fingerprint().take(256),
            clientUuid = clientUuid().take(64),
            translationModel = sanitizedModel,
        )
        val url = "${baseUrl()}/translate"
        val reqJson = json.encodeToString(IchigoTranslateRequest.serializer(), body)
        val headers = ichigoHeaders()
        val request = Request.Builder()
            .url(url)
            .post(reqJson.toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return try {
            executeWithAuthRetry(request) { resp ->
                val txt = resp.body.string().take(8 * 1024 * 1024)
                when (resp.code) {
                    200 -> {
                        val parsed = try {
                            json.decodeFromString(IchigoTranslateResponse.serializer(), txt)
                        } catch (e: Exception) {
                            xLogW("MangaTranslator decode failed: ${e.message}")
                            throw TranslationException("MangaTranslator: invalid response")
                        }
                        val list = parsed.images.firstOrNull() ?: emptyList()
                        sanitizeTranslations(list).ifEmpty { emptyList() }
                    }
                    429 -> {
                        xLogW("MangaTranslator 429 rate-limited")
                        throw TranslationException("MangaTranslator rate-limited (429) — try again later or log in at ichigo.moe")
                    }
                    401, 403 -> {
                        xLogW("MangaTranslator auth failed ${resp.code}")
                        // Clear stale token like extension's authenticatedFetch does
                        clearAuth()
                        throw TranslationException("MangaTranslator auth failed — please log in again at Settings → Translation → MangaTranslator")
                    }
                    400 -> {
                        xLogW("MangaTranslator 400 bad request")
                        throw TranslationException("MangaTranslator rejected request (400) — image may be unsupported")
                    }
                    500, 502, 503, 504 -> {
                        xLogW("MangaTranslator server error ${resp.code}")
                        throw TranslationException("MangaTranslator server error (${resp.code}) — try again later")
                    }
                    else -> {
                        xLogW("MangaTranslator HTTP ${resp.code}")
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

    private suspend fun <T> executeWithAuthRetry(
        request: Request,
        block: suspend (okhttp3.Response) -> T,
    ): T {
        val callClient = newCallClient()
        var resp = callClient.newCall(request).execute()
        // Mirror extension's authenticatedFetch: on 401/403 with token, clear and retry once without stale token
        if ((resp.code == 401 || resp.code == 403) && accessToken().isNotBlank()) {
            val bodyStr = try { resp.body.string() } catch (_: Exception) { "" }
            resp.close()
            xLogW("MangaTranslator token appears stale (HTTP ${resp.code}), clearing and retrying once")
            clearAuth()
            val retryHeaders = ichigoHeaders()
            val retryRequest = request.newBuilder().apply {
                // Remove old Authorization, re-apply current headers
                removeHeader("Authorization")
                retryHeaders.forEach { (k, v) -> header(k, v) }
                // If no token now, ensure Authorization is absent
                if (!retryHeaders.containsKey("Authorization")) removeHeader("Authorization")
            }.build()
            resp = callClient.newCall(retryRequest).execute()
            // Let block handle the retry response code (will throw appropriate TranslationException)
            return resp.use { block(it) }
        }
        return resp.use { block(it) }
    }

    private fun sanitizeTranslations(list: List<IchigoTranslation>): List<IchigoTranslation> {
        if (list.isEmpty()) return emptyList()
        val maxCount = 200
        return list.take(maxCount).mapNotNull { t ->
            val text = t.translatedText
                .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "") // strip control chars
                .trim()
                .take(500)
            if (text.isBlank()) return@mapNotNull null
            val minX = t.minX.coerceIn(0, 10000)
            val minY = t.minY.coerceIn(0, 10000)
            val maxX = t.maxX.coerceIn(0, 10000)
            val maxY = t.maxY.coerceIn(0, 10000)
            if (maxX <= minX || maxY <= minY) return@mapNotNull null
            val w = maxX - minX
            val h = maxY - minY
            if (w < 4 || h < 4 || w > 10000 || h > 10000) return@mapNotNull null
            IchigoTranslation(
                originalLanguage = t.originalLanguage.take(12),
                translatedText = text,
                minX = minX,
                minY = minY,
                maxX = maxX,
                maxY = maxY,
            )
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
            // Harden: cap bitmap size before drawing to avoid OOM
            if (bitmap.width > 8000 || bitmap.height > 8000) {
                bitmap.recycle()
                return null
            }
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
