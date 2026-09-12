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
        if (categoryId <= 0L) {
            logcat(LogPriority.WARN) { "DeleteCategory: invalid categoryId $categoryId" }
            return@withNonCancellableContext Result.InternalError(IllegalArgumentException("Invalid categoryId $categoryId"))
        }
        val all = try {
            categoryRepository.getAll()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "DeleteCategory: getAll failed, proceeding with single-id delete" }
            emptyList()
        }
        val descendants = tachiyomi.domain.category.service.CategoryTreeHandler.descendants(categoryId, all)
        val toDelete = buildSet {
            add(categoryId)
            addAll(descendants)
        }
        val depthById = run {
            val byId = all.associateBy { it.id }
            val depth = mutableMapOf<Long, Int>()
            fun depthOf(id: Long): Int {
                depth[id]?.let { return it }
                val cat = byId[id] ?: return 0
                val d = if (cat.parentId == 0L) 0 else depthOf(cat.parentId) + 1
                depth[id] = d
                return d
            }
            toDelete.forEach { depthOf(it) }
            depth
        }
        val deleteOrder = toDelete.sortedWith(compareByDescending<Long> { depthById[it] ?: 0 }.thenByDescending { it })
        var anyDeleteFailed = false
        for (id in deleteOrder) {
            try {
                categoryRepository.delete(id)
            } catch (e: Exception) {
                anyDeleteFailed = true
                logcat(LogPriority.ERROR, e) { "DeleteCategory: failed to delete category $id" }
            }
        }
        if (anyDeleteFailed) {
            logcat(LogPriority.WARN) { "DeleteCategory: partial delete failure for $categoryId -> $toDelete" }
        }

        val categories = try {
            categoryRepository.getAll().filterNot { it.id in toDelete }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "DeleteCategory: getAll after delete failed" }
            return@withNonCancellableContext Result.InternalError(e)
        }
        val updates = categories.groupBy { it.parentId }.flatMap { (_, group) ->
            group.sortedBy { it.order }.mapIndexed { index, category ->
                CategoryUpdate(id = category.id, order = index.toLong())
            }
        }

        val defaultCategory = libraryPreferences.defaultCategory().get()
        if (defaultCategory != null && defaultCategory != 0 && toDelete.contains(defaultCategory.toLong())) {
            try {
                libraryPreferences.defaultCategory().delete()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "DeleteCategory: failed to clear defaultCategory $defaultCategory" }
            }
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
