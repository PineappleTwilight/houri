package eu.kanade.presentation.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    // KMK <--
    val topLevelState = remember { topLevel.toMutableStateList() }
    val reorderableState = rememberReorderableLazyListState(lazyListState, paddingValues) { from, to ->
        val item = topLevelState.removeAt(from.index)
        topLevelState.add(to.index, item)
        onChangeOrder(item, to.index)
    }

    LaunchedEffect(topLevel) {
        if (!reorderableState.isAnyItemDragging) {
            topLevelState.clear()
            topLevelState.addAll(topLevel)
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
            items = topLevelState,
            key = { category -> category.key },
        ) { category ->
            ReorderableItem(reorderableState, category.key) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CategoryListItem(
                        modifier = Modifier.animateItem(),
                        category = category,
                        onRename = { onClickRename(category) },
                        onDelete = { onClickDelete(category) },
                        onHide = { onClickHide(category) },
                        onCreateSubcategory = { onCreateSubcategory(category) },
                        isTopLevel = true,
                        subcategoryCount = subMap[category.id]?.size ?: 0,
                    )
                    val subs = subMap[category.id] ?: emptyList()
                    if (subs.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(start = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            subs.forEachIndexed { subIndex, sub ->
                                CategoryListItem(
                                    category = sub,
                                    onRename = { onClickRename(sub) },
                                    onDelete = { onClickDelete(sub) },
                                    onHide = { onClickHide(sub) },
                                    // KMK -->
                                    onMoveUp = if (subIndex > 0) {
                                        { onChangeOrder(sub, subIndex - 1) }
                                    } else {
                                        null
                                    },
                                    onMoveDown = if (subIndex < subs.lastIndex) {
                                        { onChangeOrder(sub, subIndex + 1) }
                                    } else {
                                        null
                                    },
                                    isTopLevel = false,
                                    // KMK <--
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val Category.key inline get() = "category-$id"
