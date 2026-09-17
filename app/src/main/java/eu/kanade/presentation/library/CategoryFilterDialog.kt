package eu.kanade.presentation.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.DisabledByDefault
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.text.BidiFormatter
import eu.kanade.presentation.category.hierarchicalVisualName
import eu.kanade.presentation.category.sortedByHierarchy
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

private enum class FilterState {
    CHECKED,
    INVERSED,
    UNCHECKED,
}

/**
 * Tree-style category/subcategory filter: searchable, collapsible parents,
 * per-row item counts, tri-state include/exclude cycling. Selection state is
 * keyed by category id so collapsing, searching and scrolling never lose it.
 */
@Composable
fun CategoryFilterDialog(
    categories: List<Category>,
    initialChecked: List<Category>,
    initialInversed: List<Category>,
    itemCount: (Category) -> Int,
    onDismissRequest: () -> Unit,
    onValueChanged: (newIncluded: List<Category>, newExcluded: List<Category>) -> Unit,
    message: String? = null,
) {
    val states = remember(categories) {
        mutableStateMapOf<Long, FilterState>().apply {
            categories.forEach { category ->
                put(
                    category.id,
                    when (category) {
                        in initialChecked -> FilterState.CHECKED
                        in initialInversed -> FilterState.INVERSED
                        else -> FilterState.UNCHECKED
                    },
                )
            }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(setOf<Long>()) }

    val ordered = remember(categories) { categories.sortedByHierarchy() }
    val trimmed = query.trim()
    val rows = remember(ordered, trimmed, collapsed) {
        buildList {
            val byParent = ordered.filter { it.parentId != 0L }.groupBy { it.parentId }
            ordered.filter { it.parentId == 0L }.forEach { parent ->
                val subs = byParent[parent.id].orEmpty()
                if (trimmed.isEmpty()) {
                    val isCollapsed = parent.id in collapsed && subs.isNotEmpty()
                    add(FilterRow.Parent(parent, collapsed = isCollapsed, hasChildren = subs.isNotEmpty()))
                    if (!isCollapsed) {
                        subs.forEach { add(FilterRow.Child(it)) }
                    }
                } else {
                    val parentMatches = parent.name.contains(trimmed, ignoreCase = true)
                    val matching = subs.filter { it.name.contains(trimmed, ignoreCase = true) }
                    if (parentMatches || matching.isNotEmpty()) {
                        add(FilterRow.Parent(parent, collapsed = false, hasChildren = false))
                        (if (parentMatches) subs else matching).forEach { add(FilterRow.Child(it)) }
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.categories)) },
        text = {
            Column {
                if (message != null) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    placeholder = { Text(text = stringResource(KMR.strings.category_manager_search)) },
                    leadingIcon = { Icon(imageVector = Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                )
                Box {
                    val listState = rememberLazyListState()
                    LazyColumn(state = listState) {
                        items(rows, key = { it.category.id }) { row ->
                            val state = states[row.category.id] ?: FilterState.UNCHECKED
                            Row(
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        states[row.category.id] = when (state) {
                                            FilterState.UNCHECKED -> FilterState.CHECKED
                                            FilterState.CHECKED -> FilterState.INVERSED
                                            FilterState.INVERSED -> FilterState.UNCHECKED
                                        }
                                    }
                                    .defaultMinSize(minHeight = 48.dp)
                                    .fillMaxWidth()
                                    .padding(start = if (row is FilterRow.Child) 16.dp else 0.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (row is FilterRow.Parent && row.hasChildren) {
                                    IconButton(
                                        onClick = {
                                            collapsed = if (row.collapsed) {
                                                collapsed - row.category.id
                                            } else {
                                                collapsed + row.category.id
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = if (row.collapsed) {
                                                Icons.Outlined.ExpandMore
                                            } else {
                                                Icons.Outlined.ExpandLess
                                            },
                                            contentDescription = null,
                                        )
                                    }
                                }
                                Icon(
                                    modifier = Modifier.padding(end = 12.dp),
                                    imageVector = when (state) {
                                        FilterState.UNCHECKED -> Icons.Rounded.CheckBoxOutlineBlank
                                        FilterState.CHECKED -> Icons.Rounded.CheckBox
                                        FilterState.INVERSED -> Icons.Rounded.DisabledByDefault
                                    },
                                    tint = if (state == FilterState.UNCHECKED) {
                                        LocalContentColor.current
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    contentDescription = stringResource(
                                        when (state) {
                                            FilterState.UNCHECKED -> MR.strings.not_selected
                                            FilterState.CHECKED -> MR.strings.selected
                                            FilterState.INVERSED -> MR.strings.disabled
                                        },
                                    ),
                                )
                                val count = itemCount(row.category)
                                val label = BidiFormatter.getInstance().unicodeWrap(row.category.hierarchicalVisualName)
                                Text(
                                    text = if (count > 0) "$label ($count)" else label,
                                )
                            }
                        }
                    }

                    if (listState.canScrollBackward) HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                    if (listState.canScrollForward) HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val included = ordered.filter { states[it.id] == FilterState.CHECKED }
                    val excluded = ordered.filter { states[it.id] == FilterState.INVERSED }
                    onValueChanged(included, excluded)
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

private sealed interface FilterRow {
    val category: Category

    data class Parent(
        override val category: Category,
        val collapsed: Boolean,
        val hasChildren: Boolean,
    ) : FilterRow
    data class Child(override val category: Category) : FilterRow
}
