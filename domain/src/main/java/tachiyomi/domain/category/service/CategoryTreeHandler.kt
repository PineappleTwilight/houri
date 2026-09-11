package tachiyomi.domain.category.service

import tachiyomi.domain.category.model.Category

/**
 * Pure hierarchy handler extracted from `LibraryScreenModel` and `CategoryRepository`
 * scattered handling of `parentId` trees.
 *
 * Previously every feature (library grouping, backup restore, orphan cleanup,
 * Discord RPC subcategory filtering) re-implemented `parentId == 0` checks
 * inline. This handler centralizes tree ops, making category/subcategory logic
 * single-source and testable.
 *
 * Single-responsibility: only tree math, no DB/I/O.
 */
object CategoryTreeHandler {

    fun roots(categories: List<Category>): List<Category> = categories.filter { it.parentId == 0L }

    fun childrenOf(parentId: Long, categories: List<Category>): List<Category> =
        categories.filter { it.parentId == parentId }

    fun isSubcategory(category: Category): Boolean = category.parentId != 0L

    fun rootIdFor(category: Category, byId: Map<Long, Category>): Long {
        var cur: Category? = category
        var root = category.id
        while (cur != null && cur.parentId != 0L) {
            root = cur.parentId
            cur = byId[root]
        }
        return root
    }

    fun descendants(rootId: Long, categories: List<Category>): Set<Long> {
        val byParent = categories.groupBy { it.parentId }
        val out = mutableSetOf<Long>()
        fun dfs(id: Long) {
            for (child in byParent[id].orEmpty()) {
                out.add(child.id)
                dfs(child.id)
            }
        }
        dfs(rootId)
        return out
    }

    fun orphans(categories: List<Category>): List<Category> {
        val ids = categories.map { it.id }.toSet()
        return categories.filter { it.parentId != 0L && it.parentId !in ids }
    }

    fun validateNoCycles(categories: List<Category>): Boolean {
        val byId = categories.associateBy { it.id }
        for (cat in categories) {
            var cur = cat
            val visited = mutableSetOf<Long>()
            while (cur.parentId != 0L) {
                if (!visited.add(cur.id)) return false
                cur = byId[cur.parentId] ?: break
            }
        }
        return true
    }
}
