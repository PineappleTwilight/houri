package eu.kanade.tachiyomi.ui.browse.feed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.AnizenMultiFeedScreen
import eu.kanade.presentation.browse.FeedAddDialog
import eu.kanade.presentation.browse.FeedAddSearchDialog
import eu.kanade.presentation.browse.FeedOrderScreen
import eu.kanade.presentation.browse.FeedScreen
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.browse.components.FeedActionsDialog
import eu.kanade.presentation.browse.components.SourceFeedDeleteDialog
import eu.kanade.presentation.browse.components.bulkSelectionButton
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.feedTab(
    // KMK -->
    screenModel: FeedScreenModel,
    bulkFavoriteScreenModel: BulkFavoriteScreenModel,
    multiFeedScreenModel: AnizenMultiFeedScreenModel,
    // KMK <--
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val state by screenModel.state.collectAsState()

    // KMK -->
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()
    val showingFeedOrderScreen = rememberSaveable { mutableStateOf(false) }
    val multiState by multiFeedScreenModel.state.collectAsState()
    val multiAvailable = multiState.tabs.isNotEmpty()
    val showingMultiFeed = rememberSaveable(multiAvailable) { mutableStateOf(false) }
    if (!multiAvailable && showingMultiFeed.value) showingMultiFeed.value = false

    val multiPagerState = rememberPagerState { multiState.tabs.size }
    LaunchedEffect(multiState.selectedIndex, multiState.tabs.size) {
        val target = multiState.selectedIndex.coerceIn(0, (multiState.tabs.size - 1).coerceAtLeast(0))
        if (multiState.tabs.isNotEmpty() && multiPagerState.currentPage != target) {
            multiPagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(multiPagerState.currentPage) {
        multiFeedScreenModel.selectTab(multiPagerState.currentPage)
    }

    val haptic = LocalHapticFeedback.current

    LaunchedEffect(bulkFavoriteState.selectionMode) {
        HomeScreen.showBottomNav(!bulkFavoriteState.selectionMode)
    }
    // KMK <--

    DisposableEffect(navigator.lastEvent) {
        if (navigator.lastEvent == StackEvent.Push) {
            screenModel.pushed = true
        } else if (!screenModel.pushed) {
            screenModel.init()
        }

        onDispose {
            if (navigator.lastEvent == StackEvent.Idle && screenModel.pushed) {
                screenModel.pushed = false
            }
        }
    }

    return TabContent(
        titleRes = SYMR.strings.feed,
        actions =
        // KMK -->
        if (showingFeedOrderScreen.value) {
            persistentListOf(
                AppBar.Action(
                    title = stringResource(KMR.strings.action_sort_feed),
                    icon = Icons.Outlined.Close,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { showingFeedOrderScreen.value = false },
                ),
            )
        } else if (multiAvailable && showingMultiFeed.value) {
            persistentListOf(
                AppBar.Action(
                    title = stringResource(KMR.strings.action_refresh),
                    icon = Icons.Outlined.Refresh,
                    onClick = multiFeedScreenModel::refresh,
                ),
                bulkSelectionButton(
                    isRunning = bulkFavoriteState.isRunning,
                    toggleSelectionMode = bulkFavoriteScreenModel::toggleSelectionMode,
                ),
            )
        } else {
            // KMK <--
            persistentListOf(
                AppBar.Action(
                    title = stringResource(MR.strings.action_add),
                    icon = Icons.Outlined.Add,
                    onClick = {
                        screenModel.openAddDialog()
                    },
                ),
                // KMK -->
                AppBar.Action(
                    title = stringResource(KMR.strings.action_sort_feed),
                    icon = Icons.Outlined.SwapVert,
                    onClick = { showingFeedOrderScreen.value = true },
                ),
                bulkSelectionButton(
                    isRunning = bulkFavoriteState.isRunning,
                    toggleSelectionMode = bulkFavoriteScreenModel::toggleSelectionMode,
                ),
                // KMK <--
            )
        },
        content = { contentPadding, snackbarHostState ->
            // KMK -->
            BackHandler(enabled = bulkFavoriteState.selectionMode || showingFeedOrderScreen.value || showingMultiFeed.value) {
                when {
                    bulkFavoriteState.selectionMode -> bulkFavoriteScreenModel.backHandler()
                    showingFeedOrderScreen.value -> showingFeedOrderScreen.value = false
                    showingMultiFeed.value -> showingMultiFeed.value = false
                }
            }
            Column(modifier = Modifier.fillMaxSize()) {
                if (multiAvailable && !showingFeedOrderScreen.value) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !showingMultiFeed.value,
                            onClick = { showingMultiFeed.value = false },
                            label = { Text(stringResource(SYMR.strings.feed)) },
                        )
                        FilterChip(
                            selected = showingMultiFeed.value,
                            onClick = { showingMultiFeed.value = true },
                            label = { Text(stringResource(KMR.strings.anizen_multi_feed)) },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    Crossfade(
                        targetState = showingFeedOrderScreen.value,
                        label = "feed_order_crossfade",
                    ) { showingFeedOrderScreen ->
                        if (showingFeedOrderScreen) {
                            FeedOrderScreen(
                                state = state,
                                onClickDelete = screenModel::openDeleteDialog,
                                onChangeOrder = screenModel::changeOrder,
                            )
                        } else if (multiAvailable && showingMultiFeed.value) {
                            AnizenMultiFeedScreen(
                                state = multiState,
                                pagerState = multiPagerState,
                                contentPadding = contentPadding,
                                onClickSavedSearch = { savedSearch, source ->
                                    multiFeedScreenModel.sourcePreferences.lastUsedSource().set(savedSearch.source)
                                    navigator.push(
                                        BrowseSourceScreen(
                                            source.id,
                                            listingQuery = null,
                                            savedSearch = savedSearch.id,
                                        ),
                                    )
                                },
                                onClickSource = { source ->
                                    multiFeedScreenModel.sourcePreferences.lastUsedSource().set(source.id)
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
                                onLongClickFeed = screenModel::openActionsDialog,
                                onRetryFeed = multiFeedScreenModel::retryTab,
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
                                getMangaState = { manga -> multiFeedScreenModel.getManga(initialManga = manga) },
                            )
                        } else {
                            FeedScreen(
                                state = state,
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
                                            // KMK -->
                                            listingQuery = if (!source.supportsLatest) {
                                                GetRemoteManga.QUERY_POPULAR
                                            } else {
                                                // KMK <--
                                                GetRemoteManga.QUERY_LATEST
                                            },
                                        ),
                                    )
                                },
                                // KMK -->
                                onLongClickFeed = screenModel::openActionsDialog,
                                onRetryFeed = screenModel::retryFeed,
                                // KMK <--
                                onClickManga = { manga ->
                                    // KMK -->
                                    if (bulkFavoriteState.selectionMode) {
                                        bulkFavoriteScreenModel.toggleSelection(manga)
                                    } else {
                                        // KMK <--
                                        navigator.push(MangaScreen(manga.id, true))
                                    }
                                },
                                // KMK -->
                                onLongClickManga = { manga ->
                                    if (!bulkFavoriteState.selectionMode) {
                                        bulkFavoriteScreenModel.addRemoveManga(manga, haptic)
                                    } else {
                                        navigator.push(MangaScreen(manga.id, true))
                                    }
                                },
                                selection = bulkFavoriteState.selection,
                                // KMK <--
                                onRefresh = screenModel::init,
                                getMangaState = { manga -> screenModel.getManga(initialManga = manga) },
                            )
                        }
                    }
                }
            }

            state.dialog?.let { dialog ->
                val onDismissRequest = screenModel::dismissDialog
                when (dialog) {
                    is FeedScreenModel.Dialog.AddFeed -> {
                        FeedAddDialog(
                            sources = dialog.options,
                            onDismiss = onDismissRequest,
                            onClickAdd = {
                                if (it != null) {
                                    screenModel.openAddSearchDialog(it)
                                }
                                onDismissRequest()
                            },
                        )
                    }
                    is FeedScreenModel.Dialog.AddFeedSearch -> {
                        FeedAddSearchDialog(
                            source = dialog.source,
                            savedSearches = dialog.options,
                            onDismiss = onDismissRequest,
                            onClickAdd = { source, savedSearch ->
                                screenModel.createFeed(source, savedSearch)
                                onDismissRequest()
                            },
                        )
                    }
                    is FeedScreenModel.Dialog.DeleteFeed -> {
                        SourceFeedDeleteDialog(
                            onDismissRequest = onDismissRequest,
                            deleteFeed = {
                                screenModel.deleteFeed(dialog.feed)
                                onDismissRequest()
                            },
                        )
                    }
                    // KMK -->
                    is FeedScreenModel.Dialog.FeedActions -> {
                        FeedActionsDialog(
                            feed = dialog.feedItem.feed,
                            title = dialog.feedItem.title,
                            onDismissRequest = onDismissRequest,
                            onClickRefresh = screenModel::refreshFeed,
                            onClickDelete = { screenModel.openDeleteDialog(it) },
                        )
                    }
                    // KMK <--
                }
            }

            // KMK -->
            BulkFavoriteDialogs(
                bulkFavoriteScreenModel = bulkFavoriteScreenModel,
                dialog = bulkFavoriteState.dialog,
            )
            // KMK <--

            val internalErrString = stringResource(MR.strings.internal_error)
            val tooManyFeedsString = stringResource(KMR.strings.too_many_in_feed)
            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        FeedScreenModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                        FeedScreenModel.Event.TooManyFeeds -> {
                            launch { snackbarHostState.showSnackbar(tooManyFeedsString) }
                        }
                    }
                }
            }
        },
    )
}
