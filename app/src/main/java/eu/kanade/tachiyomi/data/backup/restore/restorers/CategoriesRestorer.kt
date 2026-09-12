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
            var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0L

            // KMK -->
            val restoredIdsByBackupId = mutableMapOf<Long, Long>()
            val pendingParents = mutableMapOf<Long, Long>()
            val allCurrent = (dbCategories + handler.awaitList { categoriesQueries.getCategories(tachiyomi.data.category.CategoryMapper::mapCategory) }).distinctBy { it.id }
            val existingNamesByParent = allCurrent.groupBy { it.parentId }.mapValues { e -> e.value.map { it.name.lowercase() }.toMutableSet() }.toMutableMap()

            val nextOrderPerParent = mutableMapOf<Long, Long>()
            val categories = backupCategories
                .sortedWith(compareBy<BackupCategory> { it.parentId != 0L }.thenBy { it.order })
                .mapNotNull {
                    val trimmed = it.name.trim().take(50)
                    if (trimmed.isEmpty()) return@mapNotNull null
                    val dbCategory = dbCategoriesByName[trimmed] ?: dbCategoriesByName[it.name]
                    if (dbCategory != null) {
                        if (it.id != 0L) restoredIdsByBackupId[it.id] = dbCategory.id
                        if (it.parentId != 0L && dbCategory.parentId == 0L) pendingParents[dbCategory.id] = it.parentId
                        return@mapNotNull dbCategory
                    }
                    val intendedParentBackupId = it.parentId
                    val intendedParentId = if (intendedParentBackupId == 0L) 0L else restoredIdsByBackupId[intendedParentBackupId]
                    val effectiveParentId = when {
                        intendedParentBackupId == 0L -> 0L
                        intendedParentId == null -> 0L
                        else -> intendedParentId
                    }
                    val siblings = existingNamesByParent.getOrPut(effectiveParentId) { mutableSetOf() }
                    if (siblings.contains(trimmed.lowercase())) {
                        val existing = (dbCategories + allCurrent).find { c -> c.name.equals(trimmed, true) && c.parentId == effectiveParentId }
                        if (existing != null) {
                            if (it.id != 0L) restoredIdsByBackupId[it.id] = existing.id
                            return@mapNotNull existing
                        }
                        return@mapNotNull null
                    }
                    val baseMax = allCurrent.filter { c -> c.parentId == effectiveParentId }.maxOfOrNull { it.order } ?: -1L
                    val perParentNext = nextOrderPerParent.getOrPut(effectiveParentId) { baseMax + 1 }
                    val orderForParent = perParentNext
                    nextOrderPerParent[effectiveParentId] = perParentNext + 1
                    nextOrder++
                    val newId = handler.awaitOneExecutable {
                        categoriesQueries.insert(
                            trimmed,
                            orderForParent,
                            it.flags,
                            hidden = if (it.hidden) 1L else 0L,
                            parentId = 0L,
                        )
                        categoriesQueries.selectLastInsertedRowId()
                    }
                    siblings.add(trimmed.lowercase())
                    if (it.id != 0L) restoredIdsByBackupId[it.id] = newId
                    if (it.parentId != 0L) pendingParents[newId] = it.parentId
                    it.toCategory(newId).copy(order = orderForParent, parentId = 0L, name = trimmed)
                }

            val byIdAfterInsert = handler.awaitList { categoriesQueries.getCategories(tachiyomi.data.category.CategoryMapper::mapCategory) }.associateBy { it.id }
            pendingParents.forEach { (categoryId, backupParentId) ->
                val parentId = restoredIdsByBackupId[backupParentId] ?: return@forEach
                val cat = byIdAfterInsert[categoryId] ?: return@forEach
                val parent = byIdAfterInsert[parentId] ?: return@forEach
                if (parent.parentId != 0L) return@forEach
                if (parentId == categoryId) return@forEach
                if (tachiyomi.domain.category.service.CategoryTreeHandler.descendants(categoryId, byIdAfterInsert.values.toList()).contains(parentId)) return@forEach
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
            handler.await { categoriesQueries.deleteOrphanedSubcategories() }
            // KMK <--

            libraryPreferences.categorizedDisplaySettings().set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}
