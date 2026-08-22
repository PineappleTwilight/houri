@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.util.fastAny
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mihon.app.di.globalAppGraph
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.interactor.CountFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.DeleteFeedSavedSearchById
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceId
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.interactor.InsertFeedSavedSearch
import tachiyomi.domain.source.interactor.ReorderFeed
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.service.SourceManager
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import java.util.concurrent.Executors
import tachiyomi.domain.manga.model.Manga as DomainManga

/**
 * Presenter of [feedTab]
 */
open class FeedScreenModel(
    val sourceManager: SourceManager = globalAppGraph.sourceManager,
    val sourcePreferences: SourcePreferences = globalAppGraph.sourcePreferences,
    private val getManga: GetManga = globalAppGraph.getManga,
    private val networkToLocalManga: NetworkToLocalManga = globalAppGraph.networkToLocalManga,
    getFeedSavedSearchGlobal: GetFeedSavedSearchGlobal = globalAppGraph.getFeedSavedSearchGlobal,
    private val getSavedSearchGlobalFeed: GetSavedSearchGlobalFeed = globalAppGraph.getSavedSearchGlobalFeed,
    private val countFeedSavedSearchGlobal: CountFeedSavedSearchGlobal = globalAppGraph.countFeedSavedSearchGlobal,
    private val getSavedSearchBySourceId: GetSavedSearchBySourceId = globalAppGraph.getSavedSearchBySourceId,
    private val insertFeedSavedSearch: InsertFeedSavedSearch = globalAppGraph.insertFeedSavedSearch,
    private val deleteFeedSavedSearchById: DeleteFeedSavedSearchById = globalAppGraph.deleteFeedSavedSearchById,
    // KMK -->
    private val reorderFeed: ReorderFeed = globalAppGraph.reorderFeed,
    // KMK <--
) : StateScreenModel<FeedScreenState>(FeedScreenState()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    private val coroutineDispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
    var pushed: Boolean = false

    init {
        getFeedSavedSearchGlobal.subscribe()
            .distinctUntilChanged()
            .onEach {
                sourceManager.isInitialized.first { it }
                val items = getSourcesToGetFeed(it).map { (feed, savedSearch) ->
                    createCatalogueSearchItem(
                        feed = feed,
                        savedSearch = savedSearch,
                        source = sourceManager.get(feed.source),
                        results = null,
                    )
                }
                mutableState.update { state ->
                    state.copy(
                        items = items
                            // KMK -->
                            .toImmutableList(),
                        // KMK <--
                    )
                }
                getFeed(items)
            }
            .catch { _events.send(Event.FailedFetchingSources) }
            .launchIn(screenModelScope)
    }

    fun init() {
        pushed = false
        // KMK -->
        // Refetch without clearing results so stale rows stay visible while loading.
        val currentItems = state.value.items ?: return
        getFeed(currentItems)
        // KMK <--
    }

    fun openAddDialog() {
        screenModelScope.launchIO {
            if (hasTooManyFeeds()) {
                _events.send(Event.TooManyFeeds)
                return@launchIO
            }
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.AddFeed(getEnabledSources()),
                )
            }
        }
    }

    fun openAddSearchDialog(source: Source) {
        screenModelScope.launchIO {
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.AddFeedSearch(
                        source,
                        (
                            // KMK -->
                            // (if (source.supportsLatest) persistentListOf(null) else persistentListOf()) +
                            persistentListOf(null) +
                                // KMK <-->
                                getSourceSavedSearches(source.id)
                            ).toImmutableList(),
                    ),
                )
            }
        }
    }

    fun openDeleteDialog(feed: FeedSavedSearch) {
        screenModelScope.launchIO {
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.DeleteFeed(feed),
                )
            }
        }
    }

    // KMK -->
    fun openActionsDialog(
        feed: FeedItemUI,
    ) {
        screenModelScope.launchIO {
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.FeedActions(
                        feedItem = feed,
                    ),
                )
            }
        }
    }
    // KMK <--

    private suspend fun hasTooManyFeeds(): Boolean {
        return countFeedSavedSearchGlobal.await() > MaxFeedItems
    }

    private fun getEnabledSources(): ImmutableList<Source> {
        val languages = sourcePreferences.enabledLanguages().get()
        val pinnedSources = sourcePreferences.pinnedSources().get()
        val disabledSources = sourcePreferences.disabledSources().get()
            .mapNotNull { it.toLongOrNull() }

        val list = sourceManager.getVisibleSources()
            .filter { it.lang in languages }
            .filterNot { it.id in disabledSources }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { "(${it.lang}) ${it.name}" })

        return list.sortedBy { it.id.toString() !in pinnedSources }.toImmutableList()
    }

    private suspend fun getSourceSavedSearches(sourceId: Long): ImmutableList<SavedSearch> {
        return getSavedSearchBySourceId.await(sourceId).toImmutableList()
    }

    fun createFeed(source: Source, savedSearch: SavedSearch?) {
        screenModelScope.launchNonCancellable {
            insertFeedSavedSearch.await(
                FeedSavedSearch(
                    id = -1,
                    source = source.id,
                    savedSearch = savedSearch?.id,
                    global = true,
                    feedOrder = 0,
                ),
            )
        }
    }

    fun deleteFeed(feed: FeedSavedSearch) {
        screenModelScope.launchNonCancellable {
            deleteFeedSavedSearchById.await(feed.id)
        }
    }

    // KMK -->
    fun changeOrder(feed: FeedSavedSearch, newIndex: Int) {
        screenModelScope.launch {
            reorderFeed.changeOrder(feed, newIndex)
        }
    }
    // KMK <--

    private suspend fun getSourcesToGetFeed(feedSavedSearch: List<FeedSavedSearch>): List<Pair<FeedSavedSearch, SavedSearch?>> {
        val savedSearches = getSavedSearchGlobalFeed.await()
            .associateBy { it.id }
        return feedSavedSearch
            .map { it to savedSearches[it.savedSearch] }
    }

    /**
     * Creates a catalogue search item
     */
    private fun createCatalogueSearchItem(
        feed: FeedSavedSearch,
        savedSearch: SavedSearch?,
        source: Source?,
        results: List<DomainManga>?,
    ): FeedItemUI {
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
            results,
        )
    }

    // KMK -->
    private val hideInLibraryFeedItems = sourcePreferences.hideInLibraryFeedItems()
    // KMK <--

    /**
     * Initiates get manga per feed.
     */
    private fun getFeed(feedSavedSearch: List<FeedItemUI>) {
        screenModelScope.launch {
            feedSavedSearch.map { itemUI ->
                async {
                    updateFeedItem(fetchFeedItem(itemUI))
                }
            }.awaitAll()
        }
    }

    // KMK -->
    fun retryFeed(item: FeedItemUI) {
        screenModelScope.launchIO {
            if (state.value.items?.none { it.feed.id == item.feed.id } != false) return@launchIO
            updateFeedItem(item.copy(results = null, failed = false))
            updateFeedItem(fetchFeedItem(item))
        }
    }

    fun refreshFeed(feed: FeedSavedSearch) {
        state.value.items
            ?.firstOrNull { it.feed.id == feed.id }
            ?.let { retryFeed(it) }
    }
    // KMK <--

    private suspend fun fetchFeedItem(itemUI: FeedItemUI): FeedItemUI {
        val page = try {
            if (itemUI.source != null) {
                withContext(coroutineDispatcher) {
                    if (itemUI.savedSearch == null) {
                        // KMK -->
                        if (itemUI.source.supportsLatest) {
                            // KMK <--
                            itemUI.source.getLatestUpdates(1)
                            // KMK -->
                        } else {
                            itemUI.source.getPopularManga(1)
                        }
                        // KMK <--
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
            // KMK -->
            // Keep stale results on failure so the row doesn't blank out.
            return itemUI.copy(failed = true)
            // KMK <--
        }

        val result = withIOContext {
            itemUI.copy(
                results = page
                    .mapNotNull { itemUI.source?.let { source -> it.toDomainManga(source.id) } }
                    .distinctBy { it.url }
                    .let { networkToLocalManga(it) }
                    // KMK -->
                    .filter { !hideInLibraryFeedItems.get() || !it.favorite },
                failed = false,
                // KMK <--
            )
        }

        return result
    }

    // KMK -->
    private fun updateFeedItem(result: FeedItemUI) {
        mutableState.update { state ->
            state.copy(
                items = state.items?.map { if (it.feed.id == result.feed.id) result else it }
                    ?.toImmutableList(),
            )
        }
    }
    // KMK <--

    private val filterSerializer = FilterSerializer()

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
    override fun onDispose() {
        super.onDispose()
        coroutineDispatcher.close()
    }

    // KMK -->
    fun showDialog(dialog: Dialog) {
        if (!state.value.isLoading) {
            mutableState.update {
                it.copy(dialog = dialog)
            }
        }
    }
    // KMK <--

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed class Dialog {
        data class AddFeed(val options: ImmutableList<Source>) : Dialog()
        data class AddFeedSearch(val source: Source, val options: ImmutableList<SavedSearch?>) : Dialog()
        data class DeleteFeed(val feed: FeedSavedSearch) : Dialog()

        // KMK -->
        data class FeedActions(
            val feedItem: FeedItemUI,
        ) : Dialog()
        // KMK <--
    }

    sealed class Event {
        data object FailedFetchingSources : Event()
        data object TooManyFeeds : Event()
    }
}

data class FeedScreenState(
    val dialog: FeedScreenModel.Dialog? = null,
    val items: ImmutableList<FeedItemUI>? = null,
) {
    val isLoading
        get() = items == null

    val isEmpty
        get() = items.isNullOrEmpty()

    val isLoadingItems
        get() = items?.fastAny { it.results == null } != false
}

const val MaxFeedItems = 20
