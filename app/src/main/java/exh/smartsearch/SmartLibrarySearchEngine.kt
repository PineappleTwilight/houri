package exh.smartsearch

import mihon.feature.migration.list.search.BaseSmartSearchEngine
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySearchParser
import tachiyomi.domain.library.model.negativeText
import tachiyomi.domain.library.model.positiveText

class SmartLibrarySearchEngine(
    extraSearchParams: String? = null,
) : BaseSmartSearchEngine<LibraryManga>(extraSearchParams, 0.7) {

    override fun getTitle(result: LibraryManga) = result.manga.ogTitle

    suspend fun smartSearch(library: List<LibraryManga>, title: String): LibraryManga? {
        // KMK --> parity with exh.search.SearchEngine: uppercase-only AND/OR/NOT,
        // lowercase stays literal, field prefixes contribute their value
        val parsed = LibrarySearchParser.parse(title)
        val excluded = parsed.mapNotNull { it.negativeText() }
        val positiveTitle = parsed.mapNotNull { it.positiveText() }.joinToString(" ").trim()
        // KMK <--
        return deepSearch(
            { query ->
                library.filter {
                    it.manga.ogTitle.contains(query, true) &&
                        // KMK --> honor NOT/- exclusions
                        excluded.none { exclusion -> it.manga.ogTitle.contains(exclusion, true) }
                    // KMK <--
                }
            },
            positiveTitle.ifBlank { title },
        )
    }
}
