package eu.kanade.tachiyomi.ui.manga.scanlator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.util.chapter.ScanlatorCoverage
import eu.kanade.tachiyomi.util.chapter.scanlatorCoverage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackStack
import mihon.core.viewmodel.StateViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ScanlatorRangeRule
import tachiyomi.domain.chapter.service.deriveScanlatorPriority
import tachiyomi.domain.chapter.service.encodeScanlatorRangeRule
import tachiyomi.domain.chapter.service.parseScanlatorRangeRule
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// KMK -->
@Composable
fun ScanlatorPreferenceScreen(
    mangaId: Long,
) {
    val backStack = LocalBackStack.current
    val model = assistedMetroViewModel<ScanlatorPreferenceViewModel, ScanlatorPreferenceViewModel.Factory> {
        create(mangaId)
    }
    val state by model.state.collectAsState()
    // KMK --> without remember any recomposition snaps back to the priority tab
    var selectedTab by remember { mutableIntStateOf(0) }

    // Guarantee the system back button exits this screen (users reported getting stuck here).
    BackHandler { backStack.removeLastOrNull() }

    Scaffold(
        topBar = {
            AppBar(
                title = stringResource(KMR.strings.scanlator_preference),
                navigateUp = backStack::removeLastOrNull,
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
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(stringResource(KMR.strings.scanlator_ranges)) },
                )
            }
            when (selectedTab) {
                0 -> PriorityTab(state, model)
                1 -> BlacklistTab(state, model)
                else -> RangesTab(state, model)
            }
        }
    }
}

@Composable
private fun PriorityTab(
    state: ScanlatorPreferenceViewModel.State,
    model: ScanlatorPreferenceViewModel,
) {
    val listState = rememberLazyListState()
    // The order only decides anything while the merge is on, so with it off the list must not
    // accept drags or persist a new priority that nothing will read.
    val mergeEnabled = state.mergeEnabled
    // KMK --> drag against a local mirror: persisting every move to the DB rebuilds
    // state.orderedScanlators mid-drag and fights the finger. Resync when settled.
    val dragList = remember { state.orderedScanlators.toMutableStateList() }
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        if (!mergeEnabled) return@rememberReorderableLazyListState
        // KMK --> from.index/to.index are ABSOLUTE LazyColumn indexes and include the
        // auto-priority/merge-off note item above the entries, so indexing dragList with them
        // shifts every move one row off and overruns its last row (data size N has max index
        // N-1): dragging the last entry upward crashed with IndexOutOfBoundsException
        // (index: 7, size: 7). Resolve positions by stable item key instead, as
        // CategoryScreen/MigrationConfigScreen do; guard -1 for hovering the note item,
        // which is not an entry.
        val fromEntry = dragList.indexOfFirst { it.name == from.key }
        val toEntry = dragList.indexOfFirst { it.name == to.key }
        if (fromEntry == -1 || toEntry == -1) return@rememberReorderableLazyListState
        dragList.apply { add(toEntry, removeAt(fromEntry)) }
        model.setPriority(dragList.map { it.name })
    }
    LaunchedEffect(state.orderedScanlators) {
        if (!reorderableState.isAnyItemDragging) {
            dragList.clear()
            dragList.addAll(state.orderedScanlators)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MaterialTheme.padding.small),
    ) {
        if (state.autoPriority) {
            item(key = "auto_priority") {
                Text(
                    text = stringResource(KMR.strings.scanlator_priority_auto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.padding.small,
                        vertical = MaterialTheme.padding.extraSmall,
                    ),
                )
            }
        } else if (!mergeEnabled) {
            item(key = "merge_off") {
                Text(
                    text = stringResource(KMR.strings.scanlator_priority_merge_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.padding.small,
                        vertical = MaterialTheme.padding.extraSmall,
                    ),
                )
            }
        }
        items(dragList, key = { it.name }) { entry ->
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
                            tint = if (mergeEnabled) {
                                Color.Unspecified
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .padding(MaterialTheme.padding.small)
                                .then(if (mergeEnabled) Modifier.draggableHandle() else Modifier),
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
                            entry.coverage?.let { coverage ->
                                // KMK -->
                                val moreText = if (coverage.hiddenCount > 0) {
                                    stringResource(KMR.strings.scanlator_coverage_more, coverage.hiddenCount)
                                } else {
                                    null
                                }
                                Text(
                                    text = buildString {
                                        append(coverage.groups.joinToString(", "))
                                        if (moreText != null) append(", ").append(moreText)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // KMK <--
                            }
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
    state: ScanlatorPreferenceViewModel.State,
    model: ScanlatorPreferenceViewModel,
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
            val number = key.substringBefore("@").toDoubleOrNull()?.asChapterLabel() ?: key.substringBefore("@")
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

@Composable
private fun RangesTab(
    state: ScanlatorPreferenceViewModel.State,
    model: ScanlatorPreferenceViewModel,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MaterialTheme.padding.small),
    ) {
        item(key = "add_range") {
            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(KMR.strings.scanlator_range_add))
            }
        }

        if (state.rangeRules.isEmpty()) {
            item(key = "ranges_empty") {
                Text(
                    text = stringResource(KMR.strings.scanlator_ranges_empty),
                    modifier = Modifier.padding(MaterialTheme.padding.medium),
                )
            }
        }

        items(state.rangeRules, key = { it }) { raw ->
            val rule = remember(raw) { parseScanlatorRangeRule(raw) }
            if (rule != null) {
                RangeRuleItem(
                    rule = rule,
                    onRemove = { model.removeRangeRule(raw) },
                )
            }
        }
    }

    if (showAddDialog) {
        RangeRuleDialog(
            scanlators = state.orderedScanlators.map { it.name },
            onConfirm = { from, to, scanlator ->
                model.addRangeRule(from, to, scanlator)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun RangeRuleItem(
    rule: ScanlatorRangeRule,
    onRemove: () -> Unit,
) {
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
                Text(
                    text = "${rule.from.asChapterLabel()} – ${rule.to.asChapterLabel()}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = rule.scanlator,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(MR.strings.action_remove),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RangeRuleDialog(
    scanlators: List<String>,
    onConfirm: (Double, Double, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var selectedScanlator by remember { mutableStateOf<String?>(null) }

    val from = fromText.toDoubleOrNull()
    val to = toText.toDoubleOrNull()
    val valid = from != null && to != null && from <= to && !selectedScanlator.isNullOrEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(KMR.strings.scanlator_range_add)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { fromText = it },
                    label = { Text(text = stringResource(KMR.strings.scanlator_range_from)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = toText,
                    onValueChange = { toText = it },
                    label = { Text(text = stringResource(KMR.strings.scanlator_range_to)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                Text(
                    text = stringResource(KMR.strings.scanlator_range_scanlator),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    scanlators.forEach { name ->
                        FilterChip(
                            selected = name == selectedScanlator,
                            onClick = { selectedScanlator = name },
                            label = { Text(text = name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            val scanlator = selectedScanlator
            TextButton(
                enabled = valid,
                onClick = {
                    if (from != null && to != null && scanlator != null) {
                        onConfirm(from, to, scanlator)
                    }
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

private fun Double.asChapterLabel(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

// KMK -->
@AssistedInject
class ScanlatorPreferenceViewModel(
    @Assisted private val mangaId: Long,
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getManga: GetManga,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val libraryPreferences: LibraryPreferences,
) : StateViewModel<ScanlatorPreferenceViewModel.State>(State()) {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long): ScanlatorPreferenceViewModel
    }

    data class State(
        val orderedScanlators: List<ScanlatorEntry> = emptyList(),
        val excludedScanlators: Set<String> = emptySet(),
        val blacklist: List<String> = emptyList(),
        val rangeRules: List<String> = emptyList(),
        val autoPriority: Boolean = false,
        val mergeEnabled: Boolean = true,
    )

    data class ScanlatorEntry(
        val name: String,
        val count: Int,
        val coverage: ScanlatorCoverage? = null,
    )

    init {
        viewModelScope.launch {
            getManga.subscribe(mangaId)
                .combine(libraryPreferences.smartScanlatorMerge().changes()) { manga, mergeEnabled ->
                    manga to mergeEnabled
                }
                .collectLatest { (manga, mergeEnabled) ->
                    val chapters = getChaptersByMangaId.await(mangaId)
                    updateState(
                        manga.scanlatorPriority,
                        chapters,
                        manga.blacklistedChapters,
                        manga.scanlatorRangeRules,
                        mergeEnabled = mergeEnabled,
                    )
                }
        }
        viewModelScope.launch {
            getExcludedScanlators.subscribe(mangaId).collectLatest { excluded ->
                mutableState.update { it.copy(excludedScanlators = excluded.toSet()) }
            }
        }
    }

    private suspend fun updateState(
        priority: List<String>,
        chapters: List<Chapter>,
        blacklist: List<String>,
        rangeRules: List<String>,
        mergeEnabled: Boolean,
    ) {
        val byScanlator = chapters
            .filter { !it.scanlator.isNullOrBlank() }
            .groupBy { it.scanlator!! }
        // With no order of the user's own, show the order the merge is actually applying, so the
        // list explains the chapter list rather than listing names alphabetically.
        val auto = priority.isEmpty() && mergeEnabled
        val effective = when {
            auto -> chapters.deriveScanlatorPriority()
            else -> priority
        }
        val known = effective.filter { it in byScanlator.keys }
        val unconfigured = byScanlator.keys.filter { it !in effective }.sorted()
        mutableState.update {
            it.copy(
                orderedScanlators = (known + unconfigured).map { name ->
                    ScanlatorEntry(
                        name = name,
                        count = byScanlator[name]?.size ?: 0,
                        coverage = scanlatorCoverage(byScanlator[name].orEmpty().map { it.chapterNumber }),
                    )
                },
                blacklist = blacklist.sorted(),
                rangeRules = rangeRules,
                autoPriority = auto,
                mergeEnabled = mergeEnabled,
            )
        }
    }

    fun setPriority(names: List<String>) {
        viewModelScope.launch { updateManga.await(MangaUpdate(id = mangaId, scanlatorPriority = names)) }
    }

    fun toggleExcluded(name: String) {
        viewModelScope.launch {
            val current = state.value.excludedScanlators
            setExcludedScanlators.await(
                mangaId,
                if (name in current) current - name else current + name,
            )
        }
    }

    fun removeBlacklisted(key: String) {
        viewModelScope.launch {
            updateManga.await(
                MangaUpdate(id = mangaId, blacklistedChapters = state.value.blacklist - key),
            )
        }
    }

    fun addRangeRule(from: Double, to: Double, scanlator: String) {
        viewModelScope.launch {
            val rule = encodeScanlatorRangeRule(ScanlatorRangeRule(from, to, scanlator))
            val current = state.value.rangeRules
            if (rule in current) return@launch
            updateManga.await(
                MangaUpdate(id = mangaId, scanlatorRangeRules = current + rule),
            )
        }
    }

    fun removeRangeRule(raw: String) {
        viewModelScope.launch {
            updateManga.await(
                MangaUpdate(id = mangaId, scanlatorRangeRules = state.value.rangeRules - raw),
            )
        }
    }
}
// KMK <--
