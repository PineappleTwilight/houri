package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalHapticFeedback
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.AnizenMultiFeedScreen
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.browse.components.bulkSelectionButton
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

// KMK -->
/**
 * Anizen-style multi-feed tab: HorizontalPager + TabRow over global FeedSavedSearch rows.
 * Wired into BrowseTab alongside [feedTab]; glue-only (no DB migration, no source ABI change).
 */
@Composable
fun Screen.anizenMultiFeedTab(
    screenModel: AnizenMultiFeedScreenModel,
    bulkFavoriteScreenModel: BulkFavoriteScreenModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val state by screenModel.state.collectAsState()
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(bulkFavoriteState.selectionMode) {
        HomeScreen.showBottomNav(!bulkFavoriteState.selectionMode)
    }

    val pagerState = rememberPagerState { state.tabs.size }
    LaunchedEffect(state.selectedIndex, state.tabs.size) {
        val target = state.selectedIndex.coerceIn(0, (state.tabs.size - 1).coerceAtLeast(0))
        if (state.tabs.isNotEmpty() && pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        screenModel.selectTab(pagerState.currentPage)
    }

    return TabContent(
        titleRes = KMR.strings.anizen_multi_feed,
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(KMR.strings.action_refresh),
                icon = Icons.Outlined.Refresh,
                onClick = screenModel::refresh,
            ),
            bulkSelectionButton(
                isRunning = bulkFavoriteState.isRunning,
                toggleSelectionMode = bulkFavoriteScreenModel::toggleSelectionMode,
            ),
        ),
        content = { contentPadding, _ ->
            AnizenMultiFeedScreen(
                state = state,
                pagerState = pagerState,
                contentPadding = contentPadding,
                onClickSavedSearch = { savedSearch, source ->
                    screenModel.sourcePreferences.lastUsedSource().set(savedSearch.source)
                    navigator.push(
                        BrowseSourceScreen(
                            source.id,
                            listingQuery = null,
                            savedSearch = savedSearch.id,
                        ),
                    )
                },
                onClickSource = { source ->
                    screenModel.sourcePreferences.lastUsedSource().set(source.id)
                    navigator.push(
                        BrowseSourceScreen(
                            source.id,
                            listingQuery = if (!source.supportsLatest) {
                                GetRemoteManga.QUERY_POPULAR
                            } else {
                                GetRemoteManga.QUERY_LATEST
                            },
                        ),
                    )
                },
                onLongClickFeed = { /* per-tab actions stay in the feed tab sort screen */ },
                onRetryFeed = screenModel::retryTab,
                onClickManga = { manga ->
                    if (bulkFavoriteState.selectionMode) {
                        bulkFavoriteScreenModel.toggleSelection(manga)
                    } else {
                        navigator.push(MangaScreen(manga.id, true))
                    }
                },
                onLongClickManga = { manga ->
                    if (!bulkFavoriteState.selectionMode) {
                        bulkFavoriteScreenModel.addRemoveManga(manga, haptic)
                    } else {
                        navigator.push(MangaScreen(manga.id, true))
                    }
                },
                selection = bulkFavoriteState.selection,
                getMangaState = { manga -> screenModel.getManga(initialManga = manga) },
            )

            BulkFavoriteDialogs(
                bulkFavoriteScreenModel = bulkFavoriteScreenModel,
                dialog = bulkFavoriteState.dialog,
            )
        },
    )
}
// KMK <--
