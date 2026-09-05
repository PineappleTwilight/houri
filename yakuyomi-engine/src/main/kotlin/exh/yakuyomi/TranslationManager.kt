package exh.yakuyomi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.log.xLogD
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.PageResult
import okhttp3.OkHttpClient
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

@SingleIn(AppScope::class)
@Inject
class TranslationManager(
    private val prefs: TranslationPreferences,
    private val cache: TranslationCache,
    private val engine: YakuyomiEngine,
    private val notes: BreadcrumbNotes,
    private val client: OkHttpClient,
    private val perMangaStore: TranslateMangaStore,
    private val preferenceStore: PreferenceStore,
    private val status: TranslationStatus,
    private val pageStore: TranslatedPageStore,
    // KMK -->
    private val geminiNano: GeminiNanoTranslator,
    private val localLlm: LocalLlmManager,
    private val infoStore: MangaInfoTranslationStore,
    private val mangaContextProvider: suspend (Long) -> String? = { null },
    // KMK <--
) {
    // KMK -->
    // On-the-fly translation is submitted per page from multiple coroutines (decode workers,
    // retries, download worker). Without ordering, page 5 can be sent to the LLM and swap in
    // before page 2, scrambling the breadcrumb context and the reading experience. Each chapter
    // gets a skip-list keyed by page index; a single worker drains it strictly in page order.
    private data class PendingTranslation(
        val imageBytes: ByteArray,
        val sourceLangHint: String,
        val deferred: CompletableDeferred<ByteArray?>,
    )

    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Pair<Long, Long>, ConcurrentSkipListMap<Int, PendingTranslation>>()
    private val workers = ConcurrentHashMap<Pair<Long, Long>, kotlinx.coroutines.Job>()

    private fun ensureTranslationWorker(key: Pair<Long, Long>) {
        workers.computeIfAbsent(key) {
            workerScope.launch {
                try {
                    while (true) {
                        val queue = pending[key] ?: break
                        val first = queue.firstEntry() ?: break
                        val pageIndex = first.key
                        val job = first.value
                        val result = runCatching {
                            translatePageInternal(
                                mangaId = key.first,
                                chapterId = key.second,
                                pageIndex = pageIndex,
                                imageBytes = job.imageBytes,
                                sourceLangHint = job.sourceLangHint,
                            )
                        }.getOrNull()
                        job.deferred.complete(result)
                        queue.remove(pageIndex)
                    }
                } finally {
                    workers.remove(key)
                    // Anything enqueued between the last drain and this removal still needs a worker.
                    if (pending[key]?.isNotEmpty() == true) ensureTranslationWorker(key)
                }
            }
        }
    }
    // KMK <--

    fun isEnabled(): Boolean = prefs.enabled().get()

    fun isGated(): Boolean {
        val incognito = preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false).get()
        val censor = preferenceStore.getBoolean("pref_censor_lewd_manga", false).get()
        return incognito || censor
    }

    suspend fun shouldTranslate(): Boolean {
        if (!isEnabled()) return false
        if (isGated()) {
            xLogD("Translation gated: incognito/censor")
            return false
        }
        return true
    }

    suspend fun shouldTranslateForManga(mangaId: Long): Boolean {
        if (!shouldTranslate()) return false
        return perMangaStore.isEnabled(mangaId)
    }

    fun isPerMangaEnabled(mangaId: Long): Boolean = perMangaStore.isEnabled(mangaId)

    fun setPerMangaEnabled(mangaId: Long, enabled: Boolean) = perMangaStore.setEnabled(mangaId, enabled)

    /**
     * Translates a manga's metadata (title + optional description) with the active provider
     * (on-device local LLM, or the configured cloud model). Results are cached on disk via
     * [MangaInfoTranslationStore]. Returns null when the feature is gated, the manga is not
     * opted in, or the provider returned nothing usable.
     */
    suspend fun translateMangaInfo(
        mangaId: Long,
        title: String,
        description: String?,
        sourceLangHint: String = "JA",
    ): MangaInfoTranslation? {
        if (!shouldTranslateForManga(mangaId)) return null
        val targetLang = prefs.targetLang().get().ifBlank { "en" }
        val lines = listOfNotNull(title.ifBlank { null }, description?.ifBlank { null })
        if (lines.isEmpty()) return null
        val translated = try {
            if (localLlm.isLocalProvider()) {
                val isEnFix = sourceLangHint.equals("EN", true) && targetLang.equals("EN", true)
                val prompt = if (isEnFix) {
                    "Fix grammar, preserve names, output only EN. Manga title and description:\n" +
                        lines.joinToString("\n") { "- $it" }
                } else {
                    "Translate the manga title and description to $targetLang. Preserve names, output only $targetLang:\n" +
                        lines.joinToString("\n") { "- $it" }
                }
                val result = localLlm.generate(prompt) ?: return null
                parseTranslationLines(result) ?: return null
            } else {
                if (prefs.effectiveApiKey().isBlank()) return null
                YakuyomiTranslator(
                    apiKey = prefs.effectiveApiKey(),
                    sourceLang = sourceLangHint,
                    targetLang = targetLang,
                    breadcrumb = "",
                    provider = prefs.provider().get().lowercase(),
                    model = prefs.effectiveModel(),
                    offlineFallback = prefs.offlineFallback().get(),
                    client = client,
                    customBaseUrl = prefs.customBaseUrl().get(),
                    customHeaders = prefs.customHeaders().get(),
                ).translate(lines)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            xLogE("translateMangaInfo failed", e)
            null
        } ?: return null
        val aligned = alignTranslationLines(translated, lines)
        val newTitle = aligned.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() } ?: title
        val newDescription = aligned.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val result = MangaInfoTranslation(title = newTitle, description = newDescription)
        infoStore.put(mangaId, result)
        return result
    }

    /** Declares the page count up front so chapter-list progress is accurate while translating. */
    fun setChapterTotalPages(mangaId: Long, chapterId: Long, totalPages: Int) =
        status.setTotalPages(mangaId, chapterId, totalPages)

    /**
     * Maps raw pipeline/LLM failure strings to a short, actionable message a normal user can
     * act on (download models, fix the API key, pick a different model, etc.).
     */
    fun friendlyError(raw: String?): String {
        if (raw.isNullOrBlank()) return "Translation failed (unknown reason)"
        val lower = raw.lowercase()
        return when {
            "not enough memory" in lower || ("requires at least 3gb" in lower) ->
                "This device doesn't have enough RAM for AI translation (needs 3GB+). Translation is disabled on this device."
            "not downloaded" in lower || "is the model downloaded" in lower ->
                "On-device model not downloaded — download it in Settings → Translation (Local)"
            "not bundled" in lower || ("runtime" in lower && "mlc" in lower) ->
                "The on-device LLM runtime isn't bundled in this build — enable a cloud provider or install a build with the local runtime"
            "models not ready" in lower || ("model" in lower && "download" in lower) ->
                "AI models not installed — download them in Settings → Translation"
            "gemini nano" in lower && "unavailable" in lower ->
                "Gemini Nano not available on this device — it fell back to the cloud provider. Check the provider/API key in Settings → Translation"
            "api key" in lower && ("not configured" in lower || "blank" in lower) ->
                "No API key set — add one in Settings → Translation"
            "429" in lower || "rate limit" in lower ->
                "Provider rate-limited you (HTTP 429) — wait and retry, or switch models"
            "401" in lower || "403" in lower || "unauthorized" in lower || "forbidden" in lower ->
                "API key rejected — check it's valid for the selected provider"
            "404" in lower || "not found" in lower ->
                "Model not found — check the model name for the selected provider"
            "400" in lower || "bad request" in lower ->
                "Provider rejected the request — wrong model or unsupported target language"
            "no usable translation" in lower || "empty response" in lower ->
                "Provider returned an empty/invalid translation — try a different model"
            "timeout" in lower || "timed out" in lower ->
                "Provider timed out — check your connection and retry"
            else -> "Translation failed: $raw"
        }
    }

    /**
     * Resolves the cache key for the LLM that would translate a page. When Gemini Nano is
     * active the key is the on-device model; otherwise the configured cloud model. Keeps
     * cache entries from mixing providers when the user toggles Gemini Nano on/off.
     */
    private suspend fun effectiveModel(): String {
        if (prefs.geminiNanoEnabled().get() && geminiNano.isAvailable()) {
            return "gemini-nano"
        }
        if (localLlm.isLocalProvider()) {
            return "local:${localLlm.resolveModel()?.id ?: "auto"}"
        }
        return prefs.effectiveModel().ifBlank { "google/gemma-2-9b-it:free" }
    }

    /**
     * Fast path for pages already translated in this session/on disk: serves the saved page or the
     * hash cache without running the detection/OCR/LLM pipeline. Returns null when nothing is stored
     * (caller should fall back to [translatePage]).
     */
    suspend fun getTranslatedBytes(
        mangaId: Long,
        chapterId: Long,
        imageBytes: ByteArray,
        pageIndex: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!prefs.enabled().get() || isGated() || !perMangaStore.isEnabled(mangaId)) return@withContext null
        val targetLang = prefs.targetLang().get().ifBlank { "en" }
        val model = effectiveModel()
        if (prefs.saveTranslatedPages().get()) {
            pageStore.loadIfExists(mangaId, chapterId, pageIndex)?.let { bytes ->
                if (bytes.isNotEmpty()) return@withContext bytes
            }
        }
        if (prefs.cacheEnabled().get()) {
            val pageHash = cache.pageHash(imageBytes)
            cache.getIfExists(pageHash, targetLang, model)?.let { f ->
                try {
                    val bytes = f.readBytes()
                    if (bytes.isNotEmpty()) return@withContext bytes
                } catch (_: Exception) {}
            }
        }
        null
    }

    suspend fun translatePage(
        mangaId: Long,
        chapterId: Long,
        imageBytes: ByteArray,
        pageIndex: Int,
        sourceLangHint: String = "JA",
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!prefs.enabled().get() || isGated() || !perMangaStore.isEnabled(mangaId)) return@withContext null
        val targetLang = prefs.targetLang().get().ifBlank { "en" }
        val model = effectiveModel()
        val cacheEnabled = prefs.cacheEnabled().get()

        // Prefer saved translated page to avoid re-translation
        if (prefs.saveTranslatedPages().get()) {
            pageStore.loadIfExists(mangaId, chapterId, pageIndex)?.let { bytes ->
                if (bytes.isNotEmpty()) {
                    status.pageCached(mangaId, chapterId, pageIndex)
                    return@withContext bytes
                }
            }
        }

        val pageHash = cache.pageHash(imageBytes)
        if (cacheEnabled) {
            cache.getIfExists(pageHash, targetLang, model)?.let { f ->
                try {
                    val bytes = f.readBytes()
                    if (bytes.isNotEmpty()) {
                        // Ensure saved copy exists for future fast load
                        if (prefs.saveTranslatedPages().get() && prefs.autoSaveWhileReading().get()) {
                            pageStore.save(mangaId, chapterId, pageIndex, bytes)
                        }
                        status.pageCached(mangaId, chapterId, pageIndex)
                        return@withContext bytes
                    }
                } catch (_: Exception) {}
            }
        }

        // KMK --> Queue the real work per chapter; the worker drains strictly in page order so
        // translations complete start-to-finish and the LLM context follows reading order.
        // KMK <--
        val key = mangaId to chapterId
        val deferred = CompletableDeferred<ByteArray?>()
        pending.computeIfAbsent(key) { ConcurrentSkipListMap() }
            .put(
                pageIndex,
                PendingTranslation(imageBytes = imageBytes, sourceLangHint = sourceLangHint, deferred = deferred),
            )?.deferred?.complete(null)
        ensureTranslationWorker(key)
        deferred.await()
    }

    /** The actual pipeline for one page - only ever called in page order by [ensureTranslationWorker]. */
    private suspend fun translatePageInternal(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        imageBytes: ByteArray,
        sourceLangHint: String,
    ): ByteArray? {
        val targetLang = prefs.targetLang().get().ifBlank { "en" }
        val model = effectiveModel()
        val cacheEnabled = prefs.cacheEnabled().get()
        val pageHash = cache.pageHash(imageBytes)
        // Manga grounding + sliding-window context. The local provider sizes the window to the
        // model's context length; cloud keeps the 800-char default.
        val localModel = if (localLlm.isLocalProvider()) localLlm.resolveModel() else null
        val breadcrumbBudget = localModel?.let { (it.contextLength * 0.2).toInt().coerceIn(400, 2400) } ?: 800
        val breadcrumb = notes.buildContextPrompt(mangaId, breadcrumbBudget)
        val mangaContext = mangaContextProvider(mangaId) ?: ""

        status.pageTranslating(mangaId, chapterId, pageIndex)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                status.pageError(mangaId, chapterId, pageIndex, "Unable to decode image bounds")
                return null
            }
            val needsSample = bounds.outWidth > 4096 || bounds.outHeight > 4096
            val sampleOpts = if (needsSample) BitmapFactory.Options().apply { inSampleSize = 2 } else null
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, sampleOpts)
            if (bitmap == null) {
                status.pageError(mangaId, chapterId, pageIndex, "Unable to decode image")
                return null
            }

            // KMK -->
            // Gemini Nano (on-device) is the priority LLM provider when the toggle is on and
            // the device has the model available; otherwise fall back to the cloud provider.
            // The page bitmap is passed along for visual context (vision augments the OCR'd
            // text lines — it never replaces them).
            val useGeminiNano = prefs.geminiNanoEnabled().get() && geminiNano.isAvailable()
            val translator: li.joye.yakuyomi.engine.Translator = when {
                useGeminiNano -> {
                    object : li.joye.yakuyomi.engine.Translator {
                        override suspend fun translate(queries: List<String>): List<String> {
                            val result = geminiNano.translate(queries, bitmap, sourceLangHint)
                            if (result != null) return result
                            if (prefs.offlineFallback().get()) return queries.map { it.trim() }
                            throw TranslationException("Gemini Nano unavailable on this device — enable a cloud provider in Settings → Translation")
                        }
                    }
                }
                localLlm.isLocalProvider() -> {
                    LocalLlmTranslator(
                        manager = localLlm,
                        sourceLang = sourceLangHint,
                        targetLang = targetLang,
                        breadcrumb = breadcrumb,
                        mangaContext = mangaContext,
                        pageBitmap = bitmap,
                        offlineFallback = prefs.offlineFallback().get(),
                    )
                }
                else -> {
                    val jpegBytes = runCatching {
                        val out = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        out.toByteArray()
                    }.getOrNull()
                    YakuyomiTranslator(
                        apiKey = prefs.effectiveApiKey(),
                        sourceLang = sourceLangHint,
                        targetLang = targetLang,
                        breadcrumb = breadcrumb,
                        mangaContext = mangaContext,
                        provider = prefs.provider().get().lowercase(),
                        model = model,
                        offlineFallback = prefs.offlineFallback().get(),
                        client = client,
                        customBaseUrl = prefs.customBaseUrl().get(),
                        customHeaders = prefs.customHeaders().get(),
                        pageImageBytes = jpegBytes,
                    )
                }
            }
            // KMK <--

            when (val result = engine.translatePage(bitmap, translator, targetLang)) {
                is PageResult.Translated -> {
                    val webp = engine.bitmapToWebP(result.page, quality = 85)
                    if (cacheEnabled) {
                        cache.put(pageHash, targetLang, model, webp)
                    }
                    if (prefs.saveTranslatedPages().get() && prefs.autoSaveWhileReading().get()) {
                        pageStore.save(mangaId, chapterId, pageIndex, webp)
                    }
                    val translatedTexts = result.analysis?.regions?.map { it.translatedText } ?: emptyList()
                    try {
                        notes.appendFromTranslation(mangaId, chapterId, translatedTexts)
                    } catch (_: Exception) {}
                    status.pageDone(mangaId, chapterId, pageIndex)
                    webp
                }
                is PageResult.Skipped -> {
                    status.pageSkipped(mangaId, chapterId, pageIndex)
                    null
                }
                is PageResult.Failed -> {
                    status.pageError(mangaId, chapterId, pageIndex, friendlyError(result.reason))
                    null
                }
            }
        } catch (e: Exception) {
            xLogE("translatePage failed", e)
            status.pageError(mangaId, chapterId, pageIndex, friendlyError(e.message ?: "Unknown translation error"))
            null
        }
    }

    fun bitmapToWebP(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
        return out.toByteArray()
    }
}
