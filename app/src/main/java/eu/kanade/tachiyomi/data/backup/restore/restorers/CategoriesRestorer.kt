package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import mihon.app.di.globalAppGraph
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.service.LibraryPreferences

class CategoriesRestorer(
    // SY -->
    private val isSync: Boolean = false,
    // SY <--
    private val handler: DatabaseHandler = globalAppGraph.databaseHandler,
    private val getCategories: GetCategories = globalAppGraph.getCategories,
    private val libraryPreferences: LibraryPreferences = globalAppGraph.libraryPreferences,
) {

    /**
     * Restores the category tree and returns backup category id -> restored database id, so
     * callers can resolve a backup's category references by identity. [eu.kanade.tachiyomi.data.backup.models.BackupManga.categoryIds]
     * needs that; resolving by name or sort is ambiguous once subcategories exist.
     */
    suspend operator fun invoke(backupCategories: List<BackupCategory>): Map<Long, Long> {
        // KMK -->
        val restoredIdsByBackupId = mutableMapOf<Long, Long>()
        if (backupCategories.isNotEmpty()) {
            val dbCategories = getCategories.await()
            val dbCategoriesByName = dbCategories.associateBy { it.name }
            // SY --> Prefer uid when syncing so renames converge instead of duplicating.
            val dbCategoriesByUid = dbCategories.filter { it.uid != 0L }.associateBy { it.uid }
            // SY <--
            var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0L

            val pendingParents = mutableMapOf<Long, Long>()
            val allCurrent = (dbCategories + handler.awaitList { categoryQueries.getCategories(tachiyomi.data.category.CategoryMapper::mapCategory) }).distinctBy { it.id }
            val existingNamesByParent = allCurrent.groupBy { it.parentId }.mapValues { e -> e.value.map { it.name.lowercase() }.toMutableSet() }.toMutableMap()

            val nextOrderPerParent = mutableMapOf<Long, Long>()
            val categories = backupCategories
                .sortedWith(compareBy<BackupCategory> { it.parentId != 0L }.thenBy { it.order })
                .mapNotNull {
                    val trimmed = it.name.trim().take(50)
                    if (trimmed.isEmpty()) return@mapNotNull null
                    // SY -->
                    val dbCategory = when {
                        isSync && it.uid != 0L -> dbCategoriesByUid[it.uid]
                        else -> null
                    } ?: dbCategoriesByName[trimmed] ?: dbCategoriesByName[it.name]
                    // SY <--
                    if (dbCategory != null) {
                        if (it.id != 0L) restoredIdsByBackupId[it.id] = dbCategory.id
                        if (it.parentId != 0L && dbCategory.parentId == 0L) pendingParents[dbCategory.id] = it.parentId
                        // SY --> A sync delta is a converging replica: adopt the server's
                        // identity and order so reorders/renames converge across devices.
                        if (isSync) {
                            applySyncedCategory(dbCategory.id, it, trimmed, dbCategory.parentId)
                        }
                        // SY <--
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
                            if (isSync) {
                                applySyncedCategory(existing.id, it, trimmed, existing.parentId)
                            }
                            return@mapNotNull existing
                        }
                        return@mapNotNull null
                    }
                    // SY --> Sync restores land at the server's order; imports append.
                    val orderForParent = if (isSync) {
                        it.order
                    } else {
                        val baseMax = allCurrent.filter { c -> c.parentId == effectiveParentId }.maxOfOrNull { c -> c.order } ?: -1L
                        val perParentNext = nextOrderPerParent.getOrPut(effectiveParentId) { baseMax + 1 }
                        nextOrderPerParent[effectiveParentId] = perParentNext + 1
                        nextOrder++
                        perParentNext
                    }
                    // SY <--
                    val newId = handler.awaitOneExecutable {
                        if (isSync) {
                            categoryQueries.insertSync(
                                name = trimmed,
                                order = orderForParent,
                                flags = it.flags,
                                hidden = if (it.hidden) 1L else 0L,
                                parentId = 0L,
                                type = it.type?.toLong(),
                                uid = it.uid,
                                version = it.version,
                                lastModifiedAt = it.lastModifiedAt,
                            )
                        } else {
                            categoryQueries.insert(
                                trimmed,
                                orderForParent,
                                it.flags,
                                hidden = if (it.hidden) 1L else 0L,
                                parentId = 0L,
                                type = it.type?.toLong(),
                            )
                        }
                        categoryQueries.selectLastInsertedRowId()
                    }
                    siblings.add(trimmed.lowercase())
                    // KMK --> The insert statement leaves track_status NULL, and it cannot be
                    // passed through because coalesce-style updates read NULL as "leave alone", so a
                    // restored binding is applied with its own statement.
                    // Read through a local because these are `var` properties, so they cannot be
                    // smart-cast after the null check.
                    it.trackStatus?.let { trackStatus ->
                        handler.await {
                            categoryQueries.updateTrackStatus(trackStatus = trackStatus.toLong(), id = newId)
                        }
                    }
                    it.updateInterval?.let { updateInterval ->
                        handler.await {
                            categoryQueries.updateUpdateInterval(updateInterval = updateInterval.toLong(), id = newId)
                        }
                    }
                    // KMK --> Type is not in this family: the insert itself accepts it, so a
                    // restored subcategory keeps its role without a follow-up statement.
                    // KMK <--
                    if (it.id != 0L) restoredIdsByBackupId[it.id] = newId
                    if (it.parentId != 0L) pendingParents[newId] = it.parentId
                    it.toCategory(newId).copy(order = orderForParent, parentId = 0L, name = trimmed)
                }

            val byIdAfterInsert = handler.awaitList { categoryQueries.getCategories(tachiyomi.data.category.CategoryMapper::mapCategory) }.associateBy { it.id }
            pendingParents.forEach { (categoryId, backupParentId) ->
                val parentId = restoredIdsByBackupId[backupParentId] ?: return@forEach
                val cat = byIdAfterInsert[categoryId] ?: return@forEach
                val parent = byIdAfterInsert[parentId] ?: return@forEach
                if (parent.parentId != 0L) return@forEach
                if (parentId == categoryId) return@forEach
                if (tachiyomi.domain.category.service.CategoryTreeHandler.descendants(categoryId, byIdAfterInsert.values.toList()).contains(parentId)) return@forEach
                handler.await {
                    categoryQueries.update(
                        name = null,
                        order = null,
                        flags = null,
                        hidden = null,
                        parentId = parentId,
                        // KMK --> the update statement's partial-update column
                        type = null,
                        // KMK <--
                        id = categoryId,
                    )
                }
            }
            handler.await { categoryQueries.deleteOrphanedSubcategories() }
            if (isSync) {
                handler.await { categoryQueries.resetIsSyncing() }
            }
            // KMK <--

            libraryPreferences.categorizedDisplaySettings().set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
        return restoredIdsByBackupId
    }

    // SY -->
    private suspend fun applySyncedCategory(
        id: Long,
        backup: BackupCategory,
        name: String,
        parentId: Long,
    ) {
        handler.await {
            categoryQueries.updateSync(
                name = name,
                order = backup.order,
                flags = backup.flags,
                hidden = if (backup.hidden) 1L else 0L,
                parentId = parentId,
                type = backup.type?.toLong(),
                uid = backup.uid,
                version = backup.version,
                lastModifiedAt = backup.lastModifiedAt,
                id = id,
            )
        }
        backup.trackStatus?.let { trackStatus ->
            handler.await {
                categoryQueries.updateTrackStatus(trackStatus = trackStatus.toLong(), id = id)
            }
        }
        backup.updateInterval?.let { updateInterval ->
            handler.await {
                categoryQueries.updateUpdateInterval(updateInterval = updateInterval.toLong(), id = id)
            }
        }
    }
    // SY <--
}
