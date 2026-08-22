package tachiyomi.domain.library.model

// KMK -->
/**
 * Scope used when refreshing the library while a subcategory is selected.
 */
enum class SubcategoryUpdateScope {
    /** Update every manga in the whole category tree (default). */
    WHOLE_CATEGORY,

    /** Update only the manga assigned to the selected subcategory. */
    SUBCATEGORY_ONLY,
}
// KMK <--
