package eu.kanade.presentation.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.category.components.CategoryListItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.category.CategoryScreenState
import kotlinx.collections.immutable.ImmutableList
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
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
    // KMK <--
) {
    val topLevel = remember(categories) { categories.filter { it.parentId == 0L } }
    // KMK -->
    val subMap = remember(categories) {
        categories
            .filter { it.parentId != 0L }
            .groupBy { it.parentId }
            .mapValues { (_, subs) -> subs.sortedBy { it.order } }
    }

    // Single flat list so every row is an individually reorderable item
    val rows = remember(topLevel, subMap) {
        buildList {
            topLevel.forEach { parent ->
                add(CategoryRow(parent, isTopLevel = true))
                subMap[parent.id]?.forEach { add(CategoryRow(it, isTopLevel = false)) }
            }
        }
    }
    // KMK <--
    val rowState = remember { rows.toMutableStateList() }
    val reorderableState = rememberReorderableLazyListState(lazyListState, paddingValues) { from, to ->
        val moved = rowState.removeAt(from.index)
        rowState.add(to.index, moved)
        // KMK -->
        // ReorderCategory scopes updates to the moved row's sibling group, so
        // only the index within that group is relevant here
        val siblingIndex = if (moved.isTopLevel) {
            rowState.take(to.index).count { it.isTopLevel }
        } else {
            val parentId = moved.category.parentId
            rowState.take(to.index).count { !it.isTopLevel && it.category.parentId == parentId }
        }
        onChangeOrder(moved.category, siblingIndex.coerceAtLeast(0))
        // KMK <--
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
                )
                // KMK <--
            }
        }
    }
}

private val Category.key inline get() = "category-$id"

// KMK -->
private data class CategoryRow(val category: Category, val isTopLevel: Boolean)
// KMK <--
