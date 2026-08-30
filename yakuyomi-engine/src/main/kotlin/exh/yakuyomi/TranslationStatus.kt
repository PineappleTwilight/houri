package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Runtime status of the MTL (Machine Translation) pipeline, keyed per chapter.
 *
 * The reader UI observes [chapters] to surface "is this chapter translating?" and
 * "how far along / did it fail?" without reaching into the viewer internals.
 * State is process-lifetime only; cache persistence is handled by [TranslationCache].
 */
@SingleIn(AppScope::class)
@Inject
class TranslationStatus {

    enum class PageState {
        /** Translation request is in-flight for this page. */
        TRANSLATING,

        /** Page was translated and baked successfully. */
        DONE,

        /** Translated image was served from cache. */
        CACHED,

        /** No actionable text detected; page passed through unchanged. */
        SKIPPED,

        /** Translation failed for this page. */
        ERROR,
    }

    data class PageStatus(
        val pageIndex: Int,
        val state: PageState,
        val error: String? = null,
    )

    data class ChapterStatus(
        val mangaId: Long,
        val chapterId: Long,
        val totalPages: Int = 0,
        val pages: Map<Int, PageStatus> = emptyMap(),
        val lastError: String? = null,
        val lastCompletedAt: Long = 0L,
        val lastUpdated: Long = 0L,
    ) {
        val isTranslating: Boolean
            get() = pages.values.any { it.state == PageState.TRANSLATING }

        /** Pages that actually received a translated/rendered image (DONE + CACHED). */
        val translatedCount: Int
            get() = pages.values.count { it.state in TRANSLATED_STATES }

        /** Pages with no actionable text (kept original; not an error, not a translation). */
        val skippedCount: Int
            get() = pages.values.count { it.state == PageState.SKIPPED }

        /** Legacy alias: everything processed that "didn't error", including skipped pages. */
        val doneCount: Int
            get() = pages.values.count { it.state in DONE_STATES }

        val errorCount: Int
            get() = pages.values.count { it.state == PageState.ERROR }

        val hasTranslatedPages: Boolean
            get() = translatedCount > 0

        /** Number of pages that still need a translated (or cached) result. */
        val pendingCount: Int
            get() = (totalPages - translatedCount).coerceAtLeast(0)

        companion object {
            private val TRANSLATED_STATES = setOf(PageState.DONE, PageState.CACHED)
            private val DONE_STATES = setOf(PageState.DONE, PageState.CACHED, PageState.SKIPPED)
        }
    }

    private val _chapters = MutableStateFlow<Map<Pair<Long, Long>, ChapterStatus>>(emptyMap())
    val chapters: StateFlow<Map<Pair<Long, Long>, ChapterStatus>> = _chapters.asStateFlow()

    fun chapterStatus(mangaId: Long, chapterId: Long): ChapterStatus? = _chapters.value[mangaId to chapterId]

    /**
     * Declares the known page count for a chapter before translation starts. Without this,
     * [ChapterStatus.totalPages] stays 0 and UI progress clamps to 0%.
     */
    fun setTotalPages(mangaId: Long, chapterId: Long, totalPages: Int) = update(mangaId, chapterId) {
        it.copy(
            totalPages = totalPages.coerceAtLeast(it.pages.size),
            lastUpdated = now(),
        )
    }

    fun pageTranslating(mangaId: Long, chapterId: Long, pageIndex: Int, totalPages: Int = 0) = update(mangaId, chapterId) {
        it.copy(
            totalPages = if (totalPages > 0) totalPages else it.totalPages,
            lastUpdated = now(),
            pages = it.pages + (pageIndex to PageStatus(pageIndex, PageState.TRANSLATING)),
        )
    }

    fun pageDone(mangaId: Long, chapterId: Long, pageIndex: Int, totalPages: Int = 0) = update(mangaId, chapterId) {
        it.copy(
            totalPages = if (totalPages > 0) totalPages else it.totalPages,
            lastError = null,
            lastCompletedAt = now(),
            lastUpdated = now(),
            pages = it.pages + (pageIndex to PageStatus(pageIndex, PageState.DONE)),
        )
    }

    fun pageCached(mangaId: Long, chapterId: Long, pageIndex: Int, totalPages: Int = 0) = update(mangaId, chapterId) {
        it.copy(
            totalPages = if (totalPages > 0) totalPages else it.totalPages,
            lastError = null,
            lastCompletedAt = now(),
            lastUpdated = now(),
            pages = it.pages + (pageIndex to PageStatus(pageIndex, PageState.CACHED)),
        )
    }

    fun pageSkipped(mangaId: Long, chapterId: Long, pageIndex: Int) = update(mangaId, chapterId) {
        it.copy(
            lastUpdated = now(),
            pages = it.pages + (pageIndex to PageStatus(pageIndex, PageState.SKIPPED)),
        )
    }

    fun pageError(mangaId: Long, chapterId: Long, pageIndex: Int, error: String, totalPages: Int = 0) = update(mangaId, chapterId) {
        it.copy(
            totalPages = if (totalPages > 0) totalPages else it.totalPages,
            lastError = error,
            lastUpdated = now(),
            pages = it.pages + (pageIndex to PageStatus(pageIndex, PageState.ERROR, error)),
        )
    }

    fun resetChapter(mangaId: Long, chapterId: Long) {
        _chapters.update { it - (mangaId to chapterId) }
    }

    fun clearAll() {
        _chapters.value = emptyMap()
    }

    private fun update(mangaId: Long, chapterId: Long, transform: (ChapterStatus) -> ChapterStatus) {
        _chapters.update { map ->
            val key = mangaId to chapterId
            val current = map[key] ?: ChapterStatus(mangaId = mangaId, chapterId = chapterId)
            map + (key to transform(current))
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}
