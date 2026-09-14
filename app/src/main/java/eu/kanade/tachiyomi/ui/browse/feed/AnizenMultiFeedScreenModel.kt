package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.FeedItemUI
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.concurrency.AppDispatchers
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.interactor.ReorderFeed
import tachiyomi.domain.source.model.AnizenMultiFeedTabs
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.service.SourceManager
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import java.util.Collections
import kotlin.time.Duration.Companion.minutes
import tachiyomi.domain.manga.model.Manga as DomainManga

// KMK -->
/**
 * Anizen-style multi-feed tabs (glue-only).
 *
 * One tab per global [FeedSavedSearch] row, rendered with a TabRow + HorizontalPager.
 * Each tab fetches FeedScreenModel-style (latest/popular or saved-search query via
 * [SourceManager]), results are shared through a synchronized in-memory cache mirroring
 * the `RecommendationSearchHelper` pattern (TTL, per-feed entries), and tab ordering
 * follows [LibraryPreferences] (alphabetical by title when the library sort mode is
 * alphabetical, feed order otherwise). No DB migration, no source ABI change.
 */
open class AnizenMultiFeedScreenModel(
    val sourceManager: SourceManager = globalAppGraph.sourceManager,
    val sourcePreferences: SourcePreferences = globalAppGraph.sourcePreferences,
    private val libraryPreferences: LibraryPreferences = globalAppGraph.libraryPreferences,
    private val getManga: GetManga = globalAppGraph.getManga,
    private val networkToLocalManga: NetworkToLocalManga = globalAppGraph.networkToLocalManga,
    getFeedSavedSearchGlobal: GetFeedSavedSearchGlobal = globalAppGraph.getFeedSavedSearchGlobal,
    private val getSavedSearchGlobalFeed: GetSavedSearchGlobalFeed = globalAppGraph.getSavedSearchGlobalFeed,
    private val reorderFeed: ReorderFeed = globalAppGraph.reorderFeed,
    private val appDispatchers: AppDispatchers = globalAppGraph.appDispatchers,
) : StateScreenModel<AnizenMultiFeedState>(AnizenMultiFeedState()) {

    private val coroutineDispatcher = appDispatchers.backgroundOps

    /**
     * Shared per-tab result cache (RecommendationSearchHelper pattern:
     * synchronized map + TTL). Feed pages go stale fast, so the TTL is minutes,
     * not the 24h recs cache.
     */
    private val sharedCache: MutableMap<Long, CachedFeedPage> =
        Collections.synchronizedMap(mutableMapOf())

    private companion object {
        val CACHE_TTL = 10.minutes
    }

    private data class CachedFeedPage(
        val results: List<DomainManga>,
        val cachedAt: Long = System.currentTimeMillis(),
    ) {
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now - cachedAt < CACHE_TTL.inWholeMilliseconds
    }

    init {
        combine(
            getFeedSavedSearchGlobal.subscribe().distinctUntilChanged(),
            libraryPreferences.sortingMode().changes(),
        ) { feeds, sortingMode -> feeds to sortingMode }
            .onEach { (feeds, sortingMode) ->
                sourceManager.isInitialized.first { it }
                val alphabetical = sortingMode.type == LibrarySort.Type.Alphabetical
                refreshTabs(feeds, alphabetical)
            }
            .catch { logcat(LogPriority.ERROR) { "AnizenMultiFeed: failed observing feeds" } }
            .launchIn(screenModelScope)
    }

    private suspend fun refreshTabs(feeds: List<FeedSavedSearch>, alphabetical: Boolean) {
        val savedSearches = getSavedSearchGlobalFeed.await().associateBy { it.id }
        val previousSelectedId = state.value.tabs
            .getOrNull(state.value.selectedIndex)
            ?.feed?.id
        val previousResults = state.value.tabs.associate { it.feed.id to it }

        val built = feeds.mapNotNull { feed ->
            val savedId = feed.savedSearch
            if (savedId != null && savedId !in savedSearches) {
                logcat(LogPriority.WARN) { "AnizenMultiFeed: dropping feed ${feed.id} with missing savedSearch $savedId" }
                return@mapNotNull null
            }
            createTab(feed, savedSearches[savedId], sourceManager.get(feed.source))
        }

        val tabModels = AnizenMultiFeedTabs.buildTabs(built.map { it.feed })
        val titles = built.associate { it.feed.id to it.title }
        val orderedIds = AnizenMultiFeedTabs.orderTabs(tabModels, titles, alphabetical).map { it.feedId }
        val byId = built.associateBy { it.feed.id }
        val items = orderedIds.mapNotNull { byId[it] }.toImmutableList()

        val retained = AnizenMultiFeedTabs.retainPerTabState(tabModels, previousResults)
        val merged = items.map { item ->
            val kept = retained[item.feed.id]
            val cached = sharedCache[item.feed.id]
            when {
                kept?.results != null -> kept
                cached != null && cached.isFresh() -> item.copy(results = cached.results)
                else -> item.copy(results = null, failed = false)
            }
        }.toImmutableList()

        mutableState.update { state ->
            state.copy(
                items = merged,
                selectedIndex = AnizenMultiFeedTabs.selectedIndex(tabModels, previousSelectedId)
                    .coerceIn(0, merged.size.coerceAtLeast(1) - 1),
            )
        }
        fetchTabs(merged.filter { it.results == null })
    }

    private fun createTab(feed: FeedSavedSearch, savedSearch: SavedSearch?, source: Source?): FeedItemUI {
        return FeedItemUI(
            feed,
            savedSearch,
            source,
            savedSearch?.name ?: (source?.name ?: feed.source.toString()),
            if (savedSearch != null) {
                source?.name ?: feed.source.toString()
            } else {
                LocaleHelper.getLocalizedDisplayName(source?.lang)
            },
            null,
        )
    }

    fun refresh() {
        sharedCache.clear()
        val items = state.value.items ?: return
        mutableState.update { it.copy(items = items.map { item -> item.copy(results = null, failed = false) }.toImmutableList()) }
        fetchTabs(items)
    }

    fun retryTab(item: FeedItemUI) {
        screenModelScope.launchIO {
            if (state.value.items?.none { it.feed.id == item.feed.id } != false) return@launchIO
            sharedCache.remove(item.feed.id)
            updateItem(item.copy(results = null, failed = false))
            updateItem(fetchTab(item))
        }
    }

    fun selectTab(index: Int) {
        val size = state.value.items?.size ?: return
        if (size == 0) return
        mutableState.update { it.copy(selectedIndex = index.coerceIn(0, size - 1)) }
    }

    fun changeOrder(feed: FeedSavedSearch, newIndex: Int) {
        screenModelScope.launch {
            reorderFeed.changeOrder(feed, newIndex)
        }
    }

    private fun fetchTabs(tabs: List<FeedItemUI>) {
        if (tabs.isEmpty()) return
        screenModelScope.launch {
            tabs.map { item ->
                async { updateItem(fetchTab(item)) }
            }.awaitAll()
        }
    }

    private suspend fun fetchTab(itemUI: FeedItemUI): FeedItemUI {
        val page = try {
            if (itemUI.source != null) {
                withContext(coroutineDispatcher) {
                    if (itemUI.savedSearch == null) {
                        if (itemUI.source.supportsLatest) {
                            itemUI.source.getLatestUpdates(1)
                        } else {
                            itemUI.source.getPopularManga(1)
                        }
                    } else {
                        itemUI.source.getSearchManga(
                            1,
                            itemUI.savedSearch.query?.sanitize().orEmpty(),
                            getFilterList(itemUI.savedSearch, itemUI.source),
                        )
                    }
                }.mangas
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            return itemUI.copy(failed = true)
        }

        val hideInLibrary = sourcePreferences.hideInLibraryFeedItems().get()
        val result = withIOContext {
            itemUI.copy(
                results = page
                    .mapNotNull { itemUI.source?.let { source -> it.toDomainManga(source.id) } }
                    .distinctBy { it.url }
                    .let { networkToLocalManga(it) }
                    .filter { !hideInLibrary || !it.favorite },
                failed = false,
            )
        }
        if (!result.failed && result.results != null) {
            sharedCache[result.feed.id] = CachedFeedPage(result.results)
        }
        return result
    }

    private fun updateItem(result: FeedItemUI) {
        mutableState.update { state ->
            state.copy(
                items = state.items?.map { if (it.feed.id == result.feed.id) result else it }?.toImmutableList(),
            )
        }
    }

    private val filterSerializer = FilterSerializer()

    @Composable
    fun getManga(initialManga: DomainManga): State<DomainManga> {
        return produceState(initialValue = initialManga) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .collectLatest { manga ->
                    if (manga == null) return@collectLatest
                    value = manga
                }
        }
    }

    private fun getFilterList(savedSearch: SavedSearch, source: Source): FilterList {
        val filters = savedSearch.filtersJson ?: return FilterList()
        return runCatching {
            val originalFilters = source.getFilterList()
            filterSerializer.deserialize(
                filters = originalFilters,
                json = Json.decodeFromString(filters),
            )
            originalFilters
        }.getOrElse { FilterList() }
    }
}

data class AnizenMultiFeedState(
    val items: ImmutableList<FeedItemUI>? = null,
    val selectedIndex: Int = 0,
) {
    val tabs: ImmutableList<FeedItemUI>
        get() = items ?: persistentListOf()

    val isLoading
        get() = items == null

    val isEmpty
        get() = items.isNullOrEmpty()
}
// KMK <--
