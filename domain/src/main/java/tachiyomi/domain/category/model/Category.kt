package tachiyomi.domain.category.model

import java.io.Serializable

data class Category(
    val id: Long,
    val name: String,
    val order: Long,
    val flags: Long,
    // KMK -->
    val hidden: Boolean,
    val parentId: Long = 0L,
    val dateAdded: Long = DATE_UNKNOWN,
    val dateModified: Long = DATE_UNKNOWN,

    // Raw TrackStatus ordinal. Stays an Int because TrackStatus lives in the app module, which
    // domain must not depend on. null means "not bound": a status change never touches this.
    val trackStatus: Int? = null,

    // Days between library updates. null inherits the global interval, which is the behaviour
    // every category has until it is given its own.
    val updateInterval: Int? = null,

    // KMK --> Role of a subcategory (author, artist, ...). null means unclassified, which is
    // how every existing row behaves; see [CategoryType].
    val type: Int? = null,
    // KMK <--
    // SY --> SyncYomi protocol v2 identity
    val uid: Long = 0,
    val version: Long = 0,
    val lastModifiedAt: Long = 0,
    // SY <--
) : Serializable {

    val isSystemCategory: Boolean = id == UNCATEGORIZED_ID

    companion object {
        const val UNCATEGORIZED_ID = 0L

        // KMK --> 0 = "unknown, predates tracking". Sorters treat it as a
        // fallback to id rather than as 1970, so legacy rows keep a sensible
        // order instead of all landing in one arbitrary tie.
        const val DATE_UNKNOWN = 0L
        // KMK <--
    }
}
