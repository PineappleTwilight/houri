// KMK --> Library AST search contract shared with exh.search.SearchEngine
package tachiyomi.domain.library.model

object LibrarySearchParser {

    fun mapSearchNamespacePrefix(prefix: String): String =
        when (prefix.lowercase()) {
            "a" -> "artist"
            "c", "char" -> "character"
            "cat" -> "category"
            "f" -> "female"
            "g", "creator", "circle" -> "group"
            "l", "lang" -> "language"
            "m" -> "male"
            "p", "series" -> "parody"
            "r" -> "reclass"
            "sub", "subcat" -> "subcategory"
            else -> prefix.lowercase()
        }

    fun isAllowedSearchNamespace(namespace: String): Boolean =
        namespace.lowercase() in ALLOWED_SEARCH_NAMESPACES

    /**
     * Tokenizes a library query. Uppercase AND/OR are separators and uppercase NOT
     * excludes the next term; lowercase and/not/or stay literal text. A colon routes
     * to [LibrarySearchToken.Field] only for known field prefixes, so titles like
     * "One: Piece" stay plain title terms.
     */
    fun parse(query: String, enableAst: Boolean = true): List<LibrarySearchToken> {
        val res = mutableListOf<LibrarySearchToken>()
        var inQuotes = false
        var tokenQuoted = false
        val current = StringBuilder()
        var nextIsExcluded = false
        var nextIsExact = false

        fun flushToken() {
            if (current.isEmpty()) {
                tokenQuoted = false
                return
            }
            val raw = current.toString()
            current.setLength(0)
            val wasQuoted = tokenQuoted
            tokenQuoted = false
            if (enableAst && !wasQuoted) {
                if (raw == "AND" || raw == "OR") return
                if (raw == "NOT") {
                    nextIsExcluded = true
                    return
                }
            }
            val excluded = nextIsExcluded
            val exact = nextIsExact
            nextIsExcluded = false
            nextIsExact = false
            if (enableAst && !wasQuoted) {
                val colon = raw.indexOf(':')
                if (colon > 0) {
                    val prefix = raw.substring(0, colon)
                    val mapped = mapSearchNamespacePrefix(prefix)
                    if (isAllowedSearchNamespace(mapped) || isAllowedSearchNamespace(prefix)) {
                        res += LibrarySearchToken.Field(
                            namespace = mapped,
                            tag = raw.substring(colon + 1),
                            excluded = excluded,
                        )
                        return
                    }
                }
            }
            res += LibrarySearchToken.Term(text = raw, excluded = excluded, exact = exact)
        }

        for (char in query) {
            when {
                char == '"' -> {
                    inQuotes = !inQuotes
                    tokenQuoted = true
                }
                char == '$' -> nextIsExact = true
                !inQuotes && (char == ' ' || char == ',') -> flushToken()
                !inQuotes && char == '-' && current.isEmpty() -> nextIsExcluded = true
                else -> current.append(char)
            }
        }
        flushToken()
        return res
    }

    private val ALLOWED_SEARCH_NAMESPACES = setOf(
        "artist", "character", "female", "group", "language", "male", "parody", "reclass",
        "a", "c", "char", "f", "g", "creator", "circle", "l", "lang", "m", "p", "series", "r",
        "title", "author", "source", "genre", "tag", "tags", "status", "tracker",
        "desc", "description", "uploader", "id", "src",
        // KMK --> library category/subcategory search
        "category", "cat", "subcategory", "sub", "subcat",
        // KMK <--
    )
}
// KMK <--
