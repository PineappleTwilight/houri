package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

@Inject
class RenameCategory(
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(categoryId: Long, name: String) = withNonCancellableContext {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withNonCancellableContext Result.InternalError(IllegalArgumentException("Category name blank"))
        if (trimmed.length > 50) return@withNonCancellableContext Result.InternalError(IllegalArgumentException("Name too long"))
        val all = categoryRepository.getAll()
        val target = all.find { it.id == categoryId }
        if (target != null && all.any { it.id != categoryId && it.parentId == target.parentId && it.name.equals(trimmed, ignoreCase = true) }) {
            return@withNonCancellableContext Result.InternalError(IllegalArgumentException("Duplicate name under same parent"))
        }
        val update = CategoryUpdate(
            id = categoryId,
            name = trimmed,
        )

        try {
            categoryRepository.updatePartial(update)
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.InternalError(e)
        }
    }

    suspend fun await(category: Category, name: String) = await(category.id, name)

    sealed interface Result {
        data object Success : Result
        data class InternalError(val error: Throwable) : Result
    }
}
