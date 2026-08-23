package eu.kanade.tachiyomi.ui.manga.scanlator

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// KMK -->
class ScanlatorPreferenceScreen(
    private val mangaId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = remember { ScanlatorPreferenceModel(mangaId) }
        val state by model.state.collectAsState()
        var selectedTab by mutableIntStateOf(0)

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(KMR.strings.scanlator_preference),
                    navigateUp = navigator::pop,
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(KMR.strings.scanlator_priority)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(KMR.strings.blacklisted_chapters)) },
                    )
                }
                if (selectedTab == 0) PriorityTab(state, model) else BlacklistTab(state, model)
            }
        }
    }

    @Composable
    private fun PriorityTab(
        state: ScanlatorPreferenceModel.State,
        model: ScanlatorPreferenceModel,
    ) {
        val listState = rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
            val reordered = state.orderedScanlators.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            model.setPriority(reordered.map { it.name })
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(MaterialTheme.padding.small),
        ) {
            items(state.orderedScanlators, key = { it.name }) { entry ->
                ReorderableItem(reorderableState, key = entry.name) {
                    val excluded = entry.name in state.excludedScanlators
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaterialTheme.padding.extraSmall)
                            .animateItem(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DragHandle,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(MaterialTheme.padding.small)
                                    .draggableHandle(),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(KMR.strings.scanlator_chapter_count, entry.count),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = { model.toggleExcluded(entry.name) }) {
                                Icon(
                                    imageVector = if (excluded) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = stringResource(KMR.strings.action_exclude_scanlator),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BlacklistTab(
        state: ScanlatorPreferenceModel.State,
        model: ScanlatorPreferenceModel,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(MaterialTheme.padding.small),
        ) {
            if (state.blacklist.isEmpty()) {
                item {
                    Text(
                        text = stringResource(KMR.strings.blacklist_empty),
                        modifier = Modifier.padding(MaterialTheme.padding.medium),
                    )
                }
            }
            items(state.blacklist, key = { it }) { key ->
                val number = key.substringBefore("@")
                val scanlator = key.substringAfter("@", "")
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.padding.extraSmall),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = number, style = MaterialTheme.typography.bodyLarge)
                            if (scanlator.isNotBlank()) {
                                Text(
                                    text = scanlator,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        IconButton(onClick = { model.removeBlacklisted(key) }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(MR.strings.action_remove),
                            )
                        }
                    }
                }
            }
        }
    }
}

// KMK -->
class ScanlatorPreferenceModel(
    private val mangaId: Long,
) : StateScreenModel<ScanlatorPreferenceModel.State>(State()) {

    data class State(
        val orderedScanlators: List<ScanlatorEntry> = emptyList(),
        val excludedScanlators: Set<String> = emptySet(),
        val blacklist: List<String> = emptyList(),
    )

    data class ScanlatorEntry(
        val name: String,
        val count: Int,
    )

    private val updateManga: UpdateManga = globalAppGraph.updateManga
    private val getChaptersByMangaId: GetChaptersByMangaId = globalAppGraph.getChaptersByMangaId
    private val getManga: GetManga = globalAppGraph.getManga
    private val getExcludedScanlators: GetExcludedScanlators = globalAppGraph.getExcludedScanlators
    private val setExcludedScanlators: SetExcludedScanlators = globalAppGraph.setExcludedScanlators

    init {
        screenModelScope.launch {
            getManga.subscribe(mangaId).collectLatest { manga ->
                val chapters = getChaptersByMangaId.await(mangaId)
                updateState(manga.scanlatorPriority, chapters, manga.blacklistedChapters)
            }
        }
        screenModelScope.launch {
            getExcludedScanlators.subscribe(mangaId).collectLatest { excluded ->
                mutableState.update { it.copy(excludedScanlators = excluded.toSet()) }
            }
        }
    }

    private suspend fun updateState(priority: List<String>, chapters: List<Chapter>, blacklist: List<String>) {
        val counts = chapters
            .filter { !it.scanlator.isNullOrBlank() }
            .groupingBy { it.scanlator!! }
            .eachCount()
        val known = priority.filter { it in counts.keys }
        val unconfigured = counts.keys.filter { it !in priority }.sorted()
        mutableState.update {
            it.copy(
                orderedScanlators = (known + unconfigured).map { name -> ScanlatorEntry(name, counts[name] ?: 0) },
                blacklist = blacklist.sorted(),
            )
        }
    }

    fun setPriority(names: List<String>) {
        screenModelScope.launch { updateManga.await(MangaUpdate(id = mangaId, scanlatorPriority = names)) }
    }

    fun toggleExcluded(name: String) {
        screenModelScope.launch {
            val current = state.value.excludedScanlators
            setExcludedScanlators.await(
                mangaId,
                if (name in current) current - name else current + name,
            )
        }
    }

    fun removeBlacklisted(key: String) {
        screenModelScope.launch {
            updateManga.await(
                MangaUpdate(id = mangaId, blacklistedChapters = state.value.blacklist - key),
            )
        }
    }
}
// KMK <--
