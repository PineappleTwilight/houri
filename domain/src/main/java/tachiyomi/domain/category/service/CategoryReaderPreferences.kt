package tachiyomi.domain.category.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class CategoryReaderPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun readerForCategory(categoryId: Long) = preferenceStore.getString("pref_reader_for_category_$categoryId", "")
    fun readerForSubcategory(categoryId: Long, parentId: Long) = preferenceStore.getString("pref_reader_for_subcategory_${parentId}_$categoryId", "")

    fun getReaderMode(categoryId: Long, parentId: Long = 0): String? {
        val sub = if (parentId != 0L) readerForSubcategory(categoryId, parentId).get().ifBlank { null } else null
        if (sub != null) return sub
        return readerForCategory(categoryId).get().ifBlank { null }
    }

    fun setReaderMode(categoryId: Long, mode: String?, parentId: Long = 0) {
        if (parentId != 0L) {
            readerForSubcategory(categoryId, parentId).set(mode ?: "")
        } else {
            readerForCategory(categoryId).set(mode ?: "")
        }
    }

    fun resolveForManga(categoryIds: List<Long>, categoryParentMap: Map<Long, Long> = emptyMap()): String? {
        for (id in categoryIds) {
            val parent = categoryParentMap[id] ?: 0L
            getReaderMode(id, parent)?.let { return it }
            if (parent != 0L) getReaderMode(parent)?.let { return it }
        }
        return null
    }
}
