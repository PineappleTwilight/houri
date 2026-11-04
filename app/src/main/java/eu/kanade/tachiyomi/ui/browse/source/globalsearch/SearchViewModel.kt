package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.concurrency.AppDispatchers
import mihon.core.viewmodel.StateViewModel
import mihon.core.viewmodel.ioCoroutineScope
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal

abstract class SearchViewModel(
    initialState: State,
    sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getManga: GetManga,
    private val preferences: SourcePreferences,
    private val appDispatchers: AppDispatchers,
    // KMK --> Migration-only chapter prefetch (see fetchChaptersForResults).
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    // KMK <--
) : StateViewModel<SearchViewModel.State>(initialState) {

    private val coroutineDispatcher = appDispatchers.extensions
    private var searchJob: Job? = null

    private val enabledLanguages = sourcePreferences.enabledLanguages().get()
    private val disabledSources = sourcePreferences.disabledSources().get()
    protected val pinnedSources = sourcePreferences.pinnedSources().get()
    // KMK --> "sourceId|category" entries backing the source category filter.
    // Source carries no categories property, so membership has to be matched here.
    protected val sourcesInCategories = sourcePreferences.sourcesTabSourcesInCategories().get()
    // KMK <--

    private var lastQuery: String? = null
    private var lastSourceFilter: SourceFilter? = null
    // KMK -->
    private var lastSourceCategory: String? = null

    // KMK -->
    private var lastExactAuthorMatch: Boolean? = null
    // KMK <--
    // KMK <--

    protected var extensionFilter: String? = null

    open val sortComparator = { map: Map<Source, SearchItemResult> ->
        compareBy<Source>(
            { (map[it] as? SearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
        )
    }

    init {
        viewModelScope.launch {
            preferences.globalSearchFilterState().changes().collectLatest { state ->
                mutableState.update { it.copy(onlyShowHasResults = state) }
            }
        }
        // KMK -->
        viewModelScope.launch {
            preferences.globalSearchPinnedState().changes().collectLatest { state ->
                mutableState.update { it.copy(sourceFilter = state) }
            }
        }
        viewModelScope.launch {
            preferences.globalSearchSourceCategory().changes().collectLatest { category ->
                mutableState.update { it.copy(sourceCategory = category) }
            }
        }
        viewModelScope.launch {
            preferences.globalSearchExactAuthorMatch().changes().collectLatest { exact ->
                mutableState.update { it.copy(exactAuthorMatch = exact) }
            }
        }
        // KMK <--
    }

    @Composable
    fun getManga(initialManga: Manga): androidx.compose.runtime.State<Manga> {
        return produceState(initialValue = initialManga) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .filterNotNull()
                .collectLatest { manga ->
                    value = manga
                }
        }
    }

    open fun getEnabledSources(): List<Source> {
        return sourceManager.getVisibleSources()
            // KMK --> The local sources bypass the language filter, as the sources list already
            // lets them. Both are lang "other"; without this a user who has not enabled "other"
            // cannot search their own local library or find a series to migrate into it from the
            // global search, while the same series is one tap away in the browse tab.
            .filter {
                (it.lang in enabledLanguages || it.isLocal()) &&
                    "${it.id}" !in disabledSources
            }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    // KMK -->
    fun hasPinnedSources(): Boolean = getEnabledSources().any { "${it.id}" in pinnedSources }

    fun shouldPinnedSourcesHidden() {
        if (!hasPinnedSources()) {
            preferences.globalSearchPinnedState().set(SourceFilter.All)
        }
    }
    // KMK <--

    private fun getSelectedSources(): List<Source> {
        val enabledSources = getEnabledSources()

        val filter = extensionFilter
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        // SY -->
        val filteredSourceIds = extensionManager.installedExtensionsFlow.value
            .filter { it.pkgName == filter }
            .flatMap { it.sources }
            .map { it.id }
        return enabledSources.filter { it.id in filteredSourceIds }
        // SY <--
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setSourceFilter(filter: SourceFilter) {
        preferences.globalSearchPinnedState().set(filter)
        // Sync state before search(): the pref flow in init updates sourceFilter
        // asynchronously, so search() would read the stale filter and early-return.
        mutableState.update { it.copy(sourceFilter = filter) }
        search()
    }

    // KMK -->
    fun setSourceCategory(category: String) {
        preferences.globalSearchSourceCategory().set(category)
        mutableState.update { it.copy(sourceCategory = category) }
        search()
    }
    // KMK <--

    fun toggleFilterResults() {
        preferences.globalSearchFilterState().toggle()
    }

    // KMK -->
    fun toggleExactAuthorMatch() {
        // Sets the concrete next value rather than calling toggle(), because the flow collector above
        // also writes this field. Negating it here as well would undo the collector if it lands first.
        val next = !state.value.exactAuthorMatch
        preferences.globalSearchExactAuthorMatch().set(next)
        mutableState.update { it.copy(exactAuthorMatch = next) }
        search()
    }
    // KMK <--

    fun search() {
        val query = state.value.searchQuery
        val sourceFilter = state.value.sourceFilter
        // KMK --> Part of the cache key too, or changing category would early-return
        val sourceCategory = state.value.sourceCategory
        val exactAuthorMatch = state.value.exactAuthorMatch
        // KMK <--

        if (query.isNullOrBlank()) return

        val sameQuery = this.lastQuery == query
        if (sameQuery &&
            this.lastSourceFilter == sourceFilter &&
            this.lastSourceCategory == sourceCategory &&
            this.lastExactAuthorMatch == exactAuthorMatch
        ) {
            return
        }

        this.lastQuery = query
        this.lastSourceFilter = sourceFilter
        this.lastSourceCategory = sourceCategory
        this.lastExactAuthorMatch = exactAuthorMatch

        searchJob?.cancel()

        val sources = getSelectedSources()

        // Reuse previous results if possible
        if (sameQuery) {
            val existingResults = state.value.items
            updateItems(
                sources
                    .associateWith { existingResults[it] ?: SearchItemResult.Loading }
                    .toPersistentMap(),
            )
        } else {
            updateItems(
                sources
                    .associateWith { SearchItemResult.Loading }
                    .toPersistentMap(),
            )
        }

        searchJob = ioCoroutineScope.launch {
            sources.map { source ->
                async {
                    if (state.value.items[source] !is SearchItemResult.Loading) {
                        return@async
                    }

                    try {
                        val page = withContext(coroutineDispatcher) {
                            source.getSearchManga(1, query.sanitize(), source.getFilterList())
                        }
                        // KMK -->
                        extensionManager.clearSourceError(source)
                        // KMK <--

                        val titles = page.mangas
                            .map { it.toDomainManga(source.id) }
                            .distinctBy { it.url }
                            .let { networkToLocalManga(it) }

                        // KMK --> Counts a source that answered with nothing. On the Success
                        // branch only, so a source that errored does not read as an empty hit.
                        if (titles.isEmpty()) {
                            runCatching { globalAppGraph.achievementManager.incrementCounter("empty_searches") }
                        }
                        // KMK <--

                        // KMK --> Narrowed after the counter above, so a source that answered and was
                        // then filtered out is not scored as one that had nothing to say.
                        val matches = if (exactAuthorMatch) {
                            titles.filter { it.matchesCreatorExactly(query) }
                        } else {
                            titles
                        }
                        // KMK <--

                        if (isActive) {
                            updateItem(source, SearchItemResult.Success(matches))
                            // KMK --> Prefetch chapter lists in the background so the latest
                            // chapter number appears as it lands. Cancelled with the search.
                            if (fetchChaptersForResults) {
                                launch { fetchLatestChapters(matches) }
                            }
                            // KMK <--
                        }
                    } catch (e: Exception) {
                        // KMK --> Recorded but never prompted. A global search queries every source
                        // at once, so each broken one raises its own popup and they queue up over the
                        // results - which is the one place the user is least able to act on any of
                        // them. The failure still lands on the extensions page, and using the same
                        // source directly still reports it.
                        extensionManager.reportSourceError(source, e, prompt = false)
                        // KMK <--
                        if (isActive) {
                            updateItem(source, SearchItemResult.Error(e))
                        }
                    }
                }
            }
                .awaitAll()
        }
    }

    // KMK -->
    /**
     * Migration-only: while the results for a source land, fetch each match's chapter list in the
     * background so the latest chapter number can be shown. Global search keeps this off.
     * Entries already known are skipped so re-searches do not re-fetch.
     */
    protected open val fetchChaptersForResults: Boolean = false

    private suspend fun fetchLatestChapters(matches: List<Manga>) {
        matches.forEach { manga ->
            if (!currentCoroutineContext().isActive) return
            if (state.value.latestChapters.containsKey(manga.id)) return@forEach
            try {
                updateMangaFromRemote(manga = manga, fetchChapters = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to prefetch chapters for ${manga.url}" }
                return@forEach
            }
            val latest = getChaptersByMangaId.await(manga.id).maxOfOrNull { it.chapterNumber }
            if (latest != null) {
                mutableState.update {
                    it.copy(latestChapters = it.latestChapters.put(manga.id, latest))
                }
            }
        }
    }
    // KMK <--

    private fun updateItems(items: Map<Source, SearchItemResult>) {
        mutableState.update {
            it.copy(
                items = items
                    .toSortedMap(sortComparator(items))
                    .toPersistentMap(),
            )
        }
    }

    private fun updateItem(source: Source, result: SearchItemResult) {
        mutableState.update { currentState ->
            val newItems = currentState.items + (source to result)
            currentState.copy(
                items = newItems
                    .toSortedMap(sortComparator(newItems))
                    .toPersistentMap(),
            )
        }
    }

    fun setMigrateDialog(currentId: Long, target: Manga) {
        viewModelScope.launchIO {
            val current = getManga.await(currentId) ?: return@launchIO
            mutableState.update { it.copy(dialog = Dialog.Migrate(target, current)) }
        }
    }

    fun clearDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    @Immutable
    data class State(
        val from: Manga? = null,
        val searchQuery: String? = null,
        val sourceFilter: SourceFilter = SourceFilter.PinnedOnly,
        // KMK --> Empty string means "no source category" rather than null.
        val sourceCategory: String = "",
        // KMK <--
        val onlyShowHasResults: Boolean = false,
        // KMK --> Narrows results to entries whose author or artist is exactly the query. Toggling it
        // re-runs the search, so it is part of the cached query identity rather than a display filter.
        val exactAuthorMatch: Boolean = false,
        // KMK <--
        val items: PersistentMap<Source, SearchItemResult> = persistentMapOf(),
        // KMK --> Latest chapter number per local manga id, filled in the background by the
        // migration search. Empty on the global search path.
        val latestChapters: PersistentMap<Long, Double> = persistentMapOf(),
        // KMK <--
        val dialog: Dialog? = null,
    ) {
        val progress: Int = items.count { it.value !is SearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
            .toImmutableMap()
    }

    sealed interface Dialog {
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }
}

enum class SourceFilter {
    All,
    PinnedOnly,
}

sealed interface SearchItemResult {
    data object Loading : SearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : SearchItemResult

    data class Success(
        val result: List<Manga>,
    ) : SearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !this.isEmpty)
    }
}
