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
        try {
            val filtered = categoryIds.filter { it != 0L }.distinct()
            if (filtered.isEmpty()) {
                mangaRepository.setMangaCategories(mangaId, filtered)
                return
            }
            val byId = categoryRepository.getAll().associateBy { it.id }
            val expanded = filtered.toMutableSet()
            for (id in filtered) {
                val cat = byId[id] ?: continue
                if (cat.parentId != 0L) expanded.add(cat.parentId)
            }
            val validated = expanded.filter { byId.containsKey(it) }
            mangaRepository.setMangaCategories(mangaId, validated)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
