package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class DeleteCategory(
    private val categoryRepository: CategoryRepository,
    private val libraryPreferences: LibraryPreferences,
    private val downloadPreferences: DownloadPreferences,
) {

    suspend fun await(categoryId: Long) = withNonCancellableContext {
        val all = try { categoryRepository.getAll() } catch (_: Exception) { emptyList() }
        val toDelete = buildSet {
            add(categoryId)
            addAll(tachiyomi.domain.category.service.CategoryTreeHandler.descendants(categoryId, all))
        }
        try {
            // KMK -->
            toDelete.sortedDescending().forEach { id ->
                try { categoryRepository.delete(id) } catch (_: Exception) {}
            }
            // KMK <--
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        val categories = categoryRepository.getAll().filterNot { it.id in toDelete }
        val updates = categories.groupBy { it.parentId }.flatMap { (_, group) ->
            group.sortedBy { it.order }.mapIndexed { index, category ->
                CategoryUpdate(id = category.id, order = index.toLong())
            }
        }

        val defaultCategory = libraryPreferences.defaultCategory().get()
        if (defaultCategory == categoryId.toInt()) {
            libraryPreferences.defaultCategory().delete()
        }

        val categoryPreferences = listOf(
            libraryPreferences.updateCategories(),
            libraryPreferences.updateCategoriesExclude(),
            downloadPreferences.removeExcludeCategories(),
            downloadPreferences.downloadNewChapterCategories(),
            downloadPreferences.downloadNewChapterCategoriesExclude(),
            // KMK -->
            libraryPreferences.filterCategoriesInclude(),
            libraryPreferences.filterCategoriesExclude(),
            // KMK <--
        )
        val toRemove = toDelete.map { it.toString() }.toSet()
        categoryPreferences.forEach { preference ->
            val ids = preference.get()
            val filtered = ids - toRemove
            if (filtered.size != ids.size) preference.set(filtered)
        }

        try {
            categoryRepository.updatePartial(updates)
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.InternalError(e)
        }
    }

    sealed interface Result {
        data object Success : Result
        data class InternalError(val error: Throwable) : Result
    }
}
