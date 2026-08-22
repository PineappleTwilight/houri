package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import mihon.app.di.globalAppGraph
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.service.LibraryPreferences

class CategoriesRestorer(
    private val handler: DatabaseHandler = globalAppGraph.databaseHandler,
    private val getCategories: GetCategories = globalAppGraph.getCategories,
    private val libraryPreferences: LibraryPreferences = globalAppGraph.libraryPreferences,
) {

    suspend operator fun invoke(backupCategories: List<BackupCategory>) {
        if (backupCategories.isNotEmpty()) {
            val dbCategories = getCategories.await()
            val dbCategoriesByName = dbCategories.associateBy { it.name }
            var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0

            // KMK -->
            // Restored rows get fresh ids, so parent references from the backup file
            // have to be remapped instead of being copied verbatim
            val restoredIdsByBackupId = mutableMapOf<Long, Long>()
            val pendingParents = mutableMapOf<Long, Long>()

            val categories = backupCategories
                .sortedBy { it.order }
                .map {
                    val dbCategory = dbCategoriesByName[it.name]
                    if (dbCategory != null) {
                        if (it.id != 0L) restoredIdsByBackupId[it.id] = dbCategory.id
                        return@map dbCategory
                    }
                    val order = nextOrder++
                    val newId = handler.awaitOneExecutable {
                        categoriesQueries.insert(
                            it.name,
                            order,
                            it.flags,
                            // KMK -->
                            hidden = if (it.hidden) 1L else 0L,
                            parentId = 0L,
                            // KMK <--
                        )
                        categoriesQueries.selectLastInsertedRowId()
                    }
                    if (it.id != 0L) restoredIdsByBackupId[it.id] = newId
                    if (it.parentId != 0L) pendingParents[newId] = it.parentId
                    it.toCategory(newId).copy(order = order)
                }

            pendingParents.forEach { (categoryId, backupParentId) ->
                // Parents missing from both the backup and the library promote to top level
                val parentId = restoredIdsByBackupId[backupParentId] ?: return@forEach
                handler.await {
                    categoriesQueries.update(
                        name = null,
                        order = null,
                        flags = null,
                        hidden = null,
                        parentId = parentId,
                        categoryId = categoryId,
                    )
                }
            }
            // KMK <--

            libraryPreferences.categorizedDisplaySettings().set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}
