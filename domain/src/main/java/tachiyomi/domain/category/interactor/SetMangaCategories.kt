package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class SetMangaCategories(
    private val mangaRepository: MangaRepository,
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(mangaId: Long, categoryIds: List<Long>) {
        if (mangaId <= 0L) {
            logcat(LogPriority.WARN) { "SetMangaCategories: invalid mangaId $mangaId" }
            return
        }
        try {
            val filtered = categoryIds.filter { it != 0L }.distinct()
            if (filtered.isEmpty()) {
                mangaRepository.setMangaCategories(mangaId, filtered)
                return
            }
            val byId = try {
                categoryRepository.getAll().associateBy { it.id }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "SetMangaCategories: getAll failed" }
                mangaRepository.setMangaCategories(mangaId, filtered)
                return
            }
            val expanded = filtered.toMutableSet()
            for (id in filtered) {
                var cur = byId[id] ?: continue
                while (cur.parentId != 0L) {
                    val parent = byId[cur.parentId] ?: break
                    if (!expanded.add(parent.id)) break
                    cur = parent
                }
            }
            val validated = expanded.filter { byId.containsKey(it) }
            mangaRepository.setMangaCategories(mangaId, validated)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
