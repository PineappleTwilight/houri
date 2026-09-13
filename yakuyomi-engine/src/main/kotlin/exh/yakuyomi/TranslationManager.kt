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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.PageResult
import mihon.core.concurrency.AppDispatchersHolder
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
    private val mangaTranslator: MangaTranslatorService,
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

    private val workerScope = CoroutineScope(SupervisorJob() + AppDispatchersHolder.get().io)
    private val pending = ConcurrentHashMap<Pair<Long, Long>, ConcurrentSkipListMap<Int, PendingTranslation>>()
    private val workers = ConcurrentHashMap<Pair<Long, Long>, kotlinx.coroutines.Job>()

    private companion object {
        const val MAX_PENDING_PER_CHAPTER = 64
        const val TRANSLATE_TIMEOUT_MS = 120_000L
    }

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
                        }.getOrElse { e ->
                            xLogE("translate worker failed page $pageIndex", e)
                            null
                        }
                        if (!job.deferred.isCompleted) job.deferred.complete(result)
                        queue.remove(pageIndex)
                        if (queue.isEmpty()) {
                            pending.remove(key)
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    xLogE("translation worker crashed", e)
                    pending[key]?.values?.forEach { p ->
                        if (!p.deferred.isCompleted) p.deferred.complete(null)
                    }
                    pending.remove(key)
                } finally {
                    workers.remove(key)
                    if (pending[key]?.isNotEmpty() == true) ensureTranslationWorker(key)
                }
            }
        }
    }
    // KMK <--

    fun isEnabled(): Boolean = prefs.enabled().get()

    private val incognitoPref by lazy { preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false) }
    private val censorPref by lazy { preferenceStore.getBoolean("pref_censor_lewd_manga", false) }

    fun isGated(): Boolean = incognitoPref.get() || censorPref.get()

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

    fun cancelChapter(mangaId: Long, chapterId: Long) {
        val key = mangaId to chapterId
        pending[key]?.values?.forEach { runCatching { it.deferred.cancel() } }
        pending.remove(key)
        workers[key]?.cancel()
        workers.remove(key)
        status.resetChapter(mangaId, chapterId)
    }

    fun pauseChapter(mangaId: Long, chapterId: Long) {
        val key = mangaId to chapterId
        workers[key]?.cancel()
        workers.remove(key)
    }

    fun resumeChapter(mangaId: Long, chapterId: Long) {
        val key = mangaId to chapterId
        if (pending[key]?.isNotEmpty() == true) ensureTranslationWorker(key)
    }

    fun retryChapter(mangaId: Long, chapterId: Long) {
        val st = status.chapterStatus(mangaId, chapterId) ?: return
        val failedPages = st.pages.filter { it.value.state == TranslationStatus.PageState.ERROR }.keys
        if (failedPages.isEmpty()) return
        status.updateForRetry(mangaId, chapterId, failedPages)
    }

    fun clearAllChapters() {
        val keys = pending.keys.toList()
        keys.forEach { (m, c) -> cancelChapter(m, c) }
        status.clearAll()
    }

    fun clearAll() = clearAllChapters()

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
        val glossary = prefs.glossaryMap()
        val translated = try {
            if (localLlm.isLocalProvider()) {
                val isEnFix = sourceLangHint.equals("EN", true) && targetLang.equals("EN", true)
                val prompt = buildTranslationPrompt(lines, sourceLangHint, targetLang, "", isEnFix, "", glossary)
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

    fun friendlyError(raw: String?): String = TranslationErrorMapper.toUserMessage(raw)

    /**
     * Resolves the cache key for the LLM that would translate a page. When Gemini Nano is
     * active the key is the on-device model; otherwise the configured cloud model. Keeps
     * cache entries from mixing providers when the user toggles Gemini Nano on/off.
     */
    private suspend fun effectiveModel(): String {
        if (prefs.mangaTranslatorEnabled().get() || prefs.provider().get().equals("mangatranslator", ignoreCase = true)) {
            return "mangatranslator"
        }
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
    ): ByteArray? = withContext(AppDispatchersHolder.get().io) {
        if (!prefs.enabled().get() || isGated() || !perMangaStore.isEnabled(mangaId)) return@withContext null
        val targetLang = prefs.targetLang().get().ifBlank { "en" }
        val model = effectiveModel()
        if (prefs.saveTranslatedPages().get() || prefs.mangaTranslatorCachePermanent().get()) {
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
    ): ByteArray? = withContext(AppDispatchersHolder.get().io) {
        if (!prefs.enabled().get() || isGated() || !perMangaStore.isEnabled(mangaId)) return@withContext null
        val targetLang = prefs.targetLang().get().ifBlank { "en" }
        val model = effectiveModel()
        val cacheEnabled = prefs.cacheEnabled().get()

        if (prefs.saveTranslatedPages().get() || prefs.mangaTranslatorCachePermanent().get()) {
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
                        if ((prefs.saveTranslatedPages().get() && prefs.autoSaveWhileReading().get()) || prefs.mangaTranslatorCachePermanent().get()) {
                            pageStore.save(mangaId, chapterId, pageIndex, bytes)
                        }
                        status.pageCached(mangaId, chapterId, pageIndex)
                        return@withContext bytes
                    }
                } catch (_: Exception) {}
            }
        }

        val key = mangaId to chapterId
        val queue = pending.computeIfAbsent(key) { ConcurrentSkipListMap() }
        if (queue.size >= MAX_PENDING_PER_CHAPTER) {
            val oldest = queue.firstKey()
            queue.remove(oldest)?.deferred?.completeExceptionally(CancellationException("queue overflow"))
            xLogE("queue overflowevicted $oldest for $key size=${queue.size}")
        }
        if (imageBytes.size > 30 * 1024 * 1024) {
            status.pageError(mangaId, chapterId, pageIndex, friendlyError("Image too large"))
            return@withContext null
        }
        if (imageBytes.size < 1024) {
            status.pageError(mangaId, chapterId, pageIndex, friendlyError("Image too small/c Corrupted"))
            return@withContext null
        }
        val deferred = CompletableDeferred<ByteArray?>()
        val prev = queue.put(
            pageIndex,
            PendingTranslation(imageBytes = imageBytes, sourceLangHint = sourceLangHint, deferred = deferred),
        )
        prev?.let { if (!it.deferred.isCompleted) it.deferred.cancel() }
        ensureTranslationWorker(key)
        try {
            kotlinx.coroutines.withTimeout(TRANSLATE_TIMEOUT_MS) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            xLogE("translatePage await failed", e)
            null
        }
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
        val glossary = prefs.glossaryMap()
        val preserveSfx = prefs.preserveSfx().get()
        val localModel = if (localLlm.isLocalProvider()) localLlm.resolveModel() else null
        val breadcrumbBudget = localModel?.let { (it.contextLength * 0.25).toInt().coerceIn(500, 3000) } ?: 1000
        val rawBreadcrumb = notes.buildContextPrompt(mangaId, breadcrumbBudget)
        val breadcrumb = if (rawBreadcrumb.length > breadcrumbBudget * 1.2) {
            val trimmed = rawBreadcrumb.takeLast(breadcrumbBudget)
            val cut = trimmed.indexOf('\n')
            if (cut in 0..200) trimmed.substring(cut + 1) else trimmed
        } else {
            rawBreadcrumb
        }
        val mangaContext = mangaContextProvider(mangaId) ?: ""

        status.pageTranslating(mangaId, chapterId, pageIndex)

        val useMangaTranslator = prefs.mangaTranslatorEnabled().get() || prefs.provider().get().equals("mangatranslator", true)
        if (useMangaTranslator) {
            try {
                val webp = mangaTranslator.translateImageToWebP(imageBytes, targetLang, prefs.effectiveModel().takeIf { it.isNotBlank() })
                if (webp != null && webp.isNotEmpty()) {
                    if (cacheEnabled) {
                        try {
                            cache.put(pageHash, targetLang, model, webp)
                        } catch (_: Exception) {}
                    }
                    try {
                        if (prefs.mangaTranslatorCachePermanent().get() || prefs.saveTranslatedPages().get()) {
                            pageStore.save(mangaId, chapterId, pageIndex, webp)
                        }
                    } catch (_: Exception) {}
                    try {
                        notes.appendFromTranslation(mangaId, chapterId, listOf("[mangatranslator]"))
                    } catch (_: Exception) {}
                    status.pageDone(mangaId, chapterId, pageIndex)
                    return webp
                } else {
                    status.pageError(mangaId, chapterId, pageIndex, friendlyError("MangaTranslator returned empty result"))
                    return null
                }
            } catch (e: TranslationException) {
                xLogE("MangaTranslator failed", e)
                status.pageError(mangaId, chapterId, pageIndex, friendlyError(e.message ?: "MangaTranslator error"))
                return null
            } catch (e: Exception) {
                xLogE("MangaTranslator failed", e)
                status.pageError(mangaId, chapterId, pageIndex, friendlyError(e.message ?: "MangaTranslator error"))
                return null
            }
        }

        var bitmap: android.graphics.Bitmap? = null
        return try {
            if (imageBytes.size < 1024 || imageBytes.size > 30 * 1024 * 1024) {
                status.pageError(mangaId, chapterId, pageIndex, "Invalid image size ${imageBytes.size}")
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            } catch (e: Exception) {
                status.pageError(mangaId, chapterId, pageIndex, "Unable to decode image bounds")
                return null
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 10000 || bounds.outHeight > 10000) {
                status.pageError(mangaId, chapterId, pageIndex, "Invalid image dimensions ${bounds.outWidth}x${bounds.outHeight}")
                return null
            }
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            val sampleSize = when {
                maxDim > 6000 -> 4
                maxDim > 4096 -> 2
                else -> 1
            }
            val sampleOpts = if (sampleSize > 1) BitmapFactory.Options().apply { inSampleSize = sampleSize } else null
            val decoded = try {
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, sampleOpts)
            } catch (e: OutOfMemoryError) {
                System.gc()
                status.pageError(mangaId, chapterId, pageIndex, friendlyError("not enough memory"))
                return null
            } catch (e: Exception) {
                status.pageError(mangaId, chapterId, pageIndex, "Unable to decode image")
                return null
            }
            if (decoded == null || decoded.isRecycled) {
                status.pageError(mangaId, chapterId, pageIndex, "Unable to decode image")
                return null
            }
            bitmap = decoded

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
                        glossary = glossary,
                    )
                }
                else -> {
                    val jpegBytes = runCatching {
                        if (bitmap.width * bitmap.height > 2_000_000) {
                            val scale = kotlin.math.sqrt(2_000_000.0 / (bitmap.width * bitmap.height)).toFloat()
                            val nw = (bitmap.width * scale).toInt().coerceAtLeast(512)
                            val nh = (bitmap.height * scale).toInt().coerceAtLeast(512)
                            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, nw, nh, true)
                            val out = java.io.ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            scaled.recycle()
                            out.toByteArray()
                        } else {
                            val out = java.io.ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            out.toByteArray()
                        }
                    }.getOrNull()?.takeIf { it.size in 1..3_000_000 }
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
                        glossary = glossary,
                    )
                }
            }
            // KMK <--

            val currentBitmap = checkNotNull(bitmap)
            val result = try {
                kotlinx.coroutines.withTimeout(90_000) { engine.translatePage(currentBitmap, translator, targetLang) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                status.pageError(mangaId, chapterId, pageIndex, friendlyError("Translation timed out"))
                runCatching { currentBitmap.recycle() }
                bitmap = null
                return null
            }
            runCatching { currentBitmap.recycle() }
            bitmap = null
            when (result) {
                is PageResult.Translated -> {
                    val webp = engine.bitmapToWebP(result.page, quality = 85)
                    runCatching { result.page.recycle() }
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
            runCatching { bitmap?.recycle() }
            bitmap = null
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
