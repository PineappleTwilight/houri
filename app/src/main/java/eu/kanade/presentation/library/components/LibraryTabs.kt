package eu.kanade.presentation.library.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import eu.kanade.presentation.category.visualName
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun LibraryTabs(
    categories: List<Category>,
    pagerState: PagerState,
    getItemCountForCategory: (Category) -> Int?,
    onTabItemClick: (Int) -> Unit,
) {
    val currentPageIndex = pagerState.currentPage.coerceAtMost(categories.lastIndex)
    Column(modifier = Modifier.zIndex(2f)) {
        PrimaryScrollableTabRow(
            selectedTabIndex = currentPageIndex,
            edgePadding = 0.dp,
            divider = {},
        ) {
            categories.forEachIndexed { index, category ->
                val isAchievements = category.id == eu.kanade.tachiyomi.ui.library.LibraryScreenModel.ACHIEVEMENTS_CATEGORY_ID
                val achievementsBadgeText = if (isAchievements) {
                    try {
                        val prefs = mihon.app.di.globalAppGraph.achievementPreferences
                        val raw = prefs.unlockedAchievements().get()
                        val unlockedSet = if (raw.isBlank()) emptySet() else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                        val total = tachiyomi.domain.achievement.model.Achievements.countable.size
                        val unlocked = unlockedSet.count { tachiyomi.domain.achievement.model.Achievements.forId(it)?.countsTowardsProgress == true }
                        "$unlocked/$total"
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                Tab(
                    selected = currentPageIndex == index,
                    onClick = { onTabItemClick(index) },
                    text = {
                        TabText(
                            text = category.visualName,
                            badgeCount = if (achievementsBadgeText == null) getItemCountForCategory(category) else null,
                            badgeText = achievementsBadgeText,
                        )
                    },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        HorizontalDivider()
    }
}

// KMK -->
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibrarySubcategoryTabs(
    subcategories: List<Category>,
    selectedSubcategoryId: Long?,
    onSelectSubcategory: (Long?) -> Unit,
    showAllChip: Boolean = true,
) {
    val visibleSubcategories = subcategories.filter { it.name.isNotBlank() }
    if (visibleSubcategories.isEmpty()) return

    // Hold-tap the "All" chip to collapse the row into a single "+" chip; tap "+" to expand.
    // Keyed on the visible subcategory list so the collapsed state resets when switching categories.
    var collapsed by rememberSaveable(visibleSubcategories) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.small, vertical = MaterialTheme.padding.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        if (collapsed) {
            SubcategoryChip(
                selected = false,
                onClick = { collapsed = false },
                label = "+",
            )
            return@Row
        }
        if (showAllChip) {
            SubcategoryChip(
                selected = selectedSubcategoryId == null,
                onClick = { onSelectSubcategory(null) },
                label = stringResource(MR.strings.all),
                onLongClick = { collapsed = true },
            )
        }
        visibleSubcategories.forEach { subcategory ->
            SubcategoryChip(
                selected = selectedSubcategoryId == subcategory.id,
                onClick = { onSelectSubcategory(subcategory.id) },
                label = subcategory.name,
            )
        }
    }
}

/**
 * Solid fills + check icon instead of default chip styling: hairline borders and
 * subtle tonal fills wash out on e-ink screens during refreshes (see todo.md).
 */
@Composable
private fun SubcategoryChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    onLongClick: (() -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = {},
        label = {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.SemiBold else null,
            )
        },
        modifier = if (onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
            Modifier
        },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            labelColor = MaterialTheme.colorScheme.onSurface,
            iconColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}
// KMK <--
