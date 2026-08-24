package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.repository.CategoryRepository

// KMK -->
@Inject
class DeleteOrphanedSubcategories(
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(): Result = withNonCancellableContext {
        try {
            categoryRepository.deleteOrphanedSubcategories()
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }
    }

    sealed interface Result {
        data object Success : Result
        data class InternalError(val error: Throwable) : Result
    }
}
// KMK <--
