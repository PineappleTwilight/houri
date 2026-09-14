package eu.kanade.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.feed.AnizenMultiFeedState
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

// KMK -->
/**
 * Anizen-style multi-feed tabs: a TabRow over global FeedSavedSearch rows with a
 * HorizontalPager page per feed. Each page reuses the FeedScreen row content
 * ([FeedItem]) so per-tab fetch/display stays FeedScreenModel-style.
 */
@Composable
fun AnizenMultiFeedScreen(
    state: AnizenMultiFeedState,
    pagerState: PagerState,
    contentPadding: PaddingValues,
    onClickSavedSearch: (SavedSearch, Source) -> Unit,
    onClickSource: (Source) -> Unit,
    onLongClickFeed: (FeedItemUI) -> Unit,
    onRetryFeed: (FeedItemUI) -> Unit,
    onClickManga: (Manga) -> Unit,
    onLongClickManga: (Manga) -> Unit,
    selection: List<Manga>,
    getMangaState: @Composable (Manga) -> State<Manga>,
) {
    when {
        state.isLoading -> LoadingScreen()
        state.isEmpty -> EmptyScreen(
            SYMR.strings.feed_tab_empty,
            modifier = Modifier.padding(contentPadding),
        )
        else -> {
            val items = state.tabs
            val scope = rememberCoroutineScope()
            Column(modifier = Modifier.fillMaxSize()) {
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage.coerceIn(0, items.size - 1),
                    modifier = Modifier.zIndex(1f),
                ) {
                    items.forEachIndexed { index, item ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { TabText(text = item.title) },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                HorizontalPager(
                    modifier = Modifier.fillMaxSize(),
                    state = pagerState,
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    val item = items[page]
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (item.savedSearch != null && item.source != null) {
                                        onClickSavedSearch(item.savedSearch, item.source)
                                    } else if (item.source != null) {
                                        onClickSource(item.source)
                                    } else {
                                        onLongClickFeed(item)
                                    }
                                }
                                .padding(
                                    horizontal = MaterialTheme.padding.medium,
                                    vertical = MaterialTheme.padding.small,
                                ),
                        ) {
                            Text(text = item.title, style = MaterialTheme.typography.titleMedium)
                            Text(text = item.subtitle, style = MaterialTheme.typography.bodySmall)
                        }
                        FeedItem(
                            item = item,
                            getMangaState = getMangaState,
                            onClickManga = onClickManga,
                            onLongClickManga = onLongClickManga,
                            selection = selection,
                            onRetryFeed = onRetryFeed,
                        )
                    }
                }
            }
        }
    }
}
// KMK <--
