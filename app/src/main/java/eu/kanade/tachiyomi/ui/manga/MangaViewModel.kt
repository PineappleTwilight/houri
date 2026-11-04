package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.viewModelScope
import coil3.Image
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.asState
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.GetPagePreviews
import eu.kanade.domain.manga.interactor.MergeMangaBySmartSearch
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.StartRereading
import eu.kanade.domain.manga.interactor.StopRereading
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.PagePreview
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.coil.CoverPaletteExtractor
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.model.EntryType
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.common.AddToLibrary
import eu.kanade.tachiyomi.ui.manga.RelatedManga.Companion.isLoading
import eu.kanade.tachiyomi.ui.manga.RelatedManga.Companion.removeDuplicates
import eu.kanade.tachiyomi.ui.manga.RelatedManga.Companion.sorted
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.retainCovers
import eu.kanade.tachiyomi.util.system.toast
import exh.log.xLogE
import exh.md.utils.FollowStatus
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.FlatMetadata
import exh.source.getMainSource
import exh.source.isEhBasedManga
import exh.source.isMergedSourceId
import exh.source.mangaDexSourceIds
import exh.util.nullIfEmpty
import exh.util.trimOrNull
import exh.yakuyomi.MangaInfoTranslationStore
import exh.yakuyomi.TranslationManager
import exh.yakuyomi.TranslationStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.viewmodel.StateViewModel
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.DeleteChapters
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.BookmarkColor
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.chapter.service.applyScanlatorVisibility
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.scanlatorBlacklistKey
import tachiyomi.domain.history.interactor.GetReadDurationForManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.libraryUpdateError.interactor.DeleteLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.interactor.InsertLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.model.LibraryUpdateError
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.InsertLibraryUpdateErrorMessages
import tachiyomi.domain.libraryUpdateErrorMessage.model.LibraryUpdateErrorMessage
import tachiyomi.domain.manga.interactor.DeleteMergeById
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.GetSequelPrequel
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.SetMangaEntryType
import tachiyomi.domain.manga.interactor.UpdateMergedSettings
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.MergeMangaSettingsUpdate
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.manga.model.SEQUEL_PREQUEL_STUB_MEMO_KEY
import tachiyomi.domain.manga.model.SequelPrequelEntry
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.model.entryType
import tachiyomi.domain.manga.model.isSequelPrequelStub
import tachiyomi.domain.manga.model.withEntryType
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal
import kotlin.math.floor
import androidx.compose.runtime.State as RuntimeState

@AssistedInject
class MangaViewModel(
    @Assisted private val context: Context,
    @Assisted private val mangaId: Long,
    // SY -->
    /** If it is opened from Source then it will auto expand the manga description */
    @Assisted private val isFromSource: Boolean,
    @Assisted private val smartSearched: Boolean,
    // SY <--
    private val libraryPreferences: LibraryPreferences,
    private val trackPreferences: TrackPreferences,
    readerPreferences: ReaderPreferences,
    private val uiPreferences: UiPreferences,
    // KMK -->
    private val sourcePreferences: SourcePreferences,
    private val downloadProvider: DownloadProvider,
    // KMK <--
    private val trackerManager: TrackerManager,
    private val trackChapter: TrackChapter,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val getMangaAndChapters: GetMangaWithChapters,
    // SY -->
    private val sourceManager: SourceManager,
    private val getManga: GetManga,
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId,
    private val getMergedMangaById: GetMergedMangaById,
    private val getMergedReferencesById: GetMergedReferencesById,
    // KMK -->
    private val mergeMangaBySmartSearch: MergeMangaBySmartSearch,
    // KMK <--
    private val updateMergedSettings: UpdateMergedSettings,
    private val networkToLocalManga: NetworkToLocalManga,
    private val deleteMergeById: DeleteMergeById,
    private val getFlatMetadata: GetFlatMetadataById,
    private val getPagePreviews: GetPagePreviews,
    private val insertTrack: InsertTrack,
    private val setCustomMangaInfo: SetCustomMangaInfo,
    // KMK -->
    private val getReadDurationForManga: GetReadDurationForManga,
    // KMK <--
    // SY <--
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getAvailableScanlators: GetAvailableScanlators,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val setReadStatus: SetReadStatus,
    // KMK -->
    private val startRereading: StartRereading,
    private val stopRereading: StopRereading,
    private val getSequelPrequel: GetSequelPrequel,
    // KMK <--
    private val updateChapter: UpdateChapter,
    private val updateManga: UpdateManga,
    private val getCategories: GetCategories,
    private val getTracks: GetTracks,
    private val addTracks: AddTracks,
    private val setMangaCategories: SetMangaCategories,
    // KMK --> Persists the entry's content type, so a detection heuristic can never quietly
    // overrule what the reader decided on a later library refresh.
    private val setMangaEntryType: SetMangaEntryType,
    private val mangaRepository: MangaRepository,
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
    // KMK -->
    private val translationStatus: TranslationStatus,
    private val preferenceStore: PreferenceStore,
    private val translationManager: TranslationManager,
    private val mangaInfoTranslationStore: MangaInfoTranslationStore,
    private val deleteLibraryUpdateErrors: DeleteLibraryUpdateErrors,
    private val insertLibraryUpdateErrors: InsertLibraryUpdateErrors,
    private val insertLibraryUpdateErrorMessages: InsertLibraryUpdateErrorMessages,
    private val deleteChaptersFromDb: DeleteChapters,
    // KMK --> Feeds the extension watchdog from this screen's source fetches; see
    // fetchAllFromSource.
    private val extensionManager: ExtensionManager,
    // KMK <--
) : StateViewModel<MangaViewModel.State>(State.Loading) {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(
            context: Context,
            mangaId: Long,
            isFromSource: Boolean,
            smartSearched: Boolean,
        ): MangaViewModel
    }

    private val successState: State.Success?
        get() = state.value as? State.Success

    // KMK -->
    val useNewSourceNavigation by uiPreferences.useNewSourceNavigation().asState(viewModelScope)
    val themeCoverBased = uiPreferences.themeCoverBased().get()
    // KMK <--

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction().get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction().get()
    private var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead().get()

    private val skipFiltered by readerPreferences.skipFiltered().asState(viewModelScope)

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions().get()

    internal var showTrackDialogAfterCategorySelection: Boolean = false

    internal val autoOpenTrack: Boolean
        get() = successState?.hasLoggedInTrackers == true && trackPreferences.trackOnAddingToLibrary().get()

    // EXH -->
    val redirectFlow: MutableSharedFlow<EXHRedirect> = MutableSharedFlow()

    data class EXHRedirect(val mangaId: Long)
    // EXH <--

    private val detailsPipeline = MangaDetailsPipeline(
        mangaId = mangaId,
        scope = viewModelScope,
        getMangaAndChapters = getMangaAndChapters,
        getMergedChaptersByMangaId = getMergedChaptersByMangaId,
        getFlatMetadata = getFlatMetadata,
        getMergedMangaById = getMergedMangaById,
        getMergedReferencesById = getMergedReferencesById,
        sourceManager = sourceManager,
        downloadCache = downloadCache,
        downloadManager = downloadManager,
        libraryPreferences = libraryPreferences,
    )

    private val chapterSettings = MangaChapterSettingsController(
        provideCurrentManga = { manga },
        screenModelScope = viewModelScope,
        context = context,
        libraryPreferences = libraryPreferences,
        setMangaChapterFlags = setMangaChapterFlags,
        setMangaDefaultChapterFlags = setMangaDefaultChapterFlags,
        snackbarHostState = snackbarHostState,
    )

    private val chapterSelection = ChapterSelectionController(
        onUpdateState = { updateSuccessState(it) },
    )

    private val addToLibrary = AddToLibrary(
        scope = viewModelScope,
        setMangaCategories = setMangaCategories,
        updateManga = updateManga,
    )

    private val downloads = ChapterDownloadsController(
        scope = viewModelScope,
        context = context,
        downloadManager = downloadManager,
        filterChaptersForDownload = filterChaptersForDownload,
        sourceManager = sourceManager,
        provideSuccessState = { successState },
        onUpdateState = { updateSuccessState(it) },
        provideFilteredChapters = { filteredChapters },
        provideAllChapters = { allChapters },
        skipFiltered = { skipFiltered },
        isFavorited = { isFavorited },
        snackbarHostState = snackbarHostState,
        onToggleFavorite = { toggleFavorite() },
        onClearSelection = { chapterSelection.toggleAllSelection(false) },
        onDeleteChapters = { deleteChapters(it) },
    )

    // KMK --> Manga-details metadata translation (spec 2026-09-23). The model owns
    // the controller lifecycle; Compose only reads State.Success.mangaInfoUiState.
    private val mangaInfoTranslationController = MangaInfoTranslationController(
        mangaId = mangaId,
        scope = viewModelScope,
        preferenceStore = preferenceStore,
        translationManager = translationManager,
        infoStore = mangaInfoTranslationStore,
        isNoMtl = BuildConfig.IS_NOMTL,
    )

    fun setMangaInfoEnabled(enabled: Boolean) {
        mangaInfoTranslationController.setEnabled(enabled)
    }

    fun setMangaInfoShowTranslated(show: Boolean) {
        mangaInfoTranslationController.setShowTranslated(show)
    }

    /**
     * Re-reads the manga into state after something outside this screen model changed it.
     *
     * The manga here is fetched once and held, not observed, so anything that edits the row behind
     * its back - a new cover, chiefly - leaves this copy stale. A stale cover matters more than other
     * fields: `coverLastModified` is part of the cover's image cache key, so holding the old value
     * keeps the key unchanged and the previously cached cover is what stays on screen, which reads as
     * the edit having been lost until the process is restarted.
     */
    fun refreshManga() {
        val mangaId = manga?.id ?: return
        viewModelScope.launchIO {
            val updated = mangaRepository.getMangaById(mangaId)
            updateSuccessState { it.copy(manga = updated) }
        }
    }

    fun refreshMangaInfo() {
        mangaInfoTranslationController.refresh()
    }

    fun retryMangaInfo() {
        mangaInfoTranslationController.retry()
    }

    fun resetMangaInfo() {
        mangaInfoTranslationController.reset()
    }

    private fun bindMangaInfo(manga: Manga, source: Source?) {
        mangaInfoTranslationController.bind(
            source?.id,
            manga.title,
            manga.description,
            source?.lang,
        )
    }
    // KMK <--

    /**
     * Helper function to update the UI state only if it's currently in success state
     */
    private inline fun updateSuccessState(func: (State.Success) -> State.Success) {
        mutableState.update {
            when (it) {
                State.Loading -> it
                is State.Success -> func(it)
            }
        }
    }

    init {
        viewModelScope.launch {
            mangaInfoTranslationController.state.collectLatest { infoUiState ->
                updateSuccessState { it.copy(mangaInfoUiState = infoUiState) }
            }
        }
        viewModelScope.launchIO {
            detailsPipeline.details()
                .collectLatest { (manga, chapters, flatMetadata, mergedData) ->
                    // KMK -->
                    bindMangaInfo(manga, successState?.source ?: sourceManager.getOrStub(manga.source))
                    // KMK <--
                    // KMK -->
                    val chapterItems = chapters
                        .applyScanlatorVisibility(
                            priority = manga.scanlatorPriority,
                            blacklistedChapters = manga.blacklistedChapters,
                            rangeRules = manga.scanlatorRangeRules,
                            mergeEnabled = libraryPreferences.smartScanlatorMerge().get(),
                        )
                        // KMK <--
                        .toChapterListItems(manga /* SY --> */, mergedData /* SY <-- */)
                    updateSuccessState {
                        it.copy(
                            manga = manga,
                            chapters = chapterItems,
                            // KMK --> Pre-blacklist and pre-merge, so blacklistChapters can tell a
                            // lone upload from one of several sharing a number and a scanlator.
                            allChapters = chapters,
                            // KMK <--
                            // SY -->
                            meta = raiseMetadata(flatMetadata, it.source),
                            mergedData = mergedData,
                            // SY <--
                        )
                    }
                }
        }

        viewModelScope.launchIO {
            detailsPipeline.rootRedirects.collect { redirectFlow.emit(EXHRedirect(it)) }
        }

        viewModelScope.launchIO {
            getExcludedScanlators.subscribe(mangaId)
                .distinctUntilChanged()
                .collectLatest { excludedScanlators ->
                    updateSuccessState {
                        it.copy(excludedScanlators = excludedScanlators.toImmutableSet())
                    }
                }
        }

        viewModelScope.launchIO {
            getAvailableScanlators.subscribe(mangaId)
                .distinctUntilChanged()
                // SY -->
                .combine(
                    state.map { (it as? State.Success)?.manga }
                        .distinctUntilChangedBy { it?.source }
                        .flatMapConcat {
                            if (it != null && isMergedSourceId(it.source)) {
                                getAvailableScanlators.subscribeMerge(mangaId)
                            } else {
                                flowOf(emptySet())
                            }
                        },
                ) { mangaScanlators, mergeScanlators ->
                    mangaScanlators + mergeScanlators
                } // SY <--
                .collectLatest { availableScanlators ->
                    updateSuccessState {
                        it.copy(availableScanlators = availableScanlators.toImmutableSet())
                    }
                }
        }

        observeDownloads()

        viewModelScope.launchIO {
            val manga = getMangaAndChapters.awaitManga(mangaId)

            // SY -->
            val mergedData = getMergedReferencesById.await(mangaId).takeIf { it.isNotEmpty() }?.let { references ->
                MergedMangaData(
                    references,
                    getMergedMangaById.await(mangaId).associateBy { it.id },
                    references.map { it.mangaSourceId }.distinct()
                        .map { sourceManager.getOrStub(it) },
                )
            }
            val chapters = if (isMergedSourceId(manga.source)) {
                getMergedChaptersByMangaId.await(mangaId, applyFilter = true)
            } else {
                getMangaAndChapters.awaitChapters(mangaId, applyFilter = true)
            }
                .toChapterListItems(manga, mergedData)
            val meta = getFlatMetadata.await(mangaId)
            // SY <--

            if (!manga.favorite) {
                setMangaDefaultChapterFlags.await(manga)
            }

            val needRefreshInfo = !manga.initialized
            // KMK --> tracker stubs carry no readable chapters, so skip the
            // chapter refresh that would only fail against the tracker url.
            val needRefreshChapter = chapters.isEmpty() && !manga.isSequelPrequelStub()

            // Show what we have earlier
            mutableState.update {
                // SY -->
                val source = sourceManager.getOrStub(manga.source)
                // SY <--
                State.Success(
                    manga = manga,
                    source = source,
                    isFromSource = isFromSource,
                    chapters = chapters,
                    // SY -->
                    availableScanlators = if (isMergedSourceId(manga.source)) {
                        getAvailableScanlators.awaitMerge(mangaId)
                    } else {
                        getAvailableScanlators.await(mangaId)
                    }.toImmutableSet(),
                    // SY <--
                    excludedScanlators = getExcludedScanlators.await(mangaId).toImmutableSet(),
                    isRefreshingData = needRefreshInfo || needRefreshChapter,
                    dialog = null,
                    hideMissingChapters = libraryPreferences.hideMissingChapters().get(),
                    // SY -->
                    showRecommendationsInOverflow = uiPreferences.recommendsInOverflow().get(),
                    showMergeInOverflow = uiPreferences.mergeInOverflow().get(),
                    showMergeWithAnother = smartSearched,
                    mergedData = mergedData,
                    meta = raiseMetadata(meta, source),
                    pagePreviewsState = if (source.getMainSource() is PagePreviewSource) {
                        getPagePreviews(manga, source)
                        PagePreviewState.Loading
                    } else {
                        PagePreviewState.Unused
                    },
                    alwaysShowReadingProgress =
                    readerPreferences.preserveReadingPosition().get() && manga.isEhBasedManga(),
                    previewsRowCount = uiPreferences.previewsRowCount().get(),
                    // SY <--
                )
            }

            // Start observe tracking since it only needs mangaId
            // KMK --> Bind metadata translation once the first details are shown.
            bindMangaInfo(manga, sourceManager.getOrStub(manga.source))
            // KMK <--
            observeTrackers()

            // Fetch info-chapters when needed
            if (viewModelScope.isActive) {
                // KMK -->
                if (needRefreshInfo || needRefreshChapter) {
                    // KMK <--
                    fetchAllFromSource(
                        manualFetch = false,
                        fetchDetails = needRefreshInfo,
                        fetchChapters = needRefreshChapter,
                    )
                }
                // KMK -->
                launch { syncTrackers() }
                launch { fetchRelatedMangasFromSource() }
                launch { fetchSequelPrequel() }
                // KMK <--
            }

            // Initial loading finished
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    // KMK -->
    /**
     * Seeds the cover-based theme from the image the details header has already decoded.
     *
     * This used to fire a *second* Coil request per details-screen open purely to sample
     * the palette, which failed whenever the cover could not be re-fetched (offline,
     * cache-only policy, expired URL) and left the screen permanently untinted. The cover
     * is already in memory here, so there is nothing left to fetch.
     *
     * Returns immediately and samples on an IO dispatcher: the caller is Coil's
     * `SubcomposeAsyncImage` success callback, which is invoked from composition on the main
     * thread, and `Palette.generate()` quantises and scores the image - tens of milliseconds on
     * the cover sizes a panorama header produces. The previous version wrapped this in
     * `launchIO`; doing it inline put a guaranteed frame drop on every details-screen open.
     */
    fun onCoverPaletteAvailable(mangaCover: MangaCover, image: Image) {
        if (!themeCoverBased && !mangaCover.isMangaFavorite) return
        // Captured before the hop: the holder can be rebound to another manga while this is in
        // flight, and `mangaCover` is the value the caller passed in, so it stays correct - but
        // reading it back on the IO thread after the fact would be a second, different snapshot.
        val isFavorite = mangaCover.isMangaFavorite
        viewModelScope.launchIO {
            val colors = CoverPaletteExtractor.colorsOf(image) ?: return@launchIO
            // Independent: the dominant swatch is chosen by population and so is the likelier of
            // the two to exist, while `vibrant` has six fallbacks and can still come back empty for
            // a flat cover. Returning early on a null vibrant would skip the dominant write too, so
            // a favorite whose palette has no vibrant swatch would lose its grid colours - the one
            // thing this path is most likely to be reached for.
            if (isFavorite) {
                colors.dominant?.let { (rgb, textColor) ->
                    mangaCover.dominantCoverColors = rgb to textColor
                }
            }
            val vibrantColor = colors.vibrant ?: return@launchIO
            mangaCover.vibrantCoverColor = vibrantColor
            val seed = Color(vibrantColor)
            updateSuccessState { state ->
                if (state.seedColor == seed) state else state.copy(seedColor = seed)
            }
        }
    }

    private suspend fun syncTrackers() {
        if (!trackPreferences.autoSyncProgressFromTrackers().get()) return
        refreshTrackers(enhancedTrackersOnly = false)
    }
    // KMK <--

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        viewModelScope.launch {
            updateSuccessState { it.copy(isRefreshingData = true) }
            fetchAllFromSource(
                manualFetch = manualFetch,
                fetchDetails = true,
                fetchChapters = true,
            )
            // KMK -->
            launch { syncTrackers() }
            // KMK <--
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    private suspend fun fetchAllFromSource(
        manualFetch: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) {
        val state = successState ?: return
        try {
            withIOContext {
                val update = updateMangaFromRemote(
                    source = state.source,
                    manga = state.manga,
                    fetchDetails = fetchDetails,
                    fetchChapters = fetchChapters,
                    manualFetch = manualFetch,
                )
                    .getOrThrow()

                // KMK -->
                // A fetch that answered is evidence the extension works now, so whatever the
                // extensions page was holding against it is dropped.
                extensionManager.clearSourceError(state.source)

                clearErrorFromDB(state.manga.id)
                // KMK <--

                if (manualFetch) {
                    downloadNewChapters(update.newChapters)
                }
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: Exception) {
            // KMK --> Fed to the extension watchdog so the failure is attributable on the
            // extensions page. Only a manual refresh may interrupt: the automatic refreshes fire
            // whenever the screen opens with stale data, without the user pointing at this
            // source, so those are recorded without a popup. An empty chapter list
            // ([NoChaptersException]) is reported too - a source that stops listing chapters is
            // usually a site change under the scraper, which is exactly what the report flow
            // exists to attribute, and it is suppressible like any other failure.
            extensionManager.reportSourceError(state.source, e, prompt = manualFetch)
            // KMK <--
            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            viewModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
            // KMK -->
            writeErrorToDB(state.manga to with(context) { e.formattedMessage })
            // KMK <--
        }
    }

    // KMK -->
    private suspend fun clearErrorFromDB(mangaId: Long) {
        deleteLibraryUpdateErrors.deleteMangaError(mangaIds = listOf(mangaId))
    }

    private suspend fun writeErrorToDB(error: Pair<Manga, String?>) {
        val errorMessage = error.second ?: context.stringResource(MR.strings.unknown_error)
        val errorMessageId = insertLibraryUpdateErrorMessages.insert(
            libraryUpdateErrorMessage = LibraryUpdateErrorMessage(-1L, errorMessage),
        )

        insertLibraryUpdateErrors.upsert(
            LibraryUpdateError(id = -1L, mangaId = error.first.id, messageId = errorMessageId),
        )
    }
    // KMK <--

    // SY -->
    private fun raiseMetadata(flatMetadata: FlatMetadata?, source: Source): RaisedSearchMetadata? {
        return if (flatMetadata != null) {
            val metaClass = source.getMainSource<MetadataSource<*, *>>()?.metaClass
            if (metaClass != null) flatMetadata.raise(metaClass) else null
        } else {
            null
        }
    }

    fun updateMangaInfo(
        title: String?,
        author: String?,
        artist: String?,
        thumbnailUrl: String?,
        description: String?,
        tags: List<String>?,
        status: Long?,
    ) {
        val state = successState ?: return
        var manga = state.manga
        if (state.manga.isLocal()) {
            val newTitle = if (title.isNullOrBlank()) manga.url else title.trim()
            val newAuthor = author?.trimOrNull()
            val newArtist = artist?.trimOrNull()
            val newThumbnailUrl = thumbnailUrl?.trimOrNull()
            val newDesc = description?.trimOrNull()
            manga = manga.copy(
                ogTitle = newTitle,
                ogAuthor = author?.trimOrNull(),
                ogArtist = artist?.trimOrNull(),
                ogThumbnailUrl = thumbnailUrl?.trimOrNull(),
                ogDescription = description?.trimOrNull(),
                ogGenre = tags?.nullIfEmpty(),
                ogStatus = status ?: 0,
                lastUpdate = manga.lastUpdate + 1,
            )
            (sourceManager.get(LocalSource.ID) as LocalSource).updateMangaInfo(manga.toSManga())
            viewModelScope.launchNonCancellable {
                updateManga.await(
                    MangaUpdate(
                        manga.id,
                        title = newTitle,
                        author = newAuthor,
                        artist = newArtist,
                        thumbnailUrl = newThumbnailUrl,
                        description = newDesc,
                        genre = tags,
                        status = status,
                    ),
                )
            }
        } else {
            val genre = if (!tags.isNullOrEmpty() && tags != state.manga.ogGenre) {
                tags
            } else {
                null
            }
            setCustomMangaInfo.set(
                CustomMangaInfo(
                    state.manga.id,
                    title?.trimOrNull(),
                    author?.trimOrNull(),
                    artist?.trimOrNull(),
                    thumbnailUrl?.trimOrNull(),
                    description?.trimOrNull(),
                    genre,
                    status.takeUnless { it == state.manga.ogStatus },
                ),
            )
            manga = manga.copy(lastUpdate = manga.lastUpdate + 1)
        }

        updateSuccessState { successState ->
            successState.copy(manga = manga)
        }
    }

    // KMK -->
    @Composable
    fun getManga(initialManga: Manga): RuntimeState<Manga> {
        return produceState(initialValue = initialManga) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .filterNotNull()
                .collectLatest { manga ->
                    value = manga
                }
        }
    }

    suspend fun smartSearchMerge(manga: Manga, originalMangaId: Long): Manga {
        return mergeMangaBySmartSearch.smartSearchMerge(manga, originalMangaId)
    }
    // KMK <--

    fun updateMergeSettings(mergedMangaReferences: List<MergedMangaReference>) {
        viewModelScope.launchNonCancellable {
            if (mergedMangaReferences.isNotEmpty()) {
                updateMergedSettings.awaitAll(
                    mergedMangaReferences.map {
                        MergeMangaSettingsUpdate(
                            id = it.id,
                            isInfoManga = it.isInfoManga,
                            getChapterUpdates = it.getChapterUpdates,
                            chapterPriority = it.chapterPriority,
                            downloadChapters = it.downloadChapters,
                            chapterSortMode = it.chapterSortMode,
                        )
                    },
                )
            }
        }
    }

    fun deleteMerge(reference: MergedMangaReference) {
        viewModelScope.launchNonCancellable {
            deleteMergeById.await(reference.id)
        }
    }
    // SY <--

    // Manga info - start

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                viewModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.delete_downloads_for_manga),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        viewModelScope.launchIO {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                    // Remove covers and update last modified in db
                    if (manga.retainCovers() != manga) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                    }
                    withUIContext { onRemoved() }
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicates = getDuplicateLibraryManga(manga)

                    if (duplicates.isNotEmpty()) {
                        updateSuccessState { it.copy(dialog = Dialog.DuplicateManga(manga, duplicates)) }
                        return@launchIO
                    }
                }

                // Now check if user previously set categories, when available
                val categories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultCategory().get().toLong()
                val defaultCategory = categories.find { it.id == defaultCategoryId }
                when {
                    // Default category set
                    defaultCategory != null -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(defaultCategory)
                    }

                    // Automatic 'Default' or no categories
                    defaultCategoryId == 0L || categories.isEmpty() -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(null)
                    }

                    // Choose a category
                    else -> {
                        showTrackDialogAfterCategorySelection = true
                        showChangeCategoryDialog()
                    }
                }

                // Finally match with enhanced tracking when available
                addTracks.bindEnhancedTrackers(manga, state.source)
                if (autoOpenTrack && !showTrackDialogAfterCategorySelection) {
                    showTrackDialog()
                }
            }
        }
    }

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            updateSuccessState { successState ->
                successState.copy(
                    dialog = Dialog.ChangeCategory(
                        manga = manga,
                        initialSelection = categories.mapAsCheckboxState { it.id in selection }.toImmutableList(),
                    ),
                )
            }
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState {
            it.copy(dialog = Dialog.SetFetchInterval(manga))
        }
    }

    // KMK -->
    fun showReadingStatsDialog() {
        viewModelScope.launchIO {
            val manga = successState?.manga
            val totalReadDuration = if (manga != null) {
                getReadDurationForManga.await(manga.id, manga.title)
            } else {
                successState?.manga?.id?.let { getReadDurationForManga.await(it) } ?: 0L
            }
            updateSuccessState {
                it.copy(dialog = Dialog.ReadingStats(totalReadDuration = totalReadDuration))
            }
        }
    }
    // KMK <--

    fun setFetchInterval(manga: Manga, interval: Int) {
        viewModelScope.launchIO {
            if (
                updateManga.awaitUpdateFetchInterval(
                    // Custom intervals are negative
                    manga.copy(fetchInterval = -interval),
                )
            ) {
                val updatedManga = mangaRepository.getMangaById(manga.id)
                updateSuccessState { it.copy(manga = updatedManga) }
            }
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    private fun hasDownloads(): Boolean = downloads.hasDownloads()

    /**
     * Deletes all the downloads for the manga.
     */
    private fun deleteDownloads() {
        downloads.deleteDownloads()
    }

    /**
     * Opens manga folder with the system's file manager.
     */
    fun openMangaFolder(currentSource: Source?, currentManga: Manga?) {
        try {
            if (currentManga == null || currentSource == null || currentSource is StubSource) return

            val mangaDir = downloadProvider.findMangaDir(/* SY --> */ currentManga.ogTitle /* SY <-- */, currentSource) ?: return
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(mangaDir.uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            context.toast(e.message ?: context.stringResource(KMR.strings.error_opening_folder))
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        addToLibrary.moveToCategoriesAndFavorite(manga, categories)
    }

    /**
     * Move the given manga to categories.
     *
     * @param categories the selected categories.
     */
    private fun moveMangaToCategories(categories: List<Category>) {
        val categoryIds = categories.map { it.id }
        moveMangaToCategory(categoryIds)
    }

    private fun moveMangaToCategory(categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    /**
     * Move the given manga to the category.
     *
     * @param category the selected category, or null for default category.
     */
    private fun moveMangaToCategory(category: Category?) {
        moveMangaToCategories(listOfNotNull(category))
    }

    // Manga info - end

    // Chapters list - start

    private fun observeDownloads() = downloads.observeDownloads()

    private fun List<Chapter>.toChapterListItems(
        manga: Manga,
        // SY -->
        mergedData: MergedMangaData?,
        // SY <--
    ): List<ChapterList.Item> {
        val isLocal = manga.isLocal()
        // SY -->
        val isExhManga = manga.isEhBasedManga()
        // SY <--
        return map { chapter ->
            val activeDownload = if (isLocal) {
                null
            } else {
                downloadManager.getQueuedDownloadOrNull(chapter.id)
            }

            // SY -->
            @Suppress("NAME_SHADOWING")
            val manga = mergedData?.manga?.get(chapter.mangaId) ?: manga
            val source = mergedData?.sources?.find { manga.source == it.id }?.takeIf { mergedData.sources.size > 2 }
            // SY <--
            val downloaded = if (manga.isLocal()) {
                true
            } else {
                downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    // SY -->
                    manga.ogTitle,
                    // SY <--
                    manga.source,
                )
            }
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }

            val translationManager = globalAppGraph.translationManager
            // Deliberately not isEnabledForManga: this drives the chapter-list progress indicator, which
            // keeps showing translation state while the UI is gated (incognito/censor only blocks
            // starting work, not displaying what already ran). The gate is not part of this question.
            val mtlEnabled = translationManager.isEnabled() && translationManager.isPerMangaEnabled(manga.id)
            val transStatus = if (mtlEnabled) translationStatus.chapterStatus(manga.id, chapter.id) else null
            val translationProgress = if (transStatus != null && transStatus.totalPages > 0) {
                (transStatus.translatedCount * 100 / transStatus.totalPages).coerceIn(0, 100)
            } else {
                0
            }
            val isTranslating = transStatus?.isTranslating ?: false
            // A partially-translated chapter still tracks progress; only a chapter with errors AND
            // zero translated pages (e.g. a failed background run) is shown as errored.
            val isTranslationError = transStatus?.let { it.errorCount > 0 && it.translatedCount == 0 } ?: false

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                selected = chapterSelection.isSelected(chapter.id),
                // SY -->
                sourceName = source?.getNameForMangaInfo(),
                showScanlator = !isExhManga,
                // SY <--
                translationProgress = translationProgress,
                isTranslating = isTranslating,
                isTranslationError = isTranslationError,
            )
        }
    }

    // SY -->
    private fun getPagePreviews(manga: Manga, source: Source) {
        viewModelScope.launchIO {
            when (val result = getPagePreviews.await(manga, source, 1)) {
                is GetPagePreviews.Result.Error -> updateSuccessState {
                    it.copy(pagePreviewsState = PagePreviewState.Error(result.error))
                }
                is GetPagePreviews.Result.Success -> updateSuccessState {
                    it.copy(pagePreviewsState = PagePreviewState.Success(result.pagePreviews))
                }
                GetPagePreviews.Result.Unused -> updateSuccessState {
                    it.copy(pagePreviewsState = PagePreviewState.Unused)
                }
            }
        }
    }
    // SY <--

    // KMK -->
    /**
     * Set the fetching related mangas status.
     * @param state
     * - false: started & fetching
     * - true: finished
     */
    private fun setRelatedMangasFetchedStatus(state: Boolean) {
        updateSuccessState { it.copy(isRelatedMangasFetched = state) }
    }

    /**
     * Requests an list of related mangas from the source.
     */
    internal suspend fun fetchRelatedMangasFromSource(onDemand: Boolean = false, onFinish: (() -> Unit)? = null) {
        val expandRelatedMangas = uiPreferences.expandRelatedMangas().get()
        if ((!onDemand && !expandRelatedMangas) || manga?.let { isMergedSourceId(it.source) } == true) return

        // start fetching related mangas
        setRelatedMangasFetchedStatus(false)

        fun exceptionHandler(e: Throwable) {
            logcat(LogPriority.ERROR, e)
            val message = with(context) { e.formattedMessage }

            viewModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
        }
        val state = successState ?: return
        val relatedMangasEnabled = sourcePreferences.relatedMangas().get()

        try {
            if (state.source !is StubSource && relatedMangasEnabled) {
                state.source.getRelatedMangaList(state.manga.toSManga(), { e -> exceptionHandler(e) }) { pair, _ ->
                    /* Push found related mangas into collection */
                    val relatedManga = RelatedManga.Success.fromPair(pair) { mangaList ->
                        mangaList
                            .map { it.toDomainManga(state.source.id) }
                            .distinctBy { it.url }
                            .let { networkToLocalManga(manga = it, updateInfo = false) }
                    }

                    updateSuccessState { successState ->
                        val relatedMangaCollection =
                            successState.relatedMangaCollection
                                ?.toMutableStateList()
                                ?.apply { add(relatedManga) }
                                ?: listOf(relatedManga)
                        successState.copy(relatedMangaCollection = relatedMangaCollection)
                    }
                }
            }
        } catch (e: Exception) {
            exceptionHandler(e)
        } finally {
            if (onFinish != null) {
                onFinish()
            } else {
                setRelatedMangasFetchedStatus(true)
            }
        }
    }
    // KMK <--

    // KMK -->
    internal suspend fun fetchSequelPrequel() {
        val state = successState ?: return
        if (!uiPreferences.showSequelPrequel().get()) return
        try {
            val preferredTrackerId = trackPreferences.getPreferredTrackerForManga(state.manga.id)
            when (val result = getSequelPrequel.await(state.manga.id, preferredTrackerId, enabled = true)) {
                is GetSequelPrequel.Result.Success -> {
                    // KMK --> scan the library once so stub entries matching a
                    // library title render an "In library" badge. Stubs stay
                    // non-favorite (insertNetworkManga preserves favorite=false)
                    // so they never count towards library/achievement stats.
                    // Titles come from the two-column projection, not the whole
                    // libraryView aggregate: this runs per details screen.
                    val libraryTitles = try {
                        mangaRepository.getFavoriteTitles()
                            .map(String::lowercase)
                            .toSet()
                    } catch (_: Exception) {
                        emptySet()
                    }
                    // KMK <--
                    updateSuccessState { it.copy(sequelPrequelEntries = result.entries, sequelPrequelLibraryTitles = libraryTitles) }
                }
                GetSequelPrequel.Result.Hidden, GetSequelPrequel.Result.Disabled ->
                    updateSuccessState { it.copy(sequelPrequelEntries = emptyList()) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            updateSuccessState { it.copy(sequelPrequelEntries = emptyList()) }
        }
    }

    suspend fun resolveSequelPrequel(entry: SequelPrequelEntry): Long? {
        val state = successState ?: return null
        // KMK --> library first: land on the existing library entry without
        // creating a stub or hitting any network. One indexed title lookup
        // instead of materializing every library entry with its chapter counts.
        try {
            mangaRepository.getMangaIdByTitle(entry.title)?.let { return it }
        } catch (_: Exception) {
        }
        // KMK --> tracker stub: metadata comes from the tracker that reported
        // the relation, since trackers carry no readable chapters.
        try {
            resolveTrackerStub(entry, state.source.id)?.let { return it }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
        // KMK --> in-source resolution (e.g. MangaDex fallback urls).
        return try {
            val sManga = (state.source as? ResolvableSource)?.getManga(entry.url)
                ?: state.source.getMangaUpdate(
                    SManga.create().apply {
                        url = entry.url
                        title = entry.title
                    },
                    emptyList(),
                    fetchDetails = true,
                    fetchChapters = false,
                ).manga
            val resolved = networkToLocalManga(sManga.toDomainManga(state.source.id))
            // KMK --> library scan first: land on the existing library entry
            // instead of opening a duplicate stub. Favoriting the stub still
            // raises the DuplicateManga dialog with a migrate prompt via
            // toggleFavorite(checkDuplicate = true).
            val duplicates = try {
                getDuplicateLibraryManga(resolved)
            } catch (_: Exception) {
                emptyList()
            }
            duplicates.firstOrNull { it.manga.favorite }?.manga?.id
                ?: duplicates.firstOrNull()?.manga?.id
                ?: resolved.id
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    private suspend fun resolveTrackerStub(entry: SequelPrequelEntry, sourceId: Long): Long? {
        val trackerId = entry.trackerId ?: return null
        val service = trackerManager.get(trackerId) as? BaseTracker ?: return null
        if (!service.isLoggedIn) return null
        val remoteId = service.parseRelatedEntryId(entry.url) ?: return null
        val metadata = service.getMangaMetadata(
            Track(
                id = -1L,
                mangaId = -1L,
                trackerId = trackerId,
                remoteId = remoteId,
                libraryId = null,
                title = entry.title,
                lastChapterRead = 0.0,
                totalChapters = 0L,
                status = 0L,
                score = 0.0,
                remoteUrl = entry.url,
                startDate = 0L,
                finishDate = 0L,
                private = false,
            ),
        )
        val sManga = SManga.create().apply {
            url = entry.url
            title = metadata.title?.ifBlank { null } ?: entry.title
            thumbnail_url = metadata.thumbnailUrl?.ifBlank { null } ?: entry.coverUrl
            description = metadata.description
            author = metadata.authors
            artist = metadata.artists
            genre = metadata.tags?.joinToString(", ")
            status = metadata.status?.toInt() ?: SManga.UNKNOWN
            initialized = true
            memo = buildJsonObject { put(SEQUEL_PREQUEL_STUB_MEMO_KEY, true) }
        }
        val resolved = networkToLocalManga(sManga.toDomainManga(sourceId))
        val duplicates = try {
            getDuplicateLibraryManga(resolved)
        } catch (_: Exception) {
            emptyList()
        }
        return duplicates.firstOrNull { it.manga.favorite }?.manga?.id
            ?: duplicates.firstOrNull()?.manga?.id
            ?: resolved.id
    }
    // KMK <--

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        viewModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapter.read)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapter.bookmark)
            }
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        val successState = successState ?: return null
        return successState.chapters.getNextUnread(successState.manga)
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) = downloads.runChapterDownloadActions(items, action)

    fun runDownloadAction(action: DownloadAction) = downloads.runDownloadAction(action)

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        val chapters = filteredChapters.orEmpty().map { it.chapter }
        val prevChapters = if (manga.sortDescending()) chapters.asReversed() else chapters
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        viewModelScope.launchIO {
            setReadStatus.await(
                read = read,
                chapters = chapters.toTypedArray(),
            )

            if (!read || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launchIO
            }

            refreshTrackers()

            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launchIO
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                withUIContext {
                    context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                }
                return@launchIO
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Long,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    private suspend fun refreshTrackers(
        // KMK -->
        enhancedTrackersOnly: Boolean = true,
        // KMK <--
        refreshTracks: RefreshTracks = globalAppGraph.refreshTracks,
    ) {
        // KMK -->
        refreshTracks.await(mangaId, enhancedTrackersOnly = enhancedTrackersOnly)
            // KMK <--
            .filter { it.first != null }
            .forEach { (track, e) ->
                logcat(LogPriority.ERROR, e) {
                    "Failed to refresh track data mangaId=$mangaId for service ${track?.id}"
                }
                withUIContext {
                    context.toast(
                        context.stringResource(
                            MR.strings.track_error,
                            track?.name ?: "Unknown",
                            e.message ?: "",
                        ),
                    )
                }
            }
    }

    // KMK -->
    fun startRereading() {
        val manga = successState?.manga ?: return
        viewModelScope.launchIO {
            when (startRereading.await(manga)) {
                is StartRereading.Result.Success -> {
                    withUIContext {
                        context.toast(context.stringResource(KMR.strings.started_rereading))
                    }
                }
                StartRereading.Result.Failure -> Unit
            }
        }
    }

    fun stopRereading() {
        val manga = successState?.manga ?: return
        viewModelScope.launchIO {
            if (stopRereading.await(manga) == StopRereading.Result.Success) {
                withUIContext {
                    context.toast(context.stringResource(KMR.strings.stopped_rereading))
                }
            }
        }
    }
    // KMK <--

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        viewModelScope.launchIO {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(id = it.id, bookmark = bookmarked) }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        viewModelScope.launchNonCancellable {
            try {
                successState?.let { state ->
                    // KMK -->
                    if (isMergedSourceId(state.source.id)) {
                        chapters.groupBy { it.mangaId }.forEach { map ->
                            val manga = state.mergedData?.manga?.get(map.key) ?: return@forEach
                            val source = state.mergedData.sources.find { !isMergedSourceId(it.id) && manga.source == it.id } ?: return@forEach
                            downloadManager.deleteChapters(
                                map.value,
                                manga,
                                source,
                                ignoreCategoryExclusion = true,
                            )
                            if (source.isLocal()) {
                                // Refresh chapters state for Local source. Runs only after
                                // deleteChapters has actually removed the files — it suspends —
                                // so the re-listing cannot race the deletion it is reacting to.
                                fetchAllFromSource(
                                    manualFetch = false,
                                    fetchDetails = false,
                                    fetchChapters = true,
                                )
                            }
                        }
                    } else {
                        // KMK <--
                        downloadManager.deleteChapters(
                            chapters,
                            state.manga,
                            state.source,
                            // KMK -->
                            ignoreCategoryExclusion = true,
                            // KMK <--
                        )
                        // KMK -->
                        if (state.source.isLocal()) {
                            // Refresh chapters state for Local source. Runs only after
                            // deleteChapters has actually removed the files — it suspends —
                            // so the re-listing cannot race the deletion it is reacting to.
                            fetchAllFromSource(
                                manualFetch = false,
                                fetchDetails = false,
                                fetchChapters = true,
                            )
                        }
                        // KMK <--
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    // KMK -->
    fun clearManga(
        deleteDownload: Boolean,
        removeChapters: Boolean,
    ) {
        if (deleteDownload) {
            deleteDownloadedData()
        }
        if (removeChapters) {
            removeChaptersDatabase()
        }
    }

    private fun deleteDownloadedData() {
        viewModelScope.launchNonCancellable {
            try {
                successState?.let { state ->
                    if (isMergedSourceId(state.source.id)) {
                        state.mergedData?.manga
                            ?.forEach { (_, manga) ->
                                val source = state.mergedData.sources.find { !isMergedSourceId(it.id) && manga.source == it.id } ?: return@forEach

                                downloadManager.deleteManga(
                                    manga = manga,
                                    source = source,
                                    removeQueued = true,
                                )
                                if (source.isLocal()) {
                                    // Refresh chapters state for Local source. deleteManga
                                    // suspends until the folder is gone, so this re-listing
                                    // cannot open a file the delete is about to remove.
                                    fetchAllFromSource(
                                        manualFetch = false,
                                        fetchDetails = false,
                                        fetchChapters = true,
                                    )
                                }
                            }
                    } else {
                        downloadManager.deleteManga(
                            manga = state.manga,
                            source = state.source,
                            removeQueued = true,
                        )
                        if (state.source.isLocal()) {
                            // Refresh chapters state for Local source. deleteManga suspends
                            // until the folder is gone, so this re-listing cannot open a file
                            // the delete is about to remove.
                            fetchAllFromSource(
                                manualFetch = false,
                                fetchDetails = false,
                                fetchChapters = true,
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun removeChaptersDatabase() {
        viewModelScope.launchNonCancellable {
            try {
                successState?.chapters?.map { it.id }
                    ?.let { deleteChaptersFromDb.await(it) }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }
    // KMK <--

    private fun downloadNewChapters(chapters: List<Chapter>) {
        downloads.downloadNewChapters(chapters)
    }

    fun setUnreadFilter(state: TriState) = chapterSettings.setUnreadFilter(state)

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) = chapterSettings.setDownloadedFilter(state)

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: TriState) = chapterSettings.setBookmarkedFilter(state)

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) = chapterSettings.setDisplayMode(mode)

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) = chapterSettings.setSorting(sort)

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) = chapterSettings.setCurrentSettingsAsDefault(applyToExisting)

    fun resetToDefaultSettings() = chapterSettings.resetToDefaultSettings()

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) = chapterSelection.toggleSelection(item, selected, fromLongPress)

    fun toggleAllSelection(selected: Boolean) = chapterSelection.toggleAllSelection(selected)

    fun invertSelection() = chapterSelection.invertSelection()

    // Chapters list - end

    // Track sheet - start

    // KMK --> inputs watched to refetch sequel/prequel relations
    // when tracker bindings change (see observeTrackers below).
    private data class SequelPrequelInputs(
        val trackKeys: Set<Pair<Long, Long>>,
        val loggedInTrackerIds: Set<Long>,
        val preferredTrackerId: Long?,
    )
    // KMK <--

    private fun observeTrackers() {
        val state = successState
        val manga = state?.manga ?: return

        // KMK --> last tracker inputs seen by the sequel/prequel
        // refetch hook in the collector below (null until the
        // first emission, which init already fetched for).
        var sequelPrequelInputs: SequelPrequelInputs? = null
        // KMK <--

        viewModelScope.launchIO {
            combine(
                getTracks.subscribe(manga.id).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                // KMK -->
                val supportedTrackers = source?.let { source ->
                    val sources = if (source is MergedSource) {
                        state.mergedData?.sources ?: emptyList()
                    } else {
                        listOf(source)
                    }
                    loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(sources) ?: true }
                } ?: loggedInTrackers.filterNot { it is EnhancedTracker }
                // KMK <--
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks to supportedTrackers
            }
                // SY -->
                .map { (tracks, supportedTrackers) ->
                    val supportedTrackerTracks = if (manga.source in mangaDexSourceIds ||
                        state.mergedData?.manga?.values.orEmpty().any {
                            it.source in mangaDexSourceIds
                        }
                    ) {
                        val mdTrack = supportedTrackers.firstOrNull { it is MdList }
                        when {
                            mdTrack == null -> {
                                tracks
                            }
                            // KMK: auto track MangaDex
                            mdTrack.id !in tracks.map { it.trackerId } -> {
                                createMdListTrack()?.let { tracks + it } ?: tracks
                            }
                            else -> tracks
                        }
                    } else {
                        tracks
                    }
                    // KMK --> carry the sequel/prequel inputs alongside
                    // the badge count so track bindings, logins and the
                    // preferred tracker can trigger a refetch below.
                    val visibleTracks = supportedTrackerTracks
                        .filter {
                            it.trackerId != trackerManager.mdList.id ||
                                it.status != FollowStatus.UNFOLLOWED.long
                        }
                    Triple(
                        visibleTracks.size,
                        supportedTrackers.isNotEmpty(),
                        SequelPrequelInputs(
                            trackKeys = visibleTracks.map { it.trackerId to it.remoteId }.toSet(),
                            loggedInTrackerIds = supportedTrackers.map { it.id }.toSet(),
                            preferredTrackerId = trackPreferences.getPreferredTrackerForManga(manga.id),
                        ),
                    )
                    // KMK <--
                }
                // SY <--
                .distinctUntilChanged()
                .collectLatest { (trackingCount, hasLoggedInTrackers, sequelInputs) ->
                    updateSuccessState {
                        it.copy(
                            trackingCount = trackingCount,
                            hasLoggedInTrackers = hasLoggedInTrackers,
                        )
                    }
                    // KMK --> refetch sequel/prequel relations when tracker
                    // inputs change (bind/unbind, login/logout, preferred
                    // switch): the first fetch may have returned Hidden
                    // before a tracker was bound, and empty results are not
                    // cached, so invalidate + re-query surfaces the new
                    // relations immediately. Skipped on the first emission
                    // because the screen-init fetch already ran for it.
                    if (sequelPrequelInputs != null && sequelPrequelInputs != sequelInputs) {
                        getSequelPrequel.invalidate(manga.id)
                        fetchSequelPrequel()
                    }
                    sequelPrequelInputs = sequelInputs
                    // KMK <--
                }
        }
    }

    // SY -->
    private suspend fun createMdListTrack(): Track? {
        try {
            val state = successState!!
            val mdManga = state.manga.takeIf { it.source in mangaDexSourceIds }
                ?: state.mergedData?.manga?.values?.find { it.source in mangaDexSourceIds }
                ?: throw IllegalArgumentException("Entry does not belong to MangaDex")
            val track = trackerManager.mdList.createInitialTracker(state.manga, mdManga)
                .toDomainTrack(false)
                ?: throw IllegalStateException("Could not create initial track")
            insertTrack.await(track)
            /* KMK -->
            return TrackItem(
                getTracks.await(mangaId).first { it.trackerId == trackerManager.mdList.id },
                 trackerManager.mdList,
             )
            KMK <-- */
            return getTracks.await(mangaId).first { it.trackerId == trackerManager.mdList.id }
        } catch (e: Exception) {
            xLogE("Failed to create MangaDex track", e)
            return null
        }
    }
    // SY <--

    // Track sheet - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog
        data class DuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog

        // KMK -->
        data class ReadingStats(val totalReadDuration: Long) : Dialog
        // KMK <--

        // SY -->
        data class EditMangaInfo(val manga: Manga) : Dialog
        data class EditMergedSettings(val mergedData: MergedMangaData) : Dialog
        // SY <--

        // KMK -->
        data object ClearManga : Dialog

        // KMK -->
        data object ConfirmStartReread : Dialog
        data object ConfirmStopReread : Dialog
        // KMK <--
        // KMK <--

        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog
    }

    fun dismissDialog() {
        updateSuccessState { it.copy(dialog = null) }
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        updateSuccessState { it.copy(dialog = Dialog.DeleteChapters(chapters)) }
    }

    fun showSettingsDialog() {
        updateSuccessState { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun showTrackDialog() {
        updateSuccessState { it.copy(dialog = Dialog.TrackSheet) }
    }

    fun showCoverDialog() {
        updateSuccessState { it.copy(dialog = Dialog.FullCover) }
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.Migrate(target = manga, current = duplicate)) }
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        viewModelScope.launchIO {
            setExcludedScanlators.await(mangaId, excludedScanlators)
        }
    }

    // KMK -->
    fun setScanlatorPriority(priority: List<String>) {
        viewModelScope.launchIO {
            updateManga.await(MangaUpdate(id = mangaId, scanlatorPriority = priority))
        }
    }

    fun setBlacklistedChapters(blacklistedChapters: List<String>) {
        viewModelScope.launchIO {
            updateManga.await(MangaUpdate(id = mangaId, blacklistedChapters = blacklistedChapters))
        }
    }

    fun blacklistChapters(chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        viewModelScope.launchIO {
            // KMK --> A scanlator that published one number twice produces the same broad key for
            // both, and blacklisting one would take the other with it (issue #39). Narrow to the
            // upload only in that case; a lone upload keeps the broad key, which survives the url
            // changing on a later fetch.
            val siblings = successState?.allChapters.orEmpty()
            val newEntries = chapters.map { chapter ->
                val hasSiblings = siblings.any { other ->
                    other.id != chapter.id &&
                        other.chapterNumber == chapter.chapterNumber &&
                        other.scanlator?.trim().orEmpty() == chapter.scanlator?.trim().orEmpty()
                }
                if (hasSiblings && chapter.url.isNotBlank()) {
                    scanlatorBlacklistKey(chapter.chapterNumber, chapter.scanlator, chapter.url)
                } else {
                    scanlatorBlacklistKey(chapter.chapterNumber, chapter.scanlator)
                }
            }
            // KMK <--
            val current = successState?.manga?.blacklistedChapters ?: emptyList()
            updateManga.await(
                MangaUpdate(
                    id = mangaId,
                    blacklistedChapters = (current + newEntries).distinct(),
                ),
            )
        }
    }

    // KMK -->
    fun setBookmarkColorFilter(colors: Set<BookmarkColor>) {
        mutableState.update { state ->
            when (state) {
                State.Loading -> state
                is State.Success -> state.copy(bookmarkColorFilter = colors)
            }
        }
    }

    fun setBookmarkColor(chapters: List<Chapter>, color: BookmarkColor) {
        if (chapters.isEmpty()) return
        viewModelScope.launchIO {
            updateChapter.awaitAll(
                chapters.map { it.toChapterUpdate().copy(bookmarkColor = color) },
            )
        }
    }
    // KMK <--

    fun removeBlacklistedChapters(keys: List<String>) {
        if (keys.isEmpty()) return
        viewModelScope.launchIO {
            val current = successState?.manga?.blacklistedChapters ?: emptyList()
            updateManga.await(
                MangaUpdate(
                    id = mangaId,
                    blacklistedChapters = current - keys.toSet(),
                ),
            )
        }
    }
    // KMK <--

    // SY -->
    fun showEditMangaInfoDialog() {
        mutableState.update { state ->
            when (state) {
                State.Loading -> state
                is State.Success -> {
                    state.copy(dialog = Dialog.EditMangaInfo(state.manga))
                }
            }
        }
    }

    fun showEditMergedSettingsDialog() {
        val mergedData = successState?.mergedData ?: return
        mutableState.update { state ->
            when (state) {
                State.Loading -> state
                is State.Success -> {
                    state.copy(dialog = Dialog.EditMergedSettings(mergedData))
                }
            }
        }
    }
    // SY <--

    // KMK -->
    // KMK -->
    fun showStartRereadDialog() {
        updateSuccessState { it.copy(dialog = Dialog.ConfirmStartReread) }
    }

    fun showStopRereadDialog() {
        updateSuccessState { it.copy(dialog = Dialog.ConfirmStopReread) }
    }

    /**
     * Forces this entry to be read as a comic, or hands it back to detection.
     *
     * The escape hatch for a heuristic that guessed wrong, and the reason the override is reachable
     * from the info screen *before* opening the book: an art book that detects as a novel is
     * otherwise only discoverable by trying to read it.
     *
     * Idempotent in effect and free of side effects beyond the one bit, so pressing it twice costs
     * nothing and returns the entry to exactly where it started.
     */
    fun toggleEntryType() {
        val manga = state.value as? State.Success ?: return
        viewModelScope.launch {
            val current = manga.manga.entryType()
            setMangaEntryType(manga.manga.id, EntryType.COMIC)
            updateSuccessState { current ->
                current.copy(manga = current.manga.withEntryType(EntryType.COMIC))
            }
        }
    }
    // KMK <--

    fun showClearMangaDialog() {
        updateSuccessState { it.copy(dialog = Dialog.ClearManga) }
    }
    // KMK <--

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            // KMK --> Every chapter the source returned, before the blacklist and smart merge cut
            // it down to `chapters`.
            val allChapters: List<Chapter> = emptyList(),
            // KMK <--
            val availableScanlators: ImmutableSet<String>,
            val excludedScanlators: ImmutableSet<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val hasPromptedToAddBefore: Boolean = false,
            val hideMissingChapters: Boolean = false,

            // SY -->
            val meta: RaisedSearchMetadata?,
            val mergedData: MergedMangaData?,
            val showRecommendationsInOverflow: Boolean,
            val showMergeInOverflow: Boolean,
            val showMergeWithAnother: Boolean,
            val pagePreviewsState: PagePreviewState,
            val alwaysShowReadingProgress: Boolean,
            val previewsRowCount: Int,
            // SY <--
            // KMK -->
            /**
             * status of fetching related mangas
             * - null: not started
             * - false: started & fetching
             * - true: finished
             */
            val isRelatedMangasFetched: Boolean? = null,
            /**
             * a list of <keyword, related mangas>
             */
            val relatedMangaCollection: List<RelatedManga>? = null,
            val sequelPrequelEntries: List<SequelPrequelEntry>? = null,
            // KMK --> lowercase library titles for the sequel/prequel "In library" badge
            val sequelPrequelLibraryTitles: Set<String> = emptySet(),
            val seedColor: Color? = manga.asMangaCover().vibrantCoverColor?.let { Color(it) },
            // KMK --> Manga-details metadata translation state (spec 2026-09-23).
            val mangaInfoUiState: MangaInfoUiState = MangaInfoUiState.Unresolved,
            // KMK --> Empty means "no colour filter". Deliberately local state rather
            // than chapter_flags bits: that column is synced, and the colour itself
            // is not, so filtering there would leak the choice to other devices.
            val bookmarkColorFilter: Set<BookmarkColor> = emptySet(),
            // KMK <--
        ) : State {
            // KMK -->
            /**
             * a value of null will be treated as still loading, so if all searching were failed and won't update
             * 'relatedMangaCollection` then we should return empty list
             */
            val relatedMangasSorted = relatedMangaCollection
                ?.sorted(manga)
                ?.removeDuplicates(manga)
                ?.filter { it.isVisible() }
                ?.isLoading(isRelatedMangasFetched)
                ?: if (isRelatedMangasFetched == true) emptyList() else null
            // KMK <--

            val processedChapters by lazy {
                chapters.applyFilters(manga, bookmarkColorFilter).toList()
                    // KMK -->
                    // safe-guard some edge-cases where chapters are duplicated some how on a merged entry
                    .distinctBy { it.id }
                // KMK <--
            }

            val isAnySelected by lazy {
                chapters.fastAny { it.selected }
            }

            val chapterListItems by lazy {
                if (hideMissingChapters) {
                    return@lazy processedChapters
                }

                processedChapters.insertSeparators { before, after ->
                    val (lowerChapter, higherChapter) = if (manga.sortDescending()) {
                        after to before
                    } else {
                        before to after
                    }
                    if (higherChapter == null) return@insertSeparators null

                    if (lowerChapter == null) {
                        floor(higherChapter.chapter.chapterNumber)
                            .toInt()
                            .minus(1)
                            .coerceAtLeast(0)
                    } else {
                        calculateChapterGap(higherChapter.chapter, lowerChapter.chapter)
                    }
                        .takeIf { it > 0 }
                        ?.let { missingCount ->
                            ChapterList.MissingCount(
                                id = "${lowerChapter?.id}-${higherChapter.id}",
                                count = missingCount,
                            )
                        }
                }
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()
        }
    }
}

// SY -->
data class MergedMangaData(
    val references: List<MergedMangaReference>,
    val manga: Map<Long, Manga>,
    val sources: List<Source>,
)
// SY <--

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        val downloadProgress: Int,
        val selected: Boolean = false,
        // SY -->
        val sourceName: String?,
        val showScanlator: Boolean,
        // SY <--
        val translationProgress: Int = 0,
        val isTranslating: Boolean = false,
        val isTranslationError: Boolean = false,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}

// SY -->
sealed interface PagePreviewState {
    data object Unused : PagePreviewState
    data object Loading : PagePreviewState
    data class Success(val pagePreviews: List<PagePreview>) : PagePreviewState
    data class Error(val error: Throwable) : PagePreviewState
}
// SY <--

// KMK -->
sealed interface RelatedManga {
    data object Loading : RelatedManga

    data class Success(
        val keyword: String,
        val mangaList: List<Manga>,
    ) : RelatedManga {
        val isEmpty: Boolean
            get() = mangaList.isEmpty()

        companion object {
            suspend fun fromPair(
                pair: Pair<String, List<SManga>>,
                toManga: suspend (mangaList: List<SManga>) -> List<Manga>,
            ) = Success(pair.first, toManga(pair.second))
        }
    }

    fun isVisible(): Boolean {
        return this is Loading || (this is Success && !this.isEmpty)
    }

    companion object {
        internal fun List<RelatedManga>.sorted(manga: Manga): List<RelatedManga> {
            val success = filterIsInstance<Success>()
            val loading = filterIsInstance<Loading>()
            val title = manga.title.lowercase()
            val ogTitle = manga.ogTitle.lowercase()
            return success.filter { it.keyword.isEmpty() } +
                success.filter { it.keyword.lowercase() == title } +
                success.filter { it.keyword.lowercase() == ogTitle && ogTitle != title } +
                success.filter { it.keyword.isNotEmpty() && it.keyword.lowercase() !in listOf(title, ogTitle) }
                    .sortedByDescending { it.keyword.length }
                    .sortedBy { it.mangaList.size } +
                loading
        }

        internal fun List<RelatedManga>.removeDuplicates(manga: Manga): List<RelatedManga> {
            val mangaIds = HashSet<Long>().apply { add(manga.id) }

            return map { relatedManga ->
                if (relatedManga is Success) {
                    Success(
                        relatedManga.keyword,
                        relatedManga.mangaList
                            .filter { mangaIds.add(it.id) },
                    )
                } else {
                    relatedManga
                }
            }
        }

        internal fun List<RelatedManga>.isLoading(isRelatedMangaFetched: Boolean?): List<RelatedManga> {
            return if (isRelatedMangaFetched == false) this + listOf(Loading) else this
        }
    }
}
// KMK <--
