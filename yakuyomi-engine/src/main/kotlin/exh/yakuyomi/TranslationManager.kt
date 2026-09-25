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
     * Translates a manga's metadata (title + optional description) with the active text
     * provider (on-device local LLM, or the configured cloud model). Independent of the
     * per-manga page-translation toggle: only the global MTL switch and the
     * incognito/censor gates apply. Results are cached on disk via
     * [MangaInfoTranslationStore] keyed by the full request identity. Returns null when
     * the feature is gated, no text provider is ready, or the provider returned nothing
     * usable.
     */
    suspend fun translateMangaInfo(
        mangaId: Long,
        title: String,
        description: String?,
        sourceLangHint: String = "JA",
        sourceId: Long? = null,
    ): MangaInfoTranslation? {
        if (!shouldTranslateMangaInfo()) return null
        if (mangaInfoProviderState() != MangaInfoProviderState.READY) return null
        val sourceLang = resolveMangaInfoSourceLang(sourceLangHint)
        val targetLang = prefs.targetLang().get().ifBlank { "en" }
        val identity = mangaInfoIdentity()
        val promptPolicy = prefs.promptPolicy()
        val promptFingerprint = promptPolicy.fingerprint()
        val lines = listOfNotNull(title.ifBlank { null }, description?.ifBlank { null })
        if (lines.isEmpty()) return null
        val glossary = prefs.glossaryMap()
        val translated = try {
            if (localLlm.isLocalProvider()) {
                val isEnFix = sourceLang.equals("EN", true) && targetLang.equals("EN", true)
                val prompt = buildTranslationPrompt(
                    texts = lines,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    breadcrumb = "",
                    isEnFix = isEnFix,
                    mangaContext = "",
                    glossary = glossary,
                    policy = promptPolicy,
                )
                val result = localLlm.generate(prompt) ?: return null
                // KMK --> Stable-ID alignment: strip <|n|> prefixes before field mapping.
                alignTranslationLines(parseTranslationLines(result) ?: return null, lines)
                // KMK <--
            } else {
                if (prefs.effectiveApiKey().isBlank()) return null
                YakuyomiTranslator(
                    apiKey = prefs.effectiveApiKey(),
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    breadcrumb = "",
                    provider = prefs.provider().get().lowercase(),
                    model = prefs.effectiveModel(),
                    offlineFallback = prefs.offlineFallback().get(),
                    client = client,
                    customBaseUrl = prefs.customBaseUrl().get(),
                    customHeaders = prefs.customHeaders().get(),
                    policy = promptPolicy,
                ).translate(lines)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            xLogE("translateMangaInfo failed", e)
            null
        } ?: return null
        // KMK --> Strict per-field mapping: missing/blank output for any requested field is
        // an error (never padded with source text, never falling back to the original).
        val (newTitle, newDescription) = mapMangaInfoTranslation(title, description, translated) ?: return null
        // KMK --> Stamp the full request identity so stale entries never display as current.
        val result = MangaInfoTranslation(
            title = newTitle,
            description = newDescription,
            sourceFingerprint = buildMangaInfoFingerprint(sourceId, title, description, sourceLang),
            targetLanguage = targetLang,
            provider = identity.provider,
            model = identity.model,
            promptFingerprint = promptFingerprint,
        )
        // KMK <--
        infoStore.put(mangaId, result)
        return result
    }

    // KMK --> Independent metadata-translation eligibility (spec 2026-09-23): the global
    // MTL switch plus incognito/censor gates apply, but the per-manga page-translation
    // toggle is deliberately not consulted. Page-translation gates are untouched.
    suspend fun shouldTranslateMangaInfo(): Boolean = shouldTranslate()

    /**
     * Whether a text provider is ready for metadata translation. MangaTranslator is an
     * image service and is reported as unsupported; local LLM and cloud text providers
     * follow the same readiness rules as the page pipeline.
     */
    fun mangaInfoProviderState(): MangaInfoProviderState {
        if (prefs.mangaTranslatorEnabled().get() || prefs.provider().get().equals("mangatranslator", ignoreCase = true)) {
            return MangaInfoProviderState.MANGA_TRANSLATOR_UNSUPPORTED
        }
        if (localLlm.isLocalProvider()) return MangaInfoProviderState.READY
        return if (prefs.effectiveApiKey().isNotBlank()) {
            MangaInfoProviderState.READY
        } else {
            MangaInfoProviderState.NOT_CONFIGURED
        }
    }

    /** Provider/model identity stamped on metadata cache entries. Mirrors the page pipeline. */
    fun mangaInfoIdentity(): MangaInfoIdentity {
        return if (localLlm.isLocalProvider()) {
            MangaInfoIdentity(provider = "local", model = "local:${localLlm.resolveModel()?.id ?: "auto"}")
        } else {
            MangaInfoIdentity(provider = prefs.provider().get().lowercase(), model = prefs.effectiveModel())
        }
    }

    /**
     * Validated metadata cache read: returns the entry only when its fingerprint,
     * target language, provider, and model all match the current request.
     */
    fun getValidCachedMangaInfo(
        mangaId: Long,
        sourceId: Long?,
        title: String,
        description: String?,
        sourceLangHint: String = "JA",
    ): MangaInfoTranslation? {
        val sourceLang = resolveMangaInfoSourceLang(sourceLangHint)
        val targetLang = prefs.targetLang().get().ifBlank { "en" }
        val identity = mangaInfoIdentity()
        return infoStore.getValidated(
            mangaId,
            buildMangaInfoFingerprint(sourceId, title, description, sourceLang),
            targetLang,
            identity.provider,
            identity.model,
            prefs.promptFingerprint(),
        )
    }
    // KMK <--

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
        val promptFingerprint = prefs.promptFingerprint()
        if (prefs.saveTranslatedPages().get() || prefs.mangaTranslatorCachePermanent().get()) {
            pageStore.loadIfExists(mangaId, chapterId, pageIndex, promptFingerprint)?.let { bytes ->
                if (bytes.isNotEmpty()) return@withContext bytes
            }
        }
        if (prefs.cacheEnabled().get()) {
            val pageHash = cache.pageHash(imageBytes)
            cache.getIfExists(pageHash, targetLang, model, promptFingerprint)?.let { f ->
                try {
                    val bytes = f.readBytes()
                    if (bytes.isNotEmpty()) return@withContext bytes
                } catch (_: Exception) {}
            }
        }
        null
    }

    // KMK --> Title for the per-manga cache sidecar: first line of the manga
    // context grounding ("title\ndesc\ntags"), which the screen reads back even
    // after the manga leaves the library.
    private suspend fun mangaTitleFor(mangaId: Long): String? =
        mangaContextProvider(mangaId)?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
    // KMK <--

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
        val promptFingerprint = prefs.promptFingerprint()
        val cacheEnabled = prefs.cacheEnabled().get()

        if (prefs.saveTranslatedPages().get() || prefs.mangaTranslatorCachePermanent().get()) {
            pageStore.loadIfExists(mangaId, chapterId, pageIndex, promptFingerprint)?.let { bytes ->
                if (bytes.isNotEmpty()) {
                    status.pageCached(mangaId, chapterId, pageIndex)
                    return@withContext bytes
                }
            }
        }

        val pageHash = cache.pageHash(imageBytes)
        if (cacheEnabled) {
            cache.getIfExists(pageHash, targetLang, model, promptFingerprint)?.let { f ->
                try {
                    val bytes = f.readBytes()
                    if (bytes.isNotEmpty()) {
                        if ((prefs.saveTranslatedPages().get() && prefs.autoSaveWhileReading().get()) || prefs.mangaTranslatorCachePermanent().get()) {
                            pageStore.save(
                                mangaId,
                                chapterId,
                                pageIndex,
                                bytes,
                                mangaTitleFor(mangaId),
                                promptFingerprint,
                            )
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
        val promptPolicy = prefs.promptPolicy()
        val promptFingerprint = promptPolicy.fingerprint()
        val glossary = promptPolicy.glossary
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
        // KMK --> Reuse the already-fetched grounding for the cache sidecar (no extra lookup).
        val mangaTitle = mangaContext.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
        // KMK <--

        status.pageTranslating(mangaId, chapterId, pageIndex)

        val useMangaTranslator = prefs.mangaTranslatorEnabled().get() || prefs.provider().get().equals("mangatranslator", true)
        if (useMangaTranslator) {
            try {
                val webp = mangaTranslator.translateImageToWebP(imageBytes, targetLang, prefs.effectiveModel().takeIf { it.isNotBlank() })
                if (webp != null && webp.isNotEmpty()) {
                    if (cacheEnabled) {
                        try {
                            cache.put(pageHash, targetLang, model, webp, promptFingerprint)
                        } catch (_: Exception) {}
                    }
                    try {
                        if (prefs.mangaTranslatorCachePermanent().get() || prefs.saveTranslatedPages().get()) {
                            pageStore.save(mangaId, chapterId, pageIndex, webp, mangaTitle, promptFingerprint)
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
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                status.pageError(mangaId, chapterId, pageIndex, "Invalid image dimensions ${bounds.outWidth}x${bounds.outHeight}")
                return null
            }
            val pixelCount = bounds.outWidth.toLong() * bounds.outHeight.toLong()
            // KMK -->
            val tallFullRes = prefs.longPageSlicingEnabled().get() &&
                LongPageSlicer.shouldSlice(bounds.outWidth, bounds.outHeight) &&
                pixelCount in 1..36_000_000L
            // KMK <--
            val widthOk = bounds.outWidth in 1..10000
            val heightOk = bounds.outHeight in 1..if (tallFullRes) 30000 else 10000
            if (!widthOk || !heightOk) {
                status.pageError(mangaId, chapterId, pageIndex, "Invalid image dimensions ${bounds.outWidth}x${bounds.outHeight}")
                return null
            }
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            val sampleSize = when {
                tallFullRes -> 1
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
            // Built as a factory over the slice bitmap: the engine may split a tall page into
            // overlapping slices, and each provider must see its own slice (vision context and
            // JPEG bytes are generated per slice, never from the whole page).
            val useGeminiNano = prefs.geminiNanoEnabled().get() && geminiNano.isAvailable()
            val translatorFactory: suspend (Bitmap) -> li.joye.yakuyomi.engine.Translator = { sliceBitmap ->
                when {
                    useGeminiNano -> {
                        object : li.joye.yakuyomi.engine.Translator {
                            override suspend fun translate(queries: List<String>): List<String> {
                                val result = geminiNano.translate(queries, sliceBitmap, sourceLangHint)
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
                            pageBitmap = sliceBitmap,
                            offlineFallback = prefs.offlineFallback().get(),
                            glossary = glossary,
                            policy = promptPolicy,
                        )
                    }
                    else -> {
                        val jpegBytes = runCatching {
                            if (sliceBitmap.width * sliceBitmap.height > 2_000_000) {
                                val scale = kotlin.math.sqrt(2_000_000.0 / (sliceBitmap.width * sliceBitmap.height)).toFloat()
                                val nw = (sliceBitmap.width * scale).toInt().coerceAtLeast(512)
                                val nh = (sliceBitmap.height * scale).toInt().coerceAtLeast(512)
                                val scaled = android.graphics.Bitmap.createScaledBitmap(sliceBitmap, nw, nh, true)
                                val out = java.io.ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                                scaled.recycle()
                                out.toByteArray()
                            } else {
                                val out = java.io.ByteArrayOutputStream()
                                sliceBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
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
                            policy = promptPolicy,
                        )
                    }
                }
            }
            // KMK <--

            val currentBitmap = checkNotNull(bitmap)
            // KMK -->
            val pageTimeoutMs = if (prefs.longPageSlicingEnabled().get() &&
                LongPageSlicer.shouldSlice(currentBitmap.width, currentBitmap.height)
            ) {
                600_000L
            } else {
                90_000L
            }
            // KMK <--
            val result = try {
                kotlinx.coroutines.withTimeout(pageTimeoutMs) { engine.translatePage(currentBitmap, translatorFactory, targetLang) }
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
                        cache.put(pageHash, targetLang, model, webp, promptFingerprint)
                    }
                    if (prefs.saveTranslatedPages().get() && prefs.autoSaveWhileReading().get()) {
                        pageStore.save(mangaId, chapterId, pageIndex, webp, mangaTitle, promptFingerprint)
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
