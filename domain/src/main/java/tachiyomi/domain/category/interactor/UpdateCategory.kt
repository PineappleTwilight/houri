package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.category.service.CategoryTreeHandler

@Inject
class UpdateCategory(
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(payload: CategoryUpdate): Result = withNonCancellableContext {
        try {
            payload.parentId?.let { newParentId ->
                if (newParentId == payload.id) return@withNonCancellableContext Result.Error(IllegalArgumentException("Category cannot be its own parent"))
                if (newParentId != 0L) {
                    val all = categoryRepository.getAll()
                    val parent = all.find { it.id == newParentId } ?: return@withNonCancellableContext Result.Error(IllegalArgumentException("Parent not found"))
                    if (parent.parentId != 0L) return@withNonCancellableContext Result.Error(IllegalArgumentException("Only one level of subcategories allowed"))
                    if (CategoryTreeHandler.descendants(payload.id, all).contains(newParentId)) {
                        return@withNonCancellableContext Result.Error(IllegalArgumentException("Cycle detected"))
                    }
                    val siblings = all.filter { it.parentId == newParentId }
                    if (payload.name != null && siblings.any { it.name.equals(payload.name, ignoreCase = true) && it.id != payload.id }) {
                        return@withNonCancellableContext Result.Error(IllegalArgumentException("Duplicate name under new parent"))
                    }
                }
                val all = categoryRepository.getAll()
                if (!CategoryTreeHandler.validateNoCycles(all.map { if (it.id == payload.id) it.copy(parentId = newParentId) else it })) {
                    return@withNonCancellableContext Result.Error(IllegalArgumentException("Cycle detected"))
                }
            }
            payload.name?.let { n ->
                val t = n.trim()
                if (t.isEmpty()) return@withNonCancellableContext Result.Error(IllegalArgumentException("Category name blank"))
                if (t.length > 50) return@withNonCancellableContext Result.Error(IllegalArgumentException("Name too long"))
            }
            categoryRepository.updatePartial(payload)
            Result.Success
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    sealed interface Result {
        data object Success : Result
        data class Error(val error: Exception) : Result
    }
}
