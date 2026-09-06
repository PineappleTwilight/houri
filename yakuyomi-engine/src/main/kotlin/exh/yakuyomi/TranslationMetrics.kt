package exh.yakuyomi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ChapterMetrics(
    val totalPages: Int = 0,
    val translated: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val avgWallMs: Long = 0,
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
)

class TranslationMetrics {
    private val wallTimes = ConcurrentHashMap<Pair<Long, Long>, MutableList<Long>>()
    private val promptTokens = ConcurrentHashMap<Pair<Long, Long>, AtomicLong>()
    private val completionTokens = ConcurrentHashMap<Pair<Long, Long>, AtomicLong>()
    private val _chapter = MutableStateFlow<Map<Pair<Long, Long>, ChapterMetrics>>(emptyMap())
    val chapter: StateFlow<Map<Pair<Long, Long>, ChapterMetrics>> = _chapter.asStateFlow()

    fun recordSuccess(mangaId: Long, chapterId: Long, wallMs: Long, prompt: Int, completion: Int) {
        val k = mangaId to chapterId
        wallTimes.getOrPut(k) { mutableListOf() }.add(wallMs)
        promptTokens.getOrPut(k) { AtomicLong() }.addAndGet(prompt.toLong())
        completionTokens.getOrPut(k) { AtomicLong() }.addAndGet(completion.toLong())
        emit(k)
    }

    fun recordOutcome(mangaId: Long, chapterId: Long, status: TranslationStatus) {
        emit(mangaId to chapterId, status)
    }

    private fun emit(k: Pair<Long, Long>, status: TranslationStatus? = null) {
        val times = wallTimes[k] ?: emptyList()
        val avg = if (times.isEmpty()) 0 else times.average().toLong()
        val pt = promptTokens[k]?.get()?.toInt() ?: 0
        val ct = completionTokens[k]?.get()?.toInt() ?: 0
        val cur = _chapter.value.toMutableMap()
        val prev = cur[k] ?: ChapterMetrics()
        cur[k] = prev.copy(avgWallMs = avg, totalPromptTokens = pt, totalCompletionTokens = ct)
        _chapter.value = cur
    }

    private fun emit(k: Pair<Long, Long>) = emit(k, null)

    fun clear(mangaId: Long, chapterId: Long) {
        wallTimes.remove(mangaId to chapterId)
        promptTokens.remove(mangaId to chapterId)
        completionTokens.remove(mangaId to chapterId)
        val cur = _chapter.value.toMutableMap()
        cur.remove(mangaId to chapterId)
        _chapter.value = cur
    }

    fun summary(mangaId: Long, chapterId: Long): ChapterMetrics = _chapter.value[mangaId to chapterId] ?: ChapterMetrics()
}
