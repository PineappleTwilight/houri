package tachiyomi.domain.source.model

// KMK -->
/**
 * Glue-only helper for Anizen-style multi-feed tabs.
 *
 * Scope is intentionally limited to global [FeedSavedSearch] rows: no DB migration,
 * no source ABI change. The DB query (`selectAllGlobal ORDER BY feed_order`) already
 * returns rows in feed order; this helper maps N rows to N tab models, preserves
 * per-tab state across list updates, and applies [LibraryPreferences][tachiyomi.domain.library.service.LibraryPreferences]
 * ordering (alphabetical when the library sort mode is alphabetical, feed order otherwise).
 *
 * Per-tab fetch itself stays FeedScreenModel-style (one fetch per [FeedSavedSearch]);
 * per-tab results are kept in a shared in-memory cache in the screen model, mirroring
 * the synchronized-map + TTL pattern of `RecommendationSearchHelper`.
 */
object AnizenMultiFeedTabs {

    data class Tab(
        val feedId: Long,
        val feedOrder: Long,
        val sourceId: Long,
        val savedSearchId: Long?,
    )

    fun buildTabs(feeds: List<FeedSavedSearch>): List<Tab> {
        return feeds
            .sortedBy { it.feedOrder }
            .map { feed ->
                Tab(
                    feedId = feed.id,
                    feedOrder = feed.feedOrder,
                    sourceId = feed.source,
                    savedSearchId = feed.savedSearch,
                )
            }
    }

    /**
     * LibraryPreferences ordering: alphabetical by resolved tab title when the caller
     * passes alphabetical = true (derived from LibraryPreferences sorting mode),
     * feed order otherwise. Titles fall back to feed id order when unknown.
     */
    fun orderTabs(tabs: List<Tab>, titles: Map<Long, String>, alphabetical: Boolean): List<Tab> {
        if (!alphabetical) return tabs.sortedBy { it.feedOrder }
        return tabs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { titles[it.feedId] ?: it.feedId.toString() })
    }

    /**
     * Clamp the selected tab index so pager state survives list updates.
     * Returns 0 when there are no tabs (callers show the empty-state instead).
     */
    fun selectedIndex(tabs: List<Tab>, selectedFeedId: Long?): Int {
        if (tabs.isEmpty()) return 0
        if (selectedFeedId == null) return 0
        val index = tabs.indexOfFirst { it.feedId == selectedFeedId }
        return index.coerceIn(0, tabs.size - 1)
    }

    /**
     * Preserve per-tab state (fetch results, scroll positions) across feed list updates:
     * keep entries whose feed still exists, drop removed feeds, do not create entries
     * for new feeds (they start unloaded).
     */
    fun <T> retainPerTabState(tabs: List<Tab>, previous: Map<Long, T>): Map<Long, T> {
        if (previous.isEmpty()) return emptyMap()
        val ids = tabs.map { it.feedId }.toSet()
        return previous.filterKeys { it in ids }
    }

    fun isEmpty(tabs: List<Tab>): Boolean = tabs.isEmpty()
}
// KMK <--
