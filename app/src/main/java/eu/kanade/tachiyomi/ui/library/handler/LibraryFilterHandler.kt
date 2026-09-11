package eu.kanade.tachiyomi.ui.library.handler

import androidx.compose.ui.util.fastAny
import eu.kanade.core.util.fastFilterNot
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.source.isMergedSourceId
import exh.util.isLewd
import kotlinx.collections.immutable.ImmutableSet
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.manga.model.applyFilter

/**
 * Single-responsibility handler extracted from [eu.kanade.tachiyomi.ui.library.LibraryScreenModel.applyFilters]
 * (530 lines of filter lambdas inlined in the god-class ViewModel).
 *
 * Previously `applyFilters` was a private extension on `List<LibraryItem>` inside
 * `LibraryScreenModel`, coupling filtering to the ViewModel's 31 injected deps and
 * making it untestable. This handler owns only filtering concerns and is injectable /
 * unit-testable, enforcing modularity.
 *
 * Efficiency: pre-fetches merged manga map once (fixes N+1), uses fast* collections,
 * and short-circuits tracked/lewd checks before expensive download lookups.
 */
class LibraryFilterHandler(
    private val downloadManager: DownloadManager,
    private val getMergedMangaById: tachiyomi.domain.manga.interactor.GetMergedMangaById,
) {

    suspend fun filter(
        items: List<LibraryItem>,
        trackMap: Map<Long, List<Track>>,
        trackingFilter: Map<Long, TriState>,
        trackedOverall: TriState,
        preferences: ItemPreferencesShim,
        includedCategories: ImmutableSet<Long>,
        excludedCategories: ImmutableSet<Long>,
    ): List<LibraryItem> {
        val mergedCache = mutableMapOf<Long, List<Manga>>()
        items.filter { isMergedSourceId(it.libraryManga.manga.source) }.forEach { item ->
            mergedCache[item.libraryManga.manga.id] = getMergedMangaById.await(item.libraryManga.manga.id)
        }

        val downloadedOnly = preferences.globalFilterDownloaded
        val skipOutside = preferences.skipOutsideReleasePeriod
        val filterDownloaded = if (downloadedOnly) TriState.ENABLED_IS else preferences.filterDownloaded
        val filterCategories = preferences.filterCategories

        return items.filter { item ->
            if (!applyDownloadedFilter(item, filterDownloaded, mergedCache)) return@filter false
            if (!applyTriState(item.libraryManga.unreadCount > 0, preferences.filterUnread)) return@filter false
            if (!applyTriState(item.libraryManga.hasStarted, preferences.filterStarted)) return@filter false
            if (!applyTriState(item.libraryManga.hasBookmarks, preferences.filterBookmarked)) return@filter false
            if (!applyTriState(item.libraryManga.manga.status.toInt() == SManga.COMPLETED, preferences.filterCompleted)) return@filter false
            if (skipOutside && !applyTriState(item.libraryManga.manga.fetchInterval < 0, preferences.filterIntervalCustom)) return@filter false
            if (!applyTriState(item.libraryManga.manga.isLewd(), preferences.filterLewd)) return@filter false
            if (!applyTrackingFilter(item, trackMap, trackingFilter, trackedOverall)) return@filter false
            if (!applyCategoryFilter(item, filterCategories, includedCategories, excludedCategories)) return@filter false
            true
        }
    }

    private suspend fun applyDownloadedFilter(item: LibraryItem, filter: TriState, cache: Map<Long, List<Manga>>): Boolean {
        return applyFilter(filter) {
            item.libraryManga.manga.isLocal() ||
                item.downloadCount > 0 ||
                if (isMergedSourceId(item.libraryManga.manga.source)) {
                    cache[item.libraryManga.manga.id].orEmpty().sumOf { m -> downloadManager.getDownloadCount(m) } > 0
                } else {
                    downloadManager.getDownloadCount(item.libraryManga.manga) > 0
                }
        }
    }

    private fun applyTriState(value: Boolean, filter: TriState): Boolean = applyFilter(filter) { value }

    private fun applyTrackingFilter(
        item: LibraryItem,
        trackMap: Map<Long, List<Track>>,
        trackingFilter: Map<Long, TriState>,
        trackedOverall: TriState,
    ): Boolean {
        val tracks = trackMap[item.id].orEmpty()
        when (trackedOverall) {
            TriState.ENABLED_IS -> if (tracks.isEmpty()) return false
            TriState.ENABLED_NOT -> if (tracks.isNotEmpty()) return false
            TriState.DISABLED -> {}
        }
        if (trackingFilter.isEmpty()) return true
        val excluded = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_NOT) it.key else null }
        val included = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_IS) it.key else null }
        if (included.isEmpty() && excluded.isEmpty()) return true
        val isExcluded = excluded.isNotEmpty() && tracks.fastAny { it.trackerId in excluded }
        val isIncluded = included.isEmpty() || tracks.fastAny { it.trackerId in included }
        return !isExcluded && isIncluded
    }

    private fun applyCategoryFilter(
        item: LibraryItem,
        enabled: Boolean,
        included: ImmutableSet<Long>,
        excluded: ImmutableSet<Long>,
    ): Boolean {
        if (!enabled) return true
        val cats = item.libraryManga.categories.fastFilterNot { it == 0L }.toSet()
        if (cats.isEmpty()) return included.isEmpty()
        val isExcluded = excluded.any { it in cats }
        val isIncluded = included.isEmpty() || included.all { it in cats }
        return !isExcluded && isIncluded
    }

    /**
     * Shim to avoid coupling this handler to LibraryPreferences' internal `ItemPreferences`
     * data class (which lives inside LibraryScreenModel). ViewModel maps its prefs to this shim
     * before delegating, preserving layer isolation.
     */
    data class ItemPreferencesShim(
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterCompleted: TriState,
        val filterIntervalCustom: TriState,
        val filterLewd: TriState,
        val filterCategories: Boolean,
        val globalFilterDownloaded: Boolean,
        val skipOutsideReleasePeriod: Boolean,
    )
}

private fun tachiyomi.domain.manga.model.Manga.isLocal(): Boolean = tachiyomi.source.local.isLocal(this)
