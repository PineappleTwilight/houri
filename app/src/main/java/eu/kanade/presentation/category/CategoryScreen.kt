package eu.kanade.presentation.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.category.components.CategoryListItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.category.CategoryManagerSort
import eu.kanade.tachiyomi.ui.category.CategoryScreenState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun CategoryScreen(
    state: CategoryScreenState.Success,
    onClickCreate: () -> Unit,
    onClickRename: (Category) -> Unit,
    onClickDelete: (Category) -> Unit,
    onChangeOrder: (Category, Int) -> Unit,
    // KMK -->
    onClickHide: (Category) -> Unit,
    onCreateSubcategory: (Category) -> Unit,
    onReparentSubcategory: (Category, Long, Int) -> Unit,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    collapsedIds: Set<Long>,
    onToggleCollapsed: (Long) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    sortMode: Int,
    onSortMode: (Int) -> Unit,
    mangaCounts: ImmutableMap<Long, Int>,
    selectMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelectMode: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectVisible: (Set<Long>) -> Unit,
    onBulkHide: () -> Unit,
    onBulkShow: () -> Unit,
    onShowMoveDialog: () -> Unit,
    onShowMergeDialog: () -> Unit,
    onShowDeleteMany: () -> Unit,
    // KMK <--
    navigateUp: () -> Unit,
) {
    val lazyListState = rememberLazyListState()
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.action_edit_categories),
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onToggleSelectMode) {
                        Icon(
                            imageVector = Icons.Outlined.Checklist,
                            contentDescription = stringResource(KMR.strings.category_manager_select),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            CategoryFloatingActionButton(
                lazyListState = lazyListState,
                onCreate = onClickCreate,
            )
        },
    ) { paddingValues ->
        if (state.isEmpty) {
            EmptyScreen(
                stringRes = MR.strings.information_empty_category,
                modifier = Modifier.padding(paddingValues),
            )
            return@Scaffold
        }

        CategoryContent(
            categories = state.categories,
            lazyListState = lazyListState,
            paddingValues = paddingValues,
            onClickRename = onClickRename,
            onClickDelete = onClickDelete,
            onChangeOrder = onChangeOrder,
            // KMK -->
            onClickHide = onClickHide,
            onCreateSubcategory = onCreateSubcategory,
            onReparentSubcategory = onReparentSubcategory,
            searchQuery = searchQuery,
            onSearchQuery = onSearchQuery,
            collapsedIds = collapsedIds,
            onToggleCollapsed = onToggleCollapsed,
            onExpandAll = onExpandAll,
            onCollapseAll = onCollapseAll,
            sortMode = sortMode,
            onSortMode = onSortMode,
            mangaCounts = mangaCounts,
            selectMode = selectMode,
            selectedIds = selectedIds,
            onToggleSelection = onToggleSelection,
            onSelectVisible = onSelectVisible,
            onBulkHide = onBulkHide,
            onBulkShow = onBulkShow,
            onShowMoveDialog = onShowMoveDialog,
            onShowMergeDialog = onShowMergeDialog,
            onShowDeleteMany = onShowDeleteMany,
            // KMK <--
        )
    }
}

@Composable
private fun CategoryContent(
    categories: ImmutableList<Category>,
    lazyListState: LazyListState,
    paddingValues: PaddingValues,
    onClickRename: (Category) -> Unit,
    onClickDelete: (Category) -> Unit,
    onChangeOrder: (Category, Int) -> Unit,
    // KMK -->
    onClickHide: (Category) -> Unit,
    onCreateSubcategory: (Category) -> Unit,
    onReparentSubcategory: (Category, Long, Int) -> Unit,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    collapsedIds: Set<Long>,
    onToggleCollapsed: (Long) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    sortMode: Int,
    onSortMode: (Int) -> Unit,
    mangaCounts: ImmutableMap<Long, Int>,
    selectMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onSelectVisible: (Set<Long>) -> Unit,
    onBulkHide: () -> Unit,
    onBulkShow: () -> Unit,
    onShowMoveDialog: () -> Unit,
    onShowMergeDialog: () -> Unit,
    onShowDeleteMany: () -> Unit,
    // KMK <--
) {
    val query = searchQuery.trim()
    val topLevel = remember(categories) { categories.filter { it.parentId == 0L } }
    // KMK -->
    val subMap = remember(categories, sortMode, mangaCounts) {
        categories
            .filter { it.parentId != 0L }
            .groupBy { it.parentId }
            .mapValues { (_, subs) ->
                when (sortMode) {
                    CategoryManagerSort.AZ -> subs.sortedBy { it.name.lowercase() }
                    CategoryManagerSort.ZA -> subs.sortedByDescending { it.name.lowercase() }
                    CategoryManagerSort.MOST_ITEMS -> subs.sortedByDescending { mangaCounts[it.id] ?: 0 }
                    CategoryManagerSort.LEAST_ITEMS -> subs.sortedBy { mangaCounts[it.id] ?: 0 }
                    else -> subs.sortedBy { it.order }
                }
            }
    }

    // Single flat list so every row is an individually reorderable item.
    // Searching ignores collapsed state and shows matching parents (with all
    // their subs for context) or matching subs under their parent header.
    val rows = remember(topLevel, subMap, query, collapsedIds, mangaCounts) {
        buildList {
            topLevel.forEach { parent ->
                val subs = subMap[parent.id].orEmpty()
                if (query.isEmpty()) {
                    val collapsed = parent.id in collapsedIds && subs.isNotEmpty()
                    add(CategoryRow(parent, isTopLevel = true, mangaCount = mangaCounts[parent.id] ?: 0, collapsed = collapsed))
                    if (!collapsed) {
                        subs.forEach { add(CategoryRow(it, isTopLevel = false, mangaCount = mangaCounts[it.id] ?: 0)) }
                    }
                } else {
                    val parentMatches = parent.name.contains(query, ignoreCase = true)
                    val matching = subs.filter { it.name.contains(query, ignoreCase = true) }
                    if (parentMatches || matching.isNotEmpty()) {
                        add(CategoryRow(parent, isTopLevel = true, mangaCount = mangaCounts[parent.id] ?: 0))
                        (if (parentMatches) subs else matching).forEach {
                            add(CategoryRow(it, isTopLevel = false, mangaCount = mangaCounts[it.id] ?: 0))
                        }
                    }
                }
            }
        }
    }
    // Manual drag-reorder only makes sense on the unfiltered, manually-sorted list.
    val reorderEnabled = query.isEmpty() && sortMode == CategoryManagerSort.MANUAL && !selectMode
    // KMK <--
    val rowState = remember { rows.toMutableStateList() }
    // KMK --> defer the DB commit until the drag ends: writing on every onMove
    // churns the categories flow mid-gesture and fights the drag, leaving rows stuck.
    var pendingDrop by remember { mutableStateOf<Pair<CategoryRow, Int>?>(null) }
    val reorderableState = rememberReorderableLazyListState(lazyListState, paddingValues) { from, to ->
        // KMK -->
        // from.index/to.index are ABSOLUTE LazyColumn indices (they include the two header
        // item()s above the category rows), so resolve rowState positions by stable item key
        // instead. Guard -1: the dragged row may hover the unwrapped header items, which have
        // no entry in rowState.
        val fromRow = rowState.indexOfFirst { it.category.key == from.key }
        val toRow = rowState.indexOfFirst { it.category.key == to.key }
        if (fromRow == -1 || toRow == -1) return@rememberReorderableLazyListState
        val moved = rowState.removeAt(fromRow)
        rowState.add(toRow, moved)
        pendingDrop = moved to toRow
        // KMK <--
    }

    fun commitDrop(moved: CategoryRow, dropIndex: Int) {
        if (!moved.isTopLevel) {
            var newParentId = moved.category.parentId
            for (i in dropIndex - 1 downTo 0) {
                if (rowState[i].isTopLevel) {
                    newParentId = rowState[i].category.id
                    break
                }
            }
            if (newParentId != moved.category.parentId) {
                var countBefore = 0
                for (i in 0 until dropIndex) {
                    if (!rowState[i].isTopLevel) {
                        var parentForI = moved.category.parentId
                        for (k in i - 1 downTo 0) {
                            if (rowState[k].isTopLevel) {
                                parentForI = rowState[k].category.id
                                break
                            }
                        }
                        if (parentForI == newParentId) countBefore++
                    }
                }
                onReparentSubcategory(moved.category, newParentId, countBefore.coerceAtLeast(0))
                return
            }
            val simpleIndex = rowState.take(dropIndex).count { !it.isTopLevel && it.category.parentId == moved.category.parentId }
            onChangeOrder(moved.category, simpleIndex.coerceAtLeast(0))
        } else {
            val siblingIndex = rowState.take(dropIndex).count { it.isTopLevel }
            onChangeOrder(moved.category, siblingIndex.coerceAtLeast(0))
        }
    }
    // KMK <--

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            pendingDrop?.let { (moved, index) ->
                pendingDrop = null
                commitDrop(moved, index)
            }
        }
    }

    LaunchedEffect(rows) {
        if (!reorderableState.isAnyItemDragging) {
            rowState.clear()
            rowState.addAll(rows)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = lazyListState,
        contentPadding = paddingValues +
            topSmallPaddingValues +
            PaddingValues(horizontal = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        // KMK -->
        item(key = "category-manager-controls") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQuery,
                modifier = Modifier.fillMaxWidth().animateItem(),
                placeholder = { Text(stringResource(KMR.strings.category_manager_search)) },
                leadingIcon = { Icon(imageVector = Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
        }
        item(key = "category-manager-sort") {
            Row(
                modifier = Modifier.fillMaxWidth().animateItem(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SortModeDropdown(sortMode = sortMode, onSortMode = onSortMode, modifier = Modifier.weight(1f))
                TextButton(onClick = onExpandAll) {
                    Text(stringResource(KMR.strings.category_manager_expand_all))
                }
                TextButton(onClick = onCollapseAll) {
                    Text(stringResource(KMR.strings.category_manager_collapse_all))
                }
            }
        }
        if (selectMode) {
            item(key = "category-manager-bulk") {
                Row(
                    modifier = Modifier.fillMaxWidth().animateItem(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onSelectVisible(rows.map { it.category.id }.toSet()) }) {
                        Text(stringResource(KMR.strings.category_manager_select))
                    }
                    TextButton(onClick = onBulkHide, enabled = selectedIds.isNotEmpty()) {
                        Text(stringResource(KMR.strings.action_hide))
                    }
                    TextButton(onClick = onBulkShow, enabled = selectedIds.isNotEmpty()) {
                        Text(stringResource(KMR.strings.category_manager_show))
                    }
                    TextButton(onClick = onShowMoveDialog, enabled = selectedIds.isNotEmpty()) {
                        Text(stringResource(KMR.strings.category_manager_move))
                    }
                    TextButton(onClick = onShowMergeDialog, enabled = selectedIds.size >= 2) {
                        Text(stringResource(KMR.strings.category_manager_merge))
                    }
                    TextButton(onClick = onShowDeleteMany, enabled = selectedIds.isNotEmpty()) {
                        Text(stringResource(MR.strings.action_delete))
                    }
                }
            }
        }
        // KMK <--
        items(
            items = rowState,
            key = { it.category.key },
        ) { row ->
            ReorderableItem(reorderableState, row.category.key) {
                // KMK -->
                val itemModifier = if (row.isTopLevel) {
                    Modifier.animateItem()
                } else {
                    Modifier.animateItem().padding(start = 16.dp)
                }
                CategoryListItem(
                    modifier = itemModifier,
                    category = row.category,
                    onRename = { onClickRename(row.category) },
                    onDelete = { onClickDelete(row.category) },
                    onHide = { onClickHide(row.category) },
                    onCreateSubcategory = ({ onCreateSubcategory(row.category) }).takeIf { row.isTopLevel },
                    isTopLevel = row.isTopLevel,
                    subcategoryCount = if (row.isTopLevel) subMap[row.category.id]?.size ?: 0 else 0,
                    showDragHandle = reorderEnabled,
                    mangaCount = row.mangaCount,
                    expanded = (!row.collapsed).takeIf { row.isTopLevel && (subMap[row.category.id]?.size ?: 0) > 0 },
                    onToggleExpand = ({ onToggleCollapsed(row.category.id) }).takeIf { row.isTopLevel },
                    selected = (row.category.id in selectedIds).takeIf { selectMode },
                    onToggleSelection = ({ onToggleSelection(row.category.id) }).takeIf { selectMode },
                )
                // KMK <--
            }
        }
    }
}

// KMK -->
@Composable
private fun SortModeDropdown(sortMode: Int, onSortMode: (Int) -> Unit, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val labels = mapOf(
        CategoryManagerSort.MANUAL to stringResource(KMR.strings.category_manager_sort_manual),
        CategoryManagerSort.AZ to stringResource(KMR.strings.category_manager_sort_az),
        CategoryManagerSort.ZA to stringResource(KMR.strings.category_manager_sort_za),
        CategoryManagerSort.MOST_ITEMS to stringResource(KMR.strings.category_manager_sort_most),
        CategoryManagerSort.LEAST_ITEMS to stringResource(KMR.strings.category_manager_sort_least),
    )
    Box(modifier = modifier) {
        TextButton(onClick = { menuOpen = true }) {
            Text("${stringResource(KMR.strings.category_manager_sort)}: ${labels[sortMode]}")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            labels.forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        menuOpen = false
                        onSortMode(mode)
                    },
                )
            }
        }
    }
}
// KMK <--

private val Category.key inline get() = "category-$id"

// KMK -->
private data class CategoryRow(
    val category: Category,
    val isTopLevel: Boolean,
    val mangaCount: Int = 0,
    val collapsed: Boolean = false,
)
// KMK <--
