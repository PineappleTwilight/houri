package eu.kanade.tachiyomi.ui.manga

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEventBus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.materialkolor.ktx.blend
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.MangaScreen
import eu.kanade.presentation.manga.components.ClearMangaDialog
import eu.kanade.presentation.manga.components.CoverCropDialog
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.presentation.manga.components.MangaReadingStatsDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.manga.components.SetIntervalDialog
import eu.kanade.presentation.manga.components.StartRereadDialog
import eu.kanade.presentation.manga.components.StopRereadDialog
import eu.kanade.presentation.more.settings.screen.SettingsEhRoute
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsRoute
import eu.kanade.tachiyomi.ui.browse.source.SmartSearchConfig
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSearchEventKey
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSearchGenreEventKey
import eu.kanade.tachiyomi.ui.home.LibrarySearchEventKey
import eu.kanade.tachiyomi.ui.manga.merged.EditMergedSettingsDialog
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.addSettingsRoute
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import exh.recs.RecommendsRoute
import exh.source.anyIs
import exh.source.getMainSource
import exh.source.isEhBasedSource
import exh.source.isMergedSourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.CategoryRoute
import mihon.core.navigation.GlobalSearchRoute
import mihon.core.navigation.HomeRoute
import mihon.core.navigation.LibrarySearchRoute
import mihon.core.navigation.MangaNotesRoute
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.MetadataViewRoute
import mihon.core.navigation.MigrationConfigRoute
import mihon.core.navigation.PagePreviewRoute
import mihon.core.navigation.PaletteRoute
import mihon.core.navigation.SettingsRoute
import mihon.core.navigation.SmartSearchRoute
import mihon.core.navigation.SmartSearchSourcesRoute
import mihon.core.navigation.SourceFeedRoute
import mihon.core.navigation.SourcePreferencesRoute
import mihon.core.navigation.TrackInfoDialogRoute
import mihon.core.navigation.WebViewRoute
import mihon.core.navigation.domain.SettingsDestination
import mihon.core.navigation.util.LocalAssistContentManager
import mihon.core.navigation.util.LocalBackStack
import mihon.core.navigation.util.popUntil
import mihon.core.navigation.util.replace
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.isTabletUi
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.isLocal

@Composable
fun MangaScreen(
    mangaId: Long,
    /**
     * If it is opened from Source then it will auto expand the manga description.
     * - `true`: Expand description if it's not favorited
     * - `false`: Don't expand description
     */
    fromSource: Boolean = false,
    // SY --> smart search ("find in another source") continuity
    smartSearchOrigTitle: String? = null,
    smartSearchOrigMangaId: Long? = null,
    // SY <--
) {
    val smartSearchConfig = smartSearchOrigTitle?.let { SmartSearchConfig(it, smartSearchOrigMangaId) }
    val assistContentManager = LocalAssistContentManager.current

    if (!ifSourcesLoaded()) {
        androidx.compose.animation.Crossfade(targetState = true, label = "sourcesLoading") { _ ->
            if (isTabletUi()) {
                tachiyomi.presentation.core.components.MangaDetailTabletShimmer()
            } else {
                tachiyomi.presentation.core.components.MangaDetailShimmer()
            }
        }
        return
    }

    val backStack = LocalBackStack.current
    val resultEventBus = LocalResultEventBus.current
    val isTabletUi = isTabletUi()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenModel = assistedMetroViewModel<MangaViewModel, MangaViewModel.Factory> {
        create(
            context = context,
            mangaId = mangaId,
            isFromSource = fromSource,
            smartSearched = smartSearchConfig != null,
        )
    }

    val state by screenModel.state.collectAsStateWithLifecycle()

    if (state is MangaViewModel.State.Loading) {
        androidx.compose.animation.Crossfade(targetState = true, label = "mangaLoading") { _ ->
            if (isTabletUi()) {
                tachiyomi.presentation.core.components.MangaDetailTabletShimmer()
            } else {
                tachiyomi.presentation.core.components.MangaDetailShimmer()
            }
        }
        return
    }

    val successState = state as MangaViewModel.State.Success

    // KMK -->
    val bulkFavoriteScreenModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

    val showingRelatedMangasScreen = rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = bulkFavoriteState.selectionMode || showingRelatedMangasScreen.value) {
        when {
            bulkFavoriteState.selectionMode -> bulkFavoriteScreenModel.backHandler()
            showingRelatedMangasScreen.value -> showingRelatedMangasScreen.value = false
        }
    }

    val content = @Composable {
        Crossfade(
            targetState = showingRelatedMangasScreen.value,
            label = "manga_related_crossfade",
        ) { showRelatedMangasScreen ->
            when (showRelatedMangasScreen) {
                true -> RelatedMangasScreen(
                    screenModel = screenModel,
                    successState = successState,
                    bulkFavoriteScreenModel = bulkFavoriteScreenModel,
                    navigateUp = { showingRelatedMangasScreen.value = false },
                    backStack = backStack,
                    resultEventBus = resultEventBus,
                    scope = scope,
                )
                false -> MangaDetailContent(
                    context = context,
                    assistContentManager = assistContentManager,
                    screenModel = screenModel,
                    successState = successState,
                    bulkFavoriteScreenModel = bulkFavoriteScreenModel,
                    showRelatedMangasScreen = { showingRelatedMangasScreen.value = true },
                    backStack = backStack,
                    resultEventBus = resultEventBus,
                    isTabletUi = isTabletUi,
                    smartSearchConfig = smartSearchConfig,
                    scope = scope,
                )
            }
        }
    }

    val seedColor = successState.seedColor
    TachiyomiTheme(
        seedColor = seedColor.takeIf { screenModel.themeCoverBased },
    ) {
        content()
    }

    BulkFavoriteDialogs(
        bulkFavoriteScreenModel = bulkFavoriteScreenModel,
        dialog = bulkFavoriteState.dialog,
    )
}

@Composable
private fun MangaDetailContent(
    context: Context,
    assistContentManager: mihon.core.navigation.util.AssistContentManager,
    screenModel: MangaViewModel,
    successState: MangaViewModel.State.Success,
    bulkFavoriteScreenModel: BulkFavoriteViewModel,
    showRelatedMangasScreen: () -> Unit,
    backStack: androidx.navigation3.runtime.NavBackStack<androidx.navigation3.runtime.NavKey>,
    resultEventBus: androidx.navigation3.runtime.result.ResultEventBus,
    isTabletUi: Boolean,
    smartSearchConfig: SmartSearchConfig?,
    scope: CoroutineScope,
) {
    // KMK <--
    val haptic = LocalHapticFeedback.current
    val isHttpSource = remember { successState.source is HttpSource }

    LaunchedEffect(successState.manga, screenModel.source) {
        if (isHttpSource) {
            try {
                withIOContext {
                    assistContentManager.currentAssistUrl = getMangaUrl(screenModel.manga, screenModel.source)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to get manga URL" }
            }
        }
    }

    // SY -->
    LaunchedEffect(Unit) {
        screenModel.redirectFlow
            .take(1)
            .onEach {
                backStack.replace(
                    MangaRoute(it.mangaId),
                )
            }
            .launchIn(this)
    }
    // SY <--

    // KMK -->
    val coverRatio = remember { mutableFloatStateOf(1f) }
    val hazeState = remember { HazeState() }
    val fullCoverBackground = MaterialTheme.colorScheme.surfaceTint.blend(MaterialTheme.colorScheme.surface)

    val isHentaiEnabled: Boolean = globalAppGraph.exhPreferences.isHentaiEnabled().get()
    val isConfigurableSource = successState.source.anyIs<ConfigurableSource>() ||
        (successState.source.isEhBasedSource() && isHentaiEnabled)
    // KMK <--

    MangaScreen(
        state = successState,
        snackbarHostState = screenModel.snackbarHostState,
        nextUpdate = successState.manga.expectedNextUpdate,
        isTabletUi = isTabletUi(),
        chapterSwipeStartAction = screenModel.chapterSwipeStartAction,
        chapterSwipeEndAction = screenModel.chapterSwipeEndAction,
        navigateUp = backStack::removeLastOrNull,
        onChapterClicked = { openChapter(context, it) },
        onDownloadChapter = screenModel::runChapterDownloadActions.takeIf { !successState.source.isLocalOrStub() },
        onAddToLibraryClicked = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            screenModel.toggleFavorite()
        },
        // SY -->
        onWebViewClicked = {
            if (successState.mergedData == null) {
                openMangaInWebView(
                    backStack,
                    screenModel.manga,
                    screenModel.source,
                )
            } else {
                mergedMangaAction(
                    context,
                    backStack,
                    successState.mergedData,
                    // KMK -->
                    action = { _, nav, manga, source -> openMangaInWebView(nav, manga, source) },
                    titleRes = MR.strings.action_open_in_web_view,
                    // KMK <--
                )
            }
        }.takeIf { isHttpSource },
        // SY <--
        onWebViewLongClicked = {
            // KMK -->
            if (successState.mergedData == null) {
                // KMK <--
                copyMangaUrl(
                    context,
                    screenModel.manga,
                    screenModel.source,
                )
                // KMK -->
            } else {
                mergedMangaAction(
                    context,
                    backStack,
                    successState.mergedData,
                    action = { ctx, _, manga, source -> copyMangaUrl(ctx, manga, source) },
                    titleRes = MR.strings.action_copy_link,
                )
                // KMK <--
            }
        }.takeIf { isHttpSource },
        onTrackingClicked = {
            if (!successState.hasLoggedInTrackers) {
                backStack.addSettingsRoute(SettingsRoute(SettingsDestination.Tracking), isTabletUi)
            } else {
                screenModel.showTrackDialog()
            }
        },
        onTagSearch = { scope.launch { performGenreSearch(backStack, resultEventBus, it, screenModel.source!!) } },
        onFilterButtonClicked = screenModel::showSettingsDialog,
        onRefresh = screenModel::fetchAllFromSource,
        onContinueReading = { continueReading(context, screenModel.getNextUnreadChapter()) },
        onSearch = { query, global -> scope.launch { performSearch(backStack, resultEventBus, query, global) } },
        // KMK -->
        librarySearch = { query ->
            scope.launch { performSearch(backStack, resultEventBus, query, global = false, library = true) }
        },
        // KMK <--
        onCoverClicked = screenModel::showCoverDialog,
        onShareClicked = {
            // KMK -->
            if (successState.mergedData == null) {
                // KMK <--
                shareManga(context, screenModel.manga, screenModel.source)
                // KMK -->
            } else {
                mergedMangaAction(
                    context,
                    backStack,
                    successState.mergedData,
                    action = { ctx, _, manga, source -> shareManga(ctx, manga, source) },
                    titleRes = MR.strings.action_share,
                )
                // KMK <--
            }
        }.takeIf { isHttpSource },
        onDownloadActionClicked = screenModel::runDownloadAction.takeIf { !successState.source.isLocalOrStub() },
        onEditCategoryClicked = screenModel::showChangeCategoryDialog.takeIf { successState.manga.favorite },
        onEditFetchIntervalClicked = screenModel::showSetFetchIntervalDialog.takeIf {
            successState.manga.favorite
        },
        onMigrateClicked = {
            backStack.add(MigrationConfigRoute(successState.manga.id))
            Unit
        }.takeIf { successState.manga.favorite },
        // SY -->
        previewsRowCount = successState.previewsRowCount,
        onMetadataViewerClicked = {
            openMetadataViewer(
                backStack,
                successState.manga,
                // KMK -->
                successState.seedColor,
                // KMK <--
            )
        },
        onEditInfoClicked = screenModel::showEditMangaInfoDialog,
        onRecommendClicked = {
            openRecommends(backStack, screenModel.source?.getMainSource(), successState.manga)
        },
        onMergedSettingsClicked = screenModel::showEditMergedSettingsDialog,
        onMergeClicked = { openSmartSearch(backStack, successState.manga) },
        onMergeWithAnotherClicked = {
            mergeWithAnother(backStack, context, successState.manga, smartSearchConfig, screenModel::smartSearchMerge)
        },
        onOpenPagePreview = { page ->
            openPagePreview(context, successState.chapters.minByOrNull { it.chapter.sourceOrder }?.chapter, page)
        },
        onMorePreviewsClicked = { openMorePagePreviews(backStack, successState.manga) },
        // SY <--
        onEditNotesClicked = { backStack.add(MangaNotesRoute(manga = successState.manga)) },
        // KMK -->
        onReadingStatsClicked = screenModel::showReadingStatsDialog,
        // KMK <--
        onMultiBookmarkClicked = screenModel::bookmarkChapters,
        onMultiMarkAsReadClicked = screenModel::markChaptersRead,
        onMarkPreviousAsReadClicked = screenModel::markPreviousChapterRead,
        onMultiDeleteClicked = screenModel::showDeleteChapterDialog,
        // KMK -->
        onBlacklistClicked = screenModel::blacklistChapters,
        onSetBookmarkColor = screenModel::setBookmarkColor,
        // KMK <--
        onChapterSwipe = screenModel::chapterSwipe,
        onChapterSelected = screenModel::toggleSelection,
        onAllChapterSelected = screenModel::toggleAllSelection,
        onInvertSelection = screenModel::invertSelection,
        // KMK -->
        getMangaState = { screenModel.getManga(initialManga = it) },
        onClickSourceSettingsClicked = {
            when {
                successState.source.isEhBasedSource() && isHentaiEnabled ->
                    backStack.add(SettingsEhRoute)
                successState.source.anyIs<ConfigurableSource>() ->
                    backStack.add(SourcePreferencesRoute(successState.source.id))
                else -> {}
            }
            Unit
        }.takeIf { isConfigurableSource },
        onClearManga = { screenModel.showClearMangaDialog() },
        onOpenMangaFolder = {
            if (successState.mergedData == null) {
                screenModel.openMangaFolder(screenModel.source, screenModel.manga)
            } else {
                mergedMangaAction(
                    context,
                    backStack,
                    successState.mergedData,
                    action = { _, _, manga, source -> screenModel.openMangaFolder(source, manga) },
                    titleRes = KMR.strings.action_open_folder,
                )
            }
        }
            .takeIf { successState.source !is StubSource },
        onRelatedMangasScreenClick = {
            if (successState.isRelatedMangasFetched == null) {
                scope.launchIO { screenModel.fetchRelatedMangasFromSource(onDemand = true) }
            }
            showRelatedMangasScreen()
        },
        onRelatedMangaClick = { backStack.add(MangaRoute(it.id, true)) },
        onRelatedMangaLongClick = { bulkFavoriteScreenModel.addRemoveManga(it, haptic) },
        // KMK -->
        onSequelPrequelClick = { entry ->
            // KMK --> web novels are unreadable in the reader; open in browser directly.
            if (entry.isWebNovel) {
                entry.url.takeIf { it.startsWith("http") }?.let { context.openInBrowser(it) }
                // KMK --> stubs that aren't in the library go straight to a global
                // search for the title instead of opening an add-to-library stub screen.
            } else if (entry.title.lowercase() !in successState.sequelPrequelLibraryTitles) {
                backStack.add(GlobalSearchRoute(entry.title))
                // KMK <--
            } else {
                scope.launch {
                    screenModel.resolveSequelPrequel(entry)?.let { backStack.add(MangaRoute(it, true)) }
                        // Tracker-sourced entries (e.g. AniList siteUrl) don't resolve
                        // in-source: open them in the browser instead of no-op.
                        ?: entry.url.takeIf { it.startsWith("http") }?.let { context.openInBrowser(it) }
                }
            }
        },
        // KMK <--
        onSourceClick = {
            if (successState.source !is StubSource) {
                // KMK -->
                if (successState.mergedData == null) {
                    screenModel.source?.let { browseSource(backStack, smartSearchConfig, it, screenModel.useNewSourceNavigation) }
                } else {
                    mergedMangaAction(
                        context,
                        backStack,
                        successState.mergedData,
                        action = { _, nav, _, source ->
                            source?.let {
                                browseSource(nav, smartSearchConfig, it, screenModel.useNewSourceNavigation)
                            }
                        },
                        titleRes = MR.strings.browse,
                    )
                }
                // KMK <--
            } else {
                backStack.add(ExtensionsRoute(searchSource = successState.source.name))
            }
        },
        onCoverLoaded = { mangaCover, image -> screenModel.onCoverPaletteAvailable(mangaCover, image) },
        coverRatio = coverRatio,
        onPaletteScreenClick = { backStack.add(PaletteRoute(successState.seedColor?.toArgb())) },
        onStartRereadingClick = screenModel::showStartRereadDialog.takeIf { successState.manga.favorite },
        onStopRereadingClick = screenModel::showStopRereadDialog.takeIf { successState.manga.favorite },
        // KMK --> The escape hatch for a detection heuristic that guessed wrong. Only offered
        // where the question exists: an `.epub` in a local library, in the library already.
        onToggleEntryTypeClick = screenModel::toggleEntryType.takeIf {
            successState.manga.favorite && successState.manga.isLocal()
        },
        hazeState = hazeState,
        // KMK --> Integrated metadata translation actions come from the model-owned controller.
        onMangaInfoEnabledChange = screenModel::setMangaInfoEnabled,
        onMangaInfoShowTranslatedChange = screenModel::setMangaInfoShowTranslated,
        onMangaInfoRefresh = screenModel::refreshMangaInfo,
        onMangaInfoRetry = screenModel::retryMangaInfo,
        onMangaInfoReset = screenModel::resetMangaInfo,
        // KMK <--
    )

    var showScanlatorsDialog by remember { mutableStateOf(false) }

    val onDismissRequest = {
        screenModel.dismissDialog()
        // KMK -->
        if (screenModel.autoOpenTrack && screenModel.showTrackDialogAfterCategorySelection) {
            screenModel.showTrackDialogAfterCategorySelection = false
            if (successState.manga.favorite) screenModel.showTrackDialog()
        }
        // KMK <--
    }
    when (val dialog = successState.dialog) {
        null -> {}
        is MangaViewModel.Dialog.ChangeCategory -> {
            ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = onDismissRequest,
                onEditCategories = { backStack.add(CategoryRoute) },
                onConfirm = { include, _ ->
                    screenModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                },
            )
        }
        is MangaViewModel.Dialog.DeleteChapters -> {
            DeleteChaptersDialog(
                onDismissRequest = onDismissRequest,
                onConfirm = {
                    screenModel.toggleAllSelection(false)
                    screenModel.deleteChapters(dialog.chapters)
                },
            )
        }

        is MangaViewModel.Dialog.DuplicateManga -> {
            DuplicateMangaDialog(
                duplicates = dialog.duplicates,
                onDismissRequest = onDismissRequest,
                onConfirm = { screenModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                onOpenManga = { backStack.add(MangaRoute(it.id)) },
                onMigrate = { screenModel.showMigrateDialog(it) },
                // KMK -->
                targetManga = dialog.manga,
                // KMK <--
            )
        }

        is MangaViewModel.Dialog.Migrate -> {
            MigrateMangaDialog(
                current = dialog.current,
                target = dialog.target,
                // Initiated from the context of [dialog.target] so we show [dialog.current].
                onClickTitle = { backStack.add(MangaRoute(dialog.current.id)) },
                onDismissRequest = onDismissRequest,
            )
        }
        MangaViewModel.Dialog.SettingsSheet -> ChapterSettingsDialog(
            onDismissRequest = onDismissRequest,
            manga = successState.manga,
            onDownloadFilterChanged = screenModel::setDownloadedFilter,
            onUnreadFilterChanged = screenModel::setUnreadFilter,
            onBookmarkedFilterChanged = screenModel::setBookmarkedFilter,
            // KMK -->
            bookmarkColorFilter = successState.bookmarkColorFilter,
            onBookmarkColorFilterChanged = screenModel::setBookmarkColorFilter,
            // KMK <--
            onSortModeChanged = screenModel::setSorting,
            onDisplayModeChanged = screenModel::setDisplayMode,
            onSetAsDefault = screenModel::setCurrentSettingsAsDefault,
            onResetToDefault = screenModel::resetToDefaultSettings,
            scanlatorFilterActive = successState.scanlatorFilterActive,
            onScanlatorFilterClicked = { showScanlatorsDialog = true },
        )
        MangaViewModel.Dialog.TrackSheet -> {
            // KMK --> The track sheet is a Nav3 overlay destination now (OverlaySheetScene);
            // surface the request as a route push instead of a nested Voyager navigator.
            LaunchedEffect(Unit) {
                screenModel.dismissDialog()
                backStack.add(
                    TrackInfoDialogRoute(
                        mangaId = successState.manga.id,
                        mangaTitle = successState.manga.title,
                        sourceId = successState.source.id,
                    ),
                )
            }
            // KMK <--
        }
        MangaViewModel.Dialog.FullCover -> {
            val sm = assistedMetroViewModel<MangaCoverViewModel, MangaCoverViewModel.Factory> { create(successState.manga.id) }
            val manga by sm.state.collectAsState()
            // KMK -->
            var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
            // KMK <--
            manga?.let { currentManga ->
                val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
                    if (it == null) return@rememberLauncherForActivityResult
                    // KMK -->
                    pendingCropUri = it
                    // KMK <--
                }
                // KMK -->
                val externalStoragePermissionNotGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_DENIED
                val saveCoverPermissionRequester = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = {
                        sm.saveCover(context)
                    },
                )
                // KMK <--
                MangaCoverDialog(
                    manga = currentManga,
                    snackbarHostState = sm.snackbarHostState,
                    isCustomCover = remember(currentManga) { currentManga.hasCustomCover() },
                    onShareClick = { sm.shareCover(context) },
                    onSaveClick = {
                        // KMK -->
                        if (externalStoragePermissionNotGranted) {
                            saveCoverPermissionRequester.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            // KMK <--
                            sm.saveCover(context)
                        }
                    },
                    onEditClick = {
                        when (it) {
                            EditCoverAction.EDIT -> getContent.launch("image/*")
                            EditCoverAction.DELETE -> sm.deleteCustomCover(context)
                        }
                    },
                    onDismissRequest = onDismissRequest,
                    // KMK -->
                    modifier = Modifier
                        .hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = Color.Transparent,
                                tint = HazeDefaults.tint(fullCoverBackground),
                                blurRadius = 10.dp,
                            ),
                        ),
                    // KMK <--
                )
                // KMK -->
                pendingCropUri?.let { source ->
                    CoverCropDialog(
                        sourceUri = source,
                        onDismissRequest = { pendingCropUri = null },
                        onCropped = { cropped ->
                            pendingCropUri = null
                            sm.editCover(context, cropped) { screenModel.refreshManga() }
                            // KMK --> Only onCropped, not onUseOriginal: choosing the
                            // original is not a crop.
                            runCatching {
                                globalAppGraph.achievementManager.tryUnlockDirect("cover_cropped")
                            }
                        },
                        onUseOriginal = {
                            pendingCropUri = null
                            sm.editCover(context, source) { screenModel.refreshManga() }
                        },
                    )
                }
                // KMK <--
            } ?: run {
                LoadingScreen(Modifier.systemBarsPadding())
            }
        }
        is MangaViewModel.Dialog.SetFetchInterval -> {
            SetIntervalDialog(
                interval = dialog.manga.fetchInterval,
                nextUpdate = dialog.manga.expectedNextUpdate,
                onDismissRequest = onDismissRequest,
                onValueChanged = { interval: Int -> screenModel.setFetchInterval(dialog.manga, interval) }
                    .takeIf { screenModel.isUpdateIntervalEnabled },
            )
        }
        // KMK -->
        is MangaViewModel.Dialog.ReadingStats -> {
            MangaReadingStatsDialog(
                totalReadDuration = dialog.totalReadDuration,
                onDismissRequest = onDismissRequest,
            )
        }
        // KMK <--
        // SY -->
        is MangaViewModel.Dialog.EditMangaInfo -> {
            EditMangaDialog(
                manga = dialog.manga,
                // KMK -->
                coverRatio = coverRatio,
                // KMK <--
                onDismissRequest = screenModel::dismissDialog,
                onPositiveClick = screenModel::updateMangaInfo,
            )
        }

        is MangaViewModel.Dialog.EditMergedSettings -> {
            EditMergedSettingsDialog(
                mergedData = dialog.mergedData,
                onDismissRequest = screenModel::dismissDialog,
                onDeleteClick = screenModel::deleteMerge,
                onPositiveClick = screenModel::updateMergeSettings,
                // KMK -->
                onOpenEntryClick = { merge ->
                    merge.mangaId?.let { backStack.add(MangaRoute(it)) }
                },
                // KMK <--
            )
        }
        // SY <--
        // KMK -->
        is MangaViewModel.Dialog.ClearManga -> {
            ClearMangaDialog(
                onDismissRequest = onDismissRequest,
                onConfirm = screenModel::clearManga,
            )
        }
        // KMK -->
        is MangaViewModel.Dialog.ConfirmStartReread -> {
            StartRereadDialog(
                rereadCount = successState.manga.rereadCount,
                onDismissRequest = onDismissRequest,
                onConfirm = screenModel::startRereading,
            )
        }
        is MangaViewModel.Dialog.ConfirmStopReread -> {
            StopRereadDialog(
                onDismissRequest = onDismissRequest,
                onConfirm = screenModel::stopRereading,
            )
        }
        // KMK <--
    }

    if (showScanlatorsDialog) {
        ScanlatorFilterDialog(
            availableScanlators = successState.availableScanlators,
            excludedScanlators = successState.excludedScanlators,
            onDismissRequest = { showScanlatorsDialog = false },
            onConfirm = screenModel::setExcludedScanlators,
        )
    }
}

private fun continueReading(context: Context, unreadChapter: Chapter?) {
    if (unreadChapter != null) openChapter(context, unreadChapter)
}

@Volatile private var lastOpenChapterTime: Long = 0L

@Volatile private var lastMangaNavTime: Long = 0L
private fun NavBackStack<NavKey>.safePush(route: NavKey) {
    val now = System.currentTimeMillis()
    if (now - lastMangaNavTime < 90) return
    lastMangaNavTime = now
    add(route)
}
private fun NavBackStack<NavKey>.safeReplace(route: NavKey) {
    val now = System.currentTimeMillis()
    if (now - lastMangaNavTime < 90) return
    lastMangaNavTime = now
    replace(route)
}
private fun openChapter(context: Context, chapter: Chapter) {
    val now = System.currentTimeMillis()
    if (now - lastOpenChapterTime < 200) return
    lastOpenChapterTime = now
    context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
}

@Suppress("LocalVariableName")
private fun getMangaUrl(manga_: Manga?, source_: Source?): String? {
    val manga = manga_ ?: return null
    val source = source_ as? HttpSource ?: return null

    return try {
        source.getMangaUrl(manga.toSManga())
    } catch (_: Exception) {
        null
    }
}

@Suppress("LocalVariableName")
private fun openMangaInWebView(backStack: NavBackStack<NavKey>, manga_: Manga?, source_: Source?) {
    getMangaUrl(manga_, source_)?.let { url ->
        backStack.add(
            WebViewRoute(
                url = url,
                initialTitle = manga_?.title,
                sourceId = source_?.id,
            ),
        )
    }
}

@Suppress("LocalVariableName")
private fun shareManga(context: Context, manga_: Manga?, source_: Source?) {
    try {
        getMangaUrl(manga_, source_)?.let { url ->
            val intent = url.toUri().toShareIntent(context, type = "text/plain")
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        context.toast(e.message)
    }
}

/**
 * Perform a search using the provided query.
 *
 * @param query the search query to the parent controller
 */
private suspend fun performSearch(
    backStack: NavBackStack<NavKey>,
    resultEventBus: ResultEventBus,
    query: String,
    global: Boolean,
    // KMK -->
    library: Boolean = false,
    // KMK <--
) {
    if (global) {
        backStack.add(GlobalSearchRoute(query))
        return
    }

    // KMK -->
    // Library search: route through a fresh home instance pushed on top of the current
    // screen, so Back from the library search results returns to the screen the search was
    // started from (#55) — the same behavior Global Search has. LibrarySearchRoute always
    // opens on the Library tab, where the query is applied via the result bus ->
    // LibraryTabSearchEventKey -> LibraryViewModel.search.
    if (library) {
        if (query.isBlank()) return
        if (backStack.lastOrNull() !is LibrarySearchRoute) {
            backStack.add(LibrarySearchRoute)
        }
        resultEventBus.sendResult(resultKey = LibrarySearchEventKey, result = query)
        return
    }
    // KMK <--

    if (backStack.size < 2) {
        return
    }

    // KMK -->
    backStack.popUntil { screen ->
        screen is HomeRoute ||
            (!library && (screen is BrowseSourceRoute || screen is SourceFeedRoute))
    }
    // KMK <--

    when (val previousRoute = backStack.lastOrNull()) {
        is HomeRoute -> resultEventBus.sendResult(resultKey = LibrarySearchEventKey, result = query)
        is BrowseSourceRoute -> resultEventBus.sendResult(resultKey = BrowseSearchEventKey, result = query)
        // SY -->
        is SourceFeedRoute -> {
            // KMK -->
            backStack.add(BrowseSourceRoute(previousRoute.sourceId, query))
            // KMK <--
        }
        // SY <--
    }
}

/**
 * Performs a genre search using the provided genre name.
 *
 * @param genreName the search genre to the parent controller
 */
private suspend fun performGenreSearch(
    backStack: NavBackStack<NavKey>,
    resultEventBus: ResultEventBus,
    genreName: String,
    source: Source,
) {
    if (backStack.size < 2) {
        return
    }

    var idx = backStack.size - 2
    while (idx >= 0) {
        val previousRoute = backStack[idx--]
        if (previousRoute is BrowseSourceRoute && source is HttpSource) {
            // KMK -->
            backStack.popUntil { backStack.size == idx + 2 }
            // KMK <--
            resultEventBus.sendResult(resultKey = BrowseSearchGenreEventKey, result = genreName)
            return
        }
        // KMK -->
        if (previousRoute is SourceFeedRoute && source is HttpSource) {
            backStack.popUntil { backStack.size == idx + 2 }
            backStack.add(BrowseSourceRoute(previousRoute.sourceId, ""))
            resultEventBus.sendResult(resultKey = BrowseSearchGenreEventKey, result = genreName)
            return
        }
        // KMK <--
    }
    if (source is HttpSource) {
        backStack.add(BrowseSourceRoute(source.id, ""))
        resultEventBus.sendResult(resultKey = BrowseSearchGenreEventKey, result = genreName)
    } else {
        performSearch(backStack, resultEventBus, genreName, global = false)
    }
}

/**
 * Copy Manga URL to Clipboard
 */
@Suppress("LocalVariableName")
private fun copyMangaUrl(context: Context, manga_: Manga?, source_: Source?) {
    val manga = manga_ ?: return
    val source = source_ as? HttpSource ?: return
    val url = source.getMangaUrl(manga.toSManga())
    context.copyToClipboard(url, url)
}

// SY -->
private fun openMetadataViewer(
    backStack: NavBackStack<NavKey>,
    manga: Manga,
    // KMK -->
    seedColor: Color?,
    // KMK <--
) {
    backStack.add(MetadataViewRoute(manga.id, manga.source, seedColor?.toArgb()))
}

private fun mergedMangaAction(
    context: Context,
    backStack: NavBackStack<NavKey>,
    mergedMangaData: MergedMangaData,
    // KMK -->
    action: (Context, NavBackStack<NavKey>, Manga, HttpSource?) -> Unit,
    titleRes: StringResource,
    // KMK <--
) {
    val sourceManager: SourceManager = globalAppGraph.sourceManager
    // KMK -->
    val mergedMangaAndSources = mergedMangaData.manga.values
        .filterNot { isMergedSourceId(it.source) }
        .map { manga -> manga to sourceManager.getOrStub(manga.source) }
    // KMK <--
    MaterialAlertDialogBuilder(context)
        .setTitle(titleRes.getString(context))
        .setSingleChoiceItems(
            Array(mergedMangaAndSources.size) { index ->
                // KMK -->
                mergedMangaAndSources[index].second.toString()
                // KMK <--
            },
            -1,
        ) { dialog, index ->
            dialog.dismiss()
            // KMK -->
            val (manga, source) = mergedMangaAndSources[index]
            action(context, backStack, manga, source as? HttpSource)
            // KMK <--
        }
        .setNegativeButton(MR.strings.action_cancel.getString(context), null)
        .show()
}

// KMK -->
private fun browseSource(
    backStack: NavBackStack<NavKey>,
    smartSearchConfig: SmartSearchConfig?,
    source: Source,
    useNewSourceNavigation: Boolean,
) {
    val now = System.currentTimeMillis()
    if (now - lastMangaNavTime < 350) return
    lastMangaNavTime = now
    val route = when {
        // Clicked on source of an entry being merged with previous entry or
        // source of an recommending entry (to search again)
        smartSearchConfig != null -> SmartSearchRoute(
            sourceId = source.id,
            origTitle = smartSearchConfig.origTitle,
            origMangaId = smartSearchConfig.origMangaId,
        )
        useNewSourceNavigation -> SourceFeedRoute(source.id)
        else -> BrowseSourceRoute(source.id, GetRemoteManga.QUERY_POPULAR)
    }
    when (route) {
        // When doing a migrate/recommend => replace previous screen to perform search again.
        // KMK --> HomeRoute is the floor, as in MangaToolbar's onHomeClicked: without it a
        // stack holding none of the searched routes was popped to empty, and the add below
        // re-seeded with the route alone - rootless, and the next back press crashed NavDisplay.
        is SmartSearchRoute -> {
            backStack.popUntil { it is HomeRoute || it is SmartSearchRoute }
            if (backStack.size > 1) backStack.replace(route) else backStack.add(route)
        }

        is SourceFeedRoute -> {
            backStack.popUntil { it is HomeRoute || it is SourceFeedRoute }
            if (backStack.size > 1) backStack.replace(route) else backStack.add(route)
        }

        else -> {
            backStack.popUntil { it is HomeRoute || it is BrowseSourceRoute }
            if (backStack.size > 1) backStack.replace(route) else backStack.add(route)
        }
        // KMK <--
    }
}
// KMK <--

private fun openMorePagePreviews(backStack: NavBackStack<NavKey>, manga: Manga) {
    backStack.safePush(PagePreviewRoute(manga.id))
}

private fun openPagePreview(context: Context, chapter: Chapter?, page: Int) {
    chapter ?: return
    context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id, page))
}
// SY <--

// EXH -->
/**
 * Called when click Merge on an entry to search for entries to merge.
 */
private fun openSmartSearch(backStack: NavBackStack<NavKey>, manga: Manga) {
    val smartSearchConfig = SmartSearchConfig(manga.title, manga.id)
    backStack.safePush(SmartSearchSourcesRoute(smartSearchConfig.origTitle, smartSearchConfig.origMangaId))
}

@OptIn(DelicateCoroutinesApi::class)
private fun mergeWithAnother(
    backStack: NavBackStack<NavKey>,
    context: Context,
    manga: Manga,
    smartSearchConfig: SmartSearchConfig?,
    smartSearchMerge: suspend (Manga, Long) -> Manga,
) {
    launchUI {
        try {
            val mergedManga = withNonCancellableContext {
                smartSearchMerge(manga, smartSearchConfig?.origMangaId!!)
            }

            backStack.popUntil { it is SmartSearchSourcesRoute }
            backStack.removeLastOrNull()
            // KMK -->
            if (backStack.lastOrNull() !is MangaRoute) {
                backStack.add(MangaRoute(mergedManga.id))
            } else {
                // KMK <--
                backStack.replace(MangaRoute(mergedManga.id))
            }
            context.toast(SYMR.strings.entry_merged)
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            context.toast(context.stringResource(SYMR.strings.failed_merge, e.message.orEmpty()))
        }
    }
}
// EXH <--

// AZ -->
private fun openRecommends(backStack: NavBackStack<NavKey>, source: Source?, manga: Manga) {
    source ?: return
    backStack.safePush(RecommendsRoute(manga.id, source.id))
}
// AZ <--
