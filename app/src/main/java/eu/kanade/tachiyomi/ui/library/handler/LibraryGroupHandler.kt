package eu.kanade.tachiyomi.ui.library.handler

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryGroup

/**
 * Extracts category-grouping logic from [eu.kanade.tachiyomi.ui.library.LibraryScreenModel.applyGrouping].
 *
 * The ViewModel previously mixed grouping (BY_DEFAULT vs BY_SOURCE vs BY_TAG etc.)
 * with ViewModel state updates and DB pref reads, making the 200-line `applyGrouping`
 * untestable. This handler is pure: `categories + items -> grouped map`, single
 * responsibility, and testable without Android dependencies.
 */
object LibraryGroupHandler {

    fun groupByDefault(
        items: List<Pair<Long, List<Long>>>,
        categories: List<Category>,
        showHidden: Boolean,
        activeCategoryId: Long?,
        activeSubCategoryId: Long?,
    ): Map<Category, List<Long>> {
        var showSystem = false
        val byId = categories.associateBy { it.id }
        val direct = mutableMapOf<Long, MutableList<Long>>()
        val tree = mutableMapOf<Long, MutableList<Long>>()
        items.forEach { (itemId, catIds) ->
            catIds.forEach { cid ->
                if (cid == 0L) showSystem = true
                direct.getOrPut(cid) { mutableListOf() }.add(itemId)
                val rootId = byId[cid]?.takeIf { it.parentId != 0L }?.parentId ?: cid
                tree.getOrPut(rootId) { mutableListOf() }.add(itemId)
            }
        }
        return categories.filter {
            (showSystem || !it.isSystemCategory) && (showHidden || !it.hidden) && it.parentId == 0L
        }.associateWith { cat ->
            if (cat.id == activeCategoryId && activeSubCategoryId != null) {
                direct[activeSubCategoryId].orEmpty()
            } else {
                tree[cat.id].orEmpty()
            }
        }
    }

    fun displayNameRes(groupType: Int) = LibraryGroup.groupTypeStringRes(groupType)
}
