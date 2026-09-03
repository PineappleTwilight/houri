package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** No-op stub of [TranslationStatus] for the no-MTL APK variant: nothing is ever translating. */
@SingleIn(AppScope::class)
@Inject
class TranslationStatus {

    enum class PageState {
        TRANSLATING,
        DONE,
        CACHED,
        SKIPPED,
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
        val isTranslating: Boolean get() = false
        val translatedCount: Int get() = 0
        val skippedCount: Int get() = 0
        val doneCount: Int get() = 0
        val errorCount: Int get() = 0
        val hasTranslatedPages: Boolean get() = false
        val pendingCount: Int get() = totalPages.coerceAtLeast(0)
    }

    private val _chapters = MutableStateFlow<Map<Pair<Long, Long>, ChapterStatus>>(emptyMap())
    val chapters: StateFlow<Map<Pair<Long, Long>, ChapterStatus>> = _chapters.asStateFlow()

    fun chapterStatus(mangaId: Long, chapterId: Long): ChapterStatus? = null

    fun setTotalPages(mangaId: Long, chapterId: Long, totalPages: Int) = Unit

    fun pageTranslating(mangaId: Long, chapterId: Long, pageIndex: Int, totalPages: Int = 0) = Unit
    fun pageDone(mangaId: Long, chapterId: Long, pageIndex: Int, totalPages: Int = 0) = Unit
    fun pageCached(mangaId: Long, chapterId: Long, pageIndex: Int, totalPages: Int = 0) = Unit
    fun pageSkipped(mangaId: Long, chapterId: Long, pageIndex: Int) = Unit
    fun pageError(mangaId: Long, chapterId: Long, pageIndex: Int, error: String, totalPages: Int = 0) = Unit
    fun resetChapter(mangaId: Long, chapterId: Long) = Unit
    fun clearAll() {
        _chapters.value = emptyMap()
    }
}
