// KMK --> Library AST search tokens shared with exh.search.SearchEngine
package tachiyomi.domain.library.model

sealed interface LibrarySearchToken {
    val excluded: Boolean

    data class Term(
        val text: String,
        override val excluded: Boolean = false,
        val exact: Boolean = false,
    ) : LibrarySearchToken

    data class Field(
        val namespace: String,
        val tag: String,
        override val excluded: Boolean = false,
    ) : LibrarySearchToken
}

/**
 * Required text of a token for title matching, or null when it contributes none.
 */
fun LibrarySearchToken.positiveText(): String? = when (this) {
    is LibrarySearchToken.Term -> if (!excluded) text else null
    is LibrarySearchToken.Field -> if (!excluded && tag.isNotBlank()) tag else null
}

/**
 * Excluded text of a token for title matching, or null when it contributes none.
 */
fun LibrarySearchToken.negativeText(): String? = when (this) {
    is LibrarySearchToken.Term -> if (excluded) text else null
    is LibrarySearchToken.Field -> if (excluded && tag.isNotBlank()) tag else null
}
// KMK <--
