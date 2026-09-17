package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.category.service.CategoryTreeHandler
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Merges duplicate subcategories into a single target: manga assigned to any
 * source move to the target, children of sources are reparented to the target
 * (deleting a parent would otherwise cascade into its children), then the
 * sources are deleted and sibling order is compacted.
 */
@Inject
class MergeCategories(
    private val categoryRepository: CategoryRepository,
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(targetId: Long, sourceIds: List<Long>) = withNonCancellableContext {
        val sources = sourceIds.filter { it > 0L && it != targetId }.distinct()
        if (targetId <= 0L || sources.isEmpty()) {
            logcat(LogPriority.WARN) { "MergeCategories: invalid target $targetId sources $sourceIds" }
            return@withNonCancellableContext Result.InternalError(
                IllegalArgumentException("Invalid merge target/sources"),
            )
        }
        try {
            val all = categoryRepository.getAll()
            val byId = all.associateBy { it.id }
            val target = byId[targetId]
                ?: return@withNonCancellableContext Result.InternalError(
                    IllegalArgumentException("Merge target $targetId not found"),
                )
            val existingSources = sources.mapNotNull { byId[it] }
            if (existingSources.size != sources.size) {
                return@withNonCancellableContext Result.InternalError(
                    IllegalArgumentException("Some merge sources not found"),
                )
            }

            val targetChildren = CategoryTreeHandler.childrenOf(targetId, all)
            var nextOrder = (targetChildren.maxOfOrNull { it.order } ?: -1L) + 1L
            val skipReparent = sources.toSet() + targetId
            for (source in existingSources) {
                for (child in CategoryTreeHandler.childrenOf(source.id, all).sortedBy { it.order }) {
                    if (child.id in skipReparent) continue
                    categoryRepository.updatePartial(
                        CategoryUpdate(id = child.id, parentId = targetId, order = nextOrder++),
                    )
                }
            }

            val library = mangaRepository.getLibraryManga()
            for (item in library) {
                if (item.categories.any { it in sources }) {
                    val reassigned = (item.categories - sources.toSet() + targetId).distinct()
                    mangaRepository.setMangaCategories(item.id, reassigned)
                }
            }

            for (source in existingSources) {
                categoryRepository.delete(source.id)
            }

            val affectedParents = (existingSources.map { it.parentId } + target.parentId).toSet()
            val remaining = categoryRepository.getAll()
            val renumber = affectedParents.flatMap { parentId ->
                remaining.filter { it.parentId == parentId }
                    .sortedBy { it.order }
                    .mapIndexed { index, category -> CategoryUpdate(id = category.id, order = index.toLong()) }
            }
            if (renumber.isNotEmpty()) categoryRepository.updatePartial(renumber)

            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "MergeCategories into $targetId failed" }
            Result.InternalError(e)
        }
    }

    sealed interface Result {
        data object Success : Result
        data class InternalError(val error: Throwable) : Result
    }
}
