package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class CreateCategoryWithName(
    private val categoryRepository: CategoryRepository,
    private val preferences: LibraryPreferences,
) {

    private val initialFlags: Long
        get() {
            val sort = preferences.sortingMode().get()
            return sort.type.flag or sort.direction.flag
        }

    suspend fun await(name: String, parentId: Long = 0L): Result = withNonCancellableContext {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withNonCancellableContext Result.InternalError(IllegalArgumentException("Category name blank"))
        if (trimmed.length > 50) return@withNonCancellableContext Result.InternalError(IllegalArgumentException("Category name too long"))
        val categories = categoryRepository.getAll()
        if (categories.any { it.name.equals(trimmed, ignoreCase = true) && it.parentId == parentId }) {
            return@withNonCancellableContext Result.InternalError(IllegalArgumentException("Duplicate category name under same parent"))
        }
        if (parentId != 0L) {
            val parent = categories.find { it.id == parentId } ?: return@withNonCancellableContext Result.InternalError(IllegalArgumentException("Parent not found"))
            if (parent.parentId != 0L) return@withNonCancellableContext Result.InternalError(IllegalArgumentException("Only one level of subcategories allowed"))
        }
        val nextOrder = categories.filter { it.parentId == parentId }.maxOfOrNull { it.order }?.plus(1) ?: 0
        val newCategory = Category(
            id = 0,
            name = trimmed,
            order = nextOrder,
            flags = initialFlags,
            // KMK -->
            hidden = false,
            parentId = parentId,
            // KMK <--
        )

        try {
            categoryRepository.insert(newCategory)
            Result.Success(/* SY --> */newCategory/* SY <-- */)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.InternalError(e)
        }
    }

    sealed interface Result {
        // SY -->
        data class Success(val category: Category) : Result

        // SY <--
        data class InternalError(val error: Throwable) : Result
    }
}
