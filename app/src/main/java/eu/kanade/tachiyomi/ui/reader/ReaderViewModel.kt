package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.annotation.ColorInt
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.connections.service.WebhookPreferences
import eu.kanade.domain.manga.interactor.CompleteRereadIfNeeded
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.event.AppEvent
import eu.kanade.tachiyomi.data.event.AppEventBus
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.data.webhook.WebhookNotifier
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.TextPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.MAX_FILE_NAME_BYTES
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.getMainSource
import exh.source.isEhBasedManga
import exh.source.isMergedSourceId
import exh.util.defaultReaderType
import exh.util.mangaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.archive.EpubArchiveUrls
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.storage.UniFileTempFileManager
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.applyScanlatorVisibility
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.Date

/**
 * Presenter used by the activity to perform background operations.
 */
@AssistedInject
class ReaderViewModel(
    @Assisted private val savedState: SavedStateHandle,
    private val context: Context,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val tempFileManager: UniFileTempFileManager,
    private val imageSaver: ImageSaver,
    val readerPreferences: ReaderPreferences,
    private val basePreferences: BasePreferences,
    private val downloadPreferences: DownloadPreferences,
    private val trackPreferences: TrackPreferences,
    private val trackChapter: TrackChapter,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getNextChapters: GetNextChapters,
    private val upsertHistory: UpsertHistory,
    private val updateChapter: UpdateChapter,
    // KMK -->
    private val completeRereadIfNeeded: CompleteRereadIfNeeded,
    // KMK <--
    private val setMangaViewerFlags: SetMangaViewerFlags,
    private val getIncognitoState: GetIncognitoState,
    private val libraryPreferences: LibraryPreferences,
    // SY -->
    private val syncPreferences: SyncPreferences,
    private val uiPreferences: UiPreferences,
    private val getFlatMetadataById: GetFlatMetadataById,
    private val getMergedMangaById: GetMergedMangaById,
    private val getMergedReferencesById: GetMergedReferencesById,
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId,
    // SY <--
    private val coverManager: LocalCoverManager,
    private val updateManga: UpdateManga,
    private val coverCache: CoverCache,
    // KMK -->
    private val appEventBus: AppEventBus,
    private val webhookPreferences: WebhookPreferences,
    private val achievementManager: tachiyomi.domain.achievement.service.AchievementManager,
    private val achievementPreferences: tachiyomi.domain.achievement.service.AchievementPreferences,
    private val readerAchievementHandler: eu.kanade.tachiyomi.data.achievement.ReaderAchievementHandler,
    // KMK --> Per-category reading mode, for the override step in [getMangaReadingMode].
    private val categoryReaderPreferences: tachiyomi.domain.category.service.CategoryReaderPreferences,
    private val getCategories: tachiyomi.domain.category.interactor.GetCategories,
    // KMK <--
) : ViewModel() {

    @AssistedFactory
    @ViewModelAssistedFactoryKey(ReaderViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): ReaderViewModel {
            return create(extras.createSavedStateHandle())
        }

        fun create(@Assisted savedState: SavedStateHandle): ReaderViewModel
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    val currentChapter: Chapter?
        get() = state.value.currentChapter?.chapter?.toDomainChapter()

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    // KMK -->
    fun handleDownloadAction(chapter: Chapter, action: ChapterDownloadAction) {
        when (action) {
            ChapterDownloadAction.START -> downloadChapter(chapter)
            ChapterDownloadAction.START_NOW -> viewModelScope.launch {
                downloadManager.startDownloadNow(chapter.id)
            }
            ChapterDownloadAction.CANCEL -> cancelDownload(chapter.id)
            ChapterDownloadAction.DELETE -> deleteChapter(chapter)
        }
    }

    /**
     * @param chapter the chapter to download.
     */
    private fun downloadChapter(chapter: Chapter) {
        viewModelScope.launch {
            val manga = manga?.let {
                if (isMergedSourceId(it.source)) {
                    state.value.mergedManga?.get(chapter.mangaId) ?: return@launch
                } else {
                    it
                }
            } ?: return@launch
            downloadManager.downloadChapters(manga, listOf(chapter))
            downloadManager.startDownloads()
        }
    }

    private fun cancelDownload(chapterId: Long) {
        viewModelScope.launch {
            // Unlike other screens the reader keeps no per-chapter download state list,
            // so there is no local UI state to propagate after cancelling.
            downloadManager.cancelQueuedDownload(chapterId)
        }
    }

    private fun deleteChapter(chapter: Chapter) {
        viewModelScope.launchNonCancellable {
            try {
                val manga = if (manga?.let { isMergedSourceId(it.source) } == true) {
                    state.value.mergedManga?.get(chapter.mangaId) ?: return@launchNonCancellable
                } else {
                    manga ?: return@launchNonCancellable
                }
                val source = sourceManager.get(manga.source) ?: return@launchNonCancellable
                downloadManager.deleteChapters(
                    listOf(chapter),
                    manga,
                    source,
                    ignoreCategoryExclusion = true,
                )
                // KMK --> No in-reader chapter-list refresh after a local-source delete:
                // the list is an init() snapshot and deleteChapters only removes files (the DB
                // row persists until the next source fetch, which MangaViewModel already
                // triggers on its side). Rebuilding lists mid-read would swap ReaderChapter and
                // loader state under the open chapter; the reader re-reads on next open.
                // KMK <--
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }
    // KMK <--

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    // KMK -->
    private val webhookReadingTimeTracker = WebhookReadingTimeTracker()
    // KMK <--

    private var chapterToDownload: Download? = null

    private var unfilteredChapterListImpl: List<Chapter>? = null
    private val unfilteredChapterList: List<Chapter>
        get() = unfilteredChapterListImpl
            ?: error("unfilteredChapterList accessed before init() completed")

    /**
     * Chapter list for the active manga. Initialized in [init] to avoid blocking the UI thread.
     */
    private var chapterListImpl: List<ReaderChapter>? = null
    private val chapterList: List<ReaderChapter>
        get() = chapterListImpl
            ?: error("chapterList accessed before init() completed")

    /**
     * Initializes the chapter lists. Must be called from [init] (a suspend function on IO).
     */
    private suspend fun initializeChapterLists(manga: Manga) {
        // KMK -->
        unfilteredChapterListImpl = if (isMergedSourceId(manga.source)) {
            getMergedChaptersByMangaId.await(manga.id, dedupe = false, applyFilter = false)
        } else {
            getChaptersByMangaId.await(manga.id, applyFilter = false)
        }
        // Category ids, for the per-category reading-mode override (see [categoryReadingMode]).
        // One query at open: the reader does not re-resolve this mid-session, so a pref read on
        // every page turn is all that would be avoided by caching.
        runCatching {
            getCategories.await(manga.id).forEach { category ->
                mangaCategoryIds += category.id
                if (category.parentId != 0L) mangaCategoryParents[category.id] = category.parentId
            }
        }
        // KMK <--

        // SY -->
        val (chapters, mangaMap) = if (isMergedSourceId(manga.source)) {
            getMergedChaptersByMangaId.await(manga.id, applyFilter = true) to
                state.value.mergedManga
        } else {
            getChaptersByMangaId.await(manga.id, applyFilter = true) to null
        }
        fun isChapterDownloaded(chapter: Chapter): Boolean {
            val chapterManga = mangaMap?.get(chapter.mangaId) ?: manga
            return downloadManager.isChapterDownloaded(
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
                chapterUrl = chapter.url,
                mangaTitle = chapterManga.ogTitle,
                sourceId = chapterManga.source,
            )
        }
        // SY <--

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

// KMK -->
        val visibleChapters = chapters.applyScanlatorVisibility(
            priority = manga.scanlatorPriority,
            blacklistedChapters = manga.blacklistedChapters,
            rangeRules = manga.scanlatorRangeRules,
            mergeEnabled = libraryPreferences.smartScanlatorMerge().get(),
            alwaysVisibleChapterId = chapterId,
        )
        // KMK <--

        val chaptersForReader = when {
            (readerPreferences.skipRead().get() || readerPreferences.skipFiltered().get()) -> {
                val filteredChapters = visibleChapters.filterNot {
                    when {
                        readerPreferences.skipRead().get() && it.read -> true
                        readerPreferences.skipFiltered().get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                // SY -->
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !isChapterDownloaded(it)
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        isChapterDownloaded(it)
                                    ) ||
                                // SY <--
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> visibleChapters
        }

        chapterListImpl = chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe().get() && libraryPreferences.smartScanlatorMerge().get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly().get()) {
                    filterDownloaded(manga, mangaMap)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    // KMK --> Resolved per source rather than `by lazy`. The lazy captured `manga?.source` on
    // first read, and the first read happens before the chapter list has loaded - manga is still
    // null there, so [GetIncognitoState.await] fell back to the global switch only and a
    // per-extension incognito toggle was invisible for the rest of the session. That made
    // `incognito_reader` ("read 10 chapters in incognito") and its rotating tier unreachable:
    // the handler was always told the flag was false, and it writes no progress or history in
    // incognito, so the reads it counts were the only evidence there was.
    // Re-resolved whenever the source changes; the reader opens on one manga for its lifetime.
    // KMK <--
    private var incognitoSource: Long? = null
    private var incognitoResolved = false
    private var incognitoValue: Boolean = false

    val incognitoMode: Boolean
        get() {
            val source = manga?.source
            if (!incognitoResolved || incognitoSource != source) {
                incognitoValue = getIncognitoState.await(source)
                incognitoSource = source
                incognitoResolved = true
            }
            return incognitoValue
        }

    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading().get()

    // KMK -->
    private val chapterCompleteSoundPlayer: ChapterCompleteSoundPlayer by lazy {
        ChapterCompleteSoundPlayer(
            context = context,
            readerPreferences = readerPreferences,
        )
    }

    @Volatile
    private var pendingBetweenChapterSound: Boolean = false

    // KMK --> Per-session guards so achievement events fire once per chapter/manga,
    // not on every revisit of an already-read page.
    private val achievementCountedChapterIds = mutableSetOf<Long>()
    private val mangaCompletedFiredFor = mutableSetOf<Long>()
    // KMK <--

    // KMK --> Category ids of the open manga, and the parent of each subcategory among them, for
    // the per-category reading-mode override. Populated once at open by
    // [initializeChapterLists].
    private val mangaCategoryIds = mutableSetOf<Long>()
    private val mangaCategoryParents = mutableMapOf<Long, Long>()

    // KMK --> Chapters this session has completed.
    //
    // [unfilteredChapterList] is the DB list captured when the reader opened. Nothing writes back
    // to it, so every read decision made from it described the state at open time for as long as
    // the session lasted. Three things were wrong because of it:
    //
    //  - "finish this manga" could never fire on the chapter that completed it, because that
    //    chapter still read as unread in the snapshot
    //  - "started this manga" fired on every chapter of the first session, since the first
    //    chapter was the only one the snapshot thought was read
    //  - the spoiler check compared list positions of a snapshot that had not moved
    //
    // An id set is enough and cannot go stale: a chapter id is read or it is not, and the DB
    // write that follows is what persists it beyond the session.
    private val chaptersReadThisSession = mutableSetOf<Long>()
    // KMK <--

    // KMK --> One completion event per chapter per session: the webhook, the completion sound and
    // the started/finished notifications all live in [updateChapterProgressOnComplete], which the
    // viewer re-enters every time the last page is *displayed* - including the swipe back that
    // re-shows it after turning forward. That is one chapter finishing several times over.
    // KMK <--
    private val chapterCompletionHandledFor = mutableSetOf<Long>()

    init {
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            // SY -->
            .drop(1) // allow the loader to set the first page and chapter id
            // SY <-
            .onEach { currentChapter ->
                // Only restore the saved page when it belongs to this chapter. Without the
                // chapter check, the previous chapter's index leaks into every newly loaded
                // chapter (wrong landing page after each chapter change).
                if (chapterPageIndex >= 0 && chapterId == currentChapter.chapter.id) {
                    // Restore from SavedState.
                    // KMK --> ... in the list coordinates *from then*. The value was written by
                    // updateChapterProgress as positionOf(page), a live index that already
                    // includes every split segment inserted before it, while the list the fresh
                    // process rebuilds is the un-split one the loader persists. Past a tall page's
                    // split, that live index names a *later* page of the fresh list, so the reader
                    // resumed however many segments forward instead of where they left.
                    // last_page_read is the same page recorded in the coordinates the surviving
                    // list has ("written in the coordinates the chapter has on the next open"),
                    // so when it exists it pins the restore to the right page. The WebGPU viewer
                    // restores the precise offset on top of it through its position store, which
                    // setChaptersInternal consults under the same last_page_read > 0 gate.
                    // Only the < branch is a shift: an equal value is the same page, and a smaller
                    // one means the process died before the progress write (incognito skips the
                    // write entirely), where page_index is the only record there is.
                    val lastRead = currentChapter.chapter.last_page_read
                    currentChapter.requestedPage = if (lastRead in 1 until chapterPageIndex) {
                        lastRead
                    } else {
                        chapterPageIndex
                    }
                    // KMK <--
                } else if (!currentChapter.chapter.read) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                }
                chapterId = currentChapter.chapter.id!!
            }
            .launchIn(viewModelScope)

        // SY -->
        state.mapLatest { it.ehAutoscrollFreq }
            .distinctUntilChanged()
            .drop(1)
            .onEach { text ->
                val parsed = text.toDoubleOrNull()

                if (parsed == null || parsed <= 0 || parsed > 9999) {
                    readerPreferences.autoscrollInterval().set(-1f)
                    mutableState.update { it.copy(isAutoScrollEnabled = false) }
                } else {
                    readerPreferences.autoscrollInterval().set(parsed.toFloat())
                    mutableState.update { it.copy(isAutoScrollEnabled = true) }
                }
            }
            .launchIn(viewModelScope)
        // SY <--

        // KMK -->
        // Force player construction so sounds are decoded before the first
        // chapter completes; lazy init on completion would skip playing.
        if (readerPreferences.chapterCompletionSound().get()) {
            chapterCompleteSoundPlayer
        }
        // KMK <--
    }

    override fun onCleared() {
        // KMK -->
        chapterCompleteSoundPlayer.release()
        // KMK <--
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        deletePendingChapters()
    }

    /**
     * Whether this presenter is initialized yet.
     */
    fun needsInit(): Boolean {
        return manga == null
    }

    /**
     * Initializes this presenter with the given [mangaId] and [initialChapterId]. This method will
     * fetch the manga from the database and initialize the initial chapter.
     */
    suspend fun init(mangaId: Long, initialChapterId: Long /* SY --> */, page: Int?/* SY <-- */): Result<Boolean> {
        if (!needsInit()) return Result.success(true)
        return withIOContext {
            try {
                val manga = getManga.await(mangaId)
                if (manga != null) {
                    // SY -->
                    sourceManager.isInitialized.first { it }
                    val source = sourceManager.getOrStub(manga.source)
                    val metadataSource = source.getMainSource<MetadataSource<*, *>>()
                    val metadata = if (metadataSource != null) {
                        getFlatMetadataById.await(mangaId)?.raise(metadataSource.metaClass)
                    } else {
                        null
                    }
                    val mergedReferences = if (source is MergedSource) {
                        getMergedReferencesById.await(manga.id)
                    } else {
                        emptyList()
                    }
                    val mergedManga = if (source is MergedSource) {
                        getMergedMangaById.await(manga.id)
                            .associateBy { it.id }
                    } else {
                        null
                    }
                    val relativeTime = uiPreferences.relativeTime().get()
                    val autoScrollFreq = readerPreferences.autoscrollInterval().get()
                    // SY <--
                    mutableState.update {
                        it.copy(
                            manga = manga,
                            // SY -->
                            meta = metadata,
                            mergedManga = mergedManga,
                            dateRelativeTime = relativeTime,
                            ehAutoscrollFreq = if (autoScrollFreq == -1f) {
                                ""
                            } else {
                                autoScrollFreq.toString()
                            },
                            isAutoScrollEnabled = autoScrollFreq != -1f,
                            // SY <--
                        )
                    }
                    if (chapterId == -1L) chapterId = initialChapterId

                    // Initialize chapter lists on IO thread to avoid blocking UI
                    initializeChapterLists(manga)

                    // val source = sourceManager.getOrStub(manga.source)
                    loader = ChapterLoader(
                        context = context,
                        downloadManager = downloadManager,
                        downloadProvider = downloadProvider,
                        manga = manga,
                        source = source,
                        // SY -->
                        sourceManager = sourceManager,
                        readerPrefs = readerPreferences,
                        mergedReferences = mergedReferences,
                        mergedManga = mergedManga,
                        // SY <--
                    )

                    loadChapter(
                        loader!!,
                        chapterList.first { chapterId == it.chapter.id },
                        // SY -->
                        page,
                        // SY <--
                    )
                    Result.success(true)
                } else {
                    // Unlikely but okay
                    Result.success(false)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                // Mihon -->
                mutableState.update { it.copy(initError = e) }
                // Mihon <--
                Result.failure(e)
            }
        }
    }

    // SY -->
    fun getChapters(): List<ReaderChapterItem> {
        // KMK -->
        val manga = manga ?: return emptyList()
        val mangaList = state.value.mergedManga?.takeIf { it.isNotEmpty() } ?: mapOf(manga.id to manga)
        // KMK <--

        val currentChapter = getCurrentChapter()

        return chapterList.map {
            ReaderChapterItem(
                chapter = it.chapter.toDomainChapter()!!,
                // KMK -->
                manga = mangaList[it.chapter.manga_id] ?: manga,
                // KMK <--
                isCurrent = it.chapter.id == currentChapter?.chapter?.id,
                dateFormat = UiPreferences.dateFormat(uiPreferences.dateFormat().get()),
            )
        }
    }
    // SY <--

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
        // SY -->
        page: Int? = null,
        // SY <--
    ): ViewerChapters {
        loader.loadChapter(chapter /* SY --> */, page/* SY <-- */)

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                )
            }
        }
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        // KMK -->
        if (pendingBetweenChapterSound) {
            pendingBetweenChapterSound = false
            chapterCompleteSoundPlayer.playChapterCompleteSound()
        }
        // KMK <--
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()

            // KMK -->
            // Chapter switch: the previous chapter's reading time scope ends here.
            resetWebhookReadingTime()
            // KMK <--
            restartReadTimer()

            // KMK -->
            if (chapter.chapter.last_page_read == 0 && !chapter.chapter.read) {
                manga?.let { currentManga ->
                    appEventBus.emit(
                        AppEvent.ChapterStarted(
                            mangaTitle = currentManga.title,
                            chapterName = chapter.chapter.name,
                            sourceId = currentManga.source,
                            mangaId = currentManga.id,
                        ),
                    )
                }
            }
            // KMK <--

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun loadNewChapterFromDialog(chapter: Chapter) {
        viewModelScope.launchIO {
            val newChapter = chapterList.firstOrNull { it.chapter.id == chapter.id } ?: return@launchIO
            loadAdjacent(newChapter)
        }
    }

    /**
     * Called by a viewer that has reached a chapter boundary itself.
     *
     * KMK --> The novel viewer has no transition page: a book is a continuous scroll with no place to
     * draw one, so "the reader reached the end" has to reach the presenter from the viewer. Going
     * through [loadAdjacent] rather than through `onPageSelected` is deliberate — that path returns
     * early while `chapter.pages` is still null, which for a chapter that has not been loaded yet is
     * exactly the case here, so the switch would silently do nothing.
     *
     * Reuses the same call the next/previous toolbar buttons make, so the progress dialog, the
     * history update and the read-timer reset all behave identically whichever way the reader got
     * there.
     * KMK <--
     */
    fun goToAdjacentChapter(chapter: ReaderChapter) {
        viewModelScope.launchIO { loadAdjacent(chapter) }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        /*
         * This code is likely deprecated since once `chapter.pageLoader` is initialized with [HttpPageLoader],
         * it would set `chapter.state` to `Loading` or `Loaded` and return early already.
         */
        if (chapter.pageLoader?.isLocal == false) {
            val manga = state.value.mergedManga?.get(chapter.chapter.manga_id) ?: manga ?: return
            val dbChapter = chapter.chapter
            val isDownloaded = downloadManager.isChapterDownloaded(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                // SY -->
                manga.ogTitle,
                // SY <--
                manga.source,
                skipCache = true,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage, currentPageText: String /* SY --> */, hasExtraPage: Boolean /* SY <-- */) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        // SY -->
        mutableState.update { it.copy(currentPageText = currentPageText) }
        // SY <--

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page/* SY --> */, hasExtraPage/* SY <-- */)
        }

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val inDownloadRange = selectedChapter.displayNumber(page).toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        // KMK -->
        val mangas = state.value.mergedManga ?: mapOf(manga.id to manga)
        val nextChapterManga = mangas[nextChapter.manga_id] ?: return
        // KMK <--

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                // KMK -->
                nextChapterManga.ogTitle,
                nextChapterManga.source,
                // KMK <--
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(
                manga.id,
                nextChapter.id!!,
                mergeEnabled = libraryPreferences.smartScanlatorMerge().get(),
            ).run {
                if (readerPreferences.skipDupe().get() && libraryPreferences.smartScanlatorMerge().get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            // KMK -->
            chaptersToDownload.groupBy { it.mangaId }.forEach { (mangaId, chapters) ->
                val chapterManga = mangas[mangaId] ?: return@forEach
                downloadManager.downloadChapters(
                    chapterManga,
                    chapters,
                )
            }
            // KMK <--
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete.
     *
     * This deletes chapters from reading list (filtered, unduplicated if any set).
     *
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots().get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    // KMK -->
    /**
     * Deletes duplicate chapters when `removeAfterReadSlots` = "Last read chapter" (0).
     *
     * Ignore the case where `removeAfterReadSlots` > 0 while `skipDupe` = true as we don't know
     * where the chapters to be deleted are in the filtered [chapterList].
     *
     * For the case where `skipDupe` = false, chapters at should be deleted normally by [deleteChapterIfNeeded]
     * based on the `removeAfterReadSlots` offset while the user is reading sequentially.
     */
    private fun deleteDupChapterIfNeeded(chapterToDelete: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots().get()
        if (removeAfterReadSlots != 0) return
        enqueueDeleteReadChapters(chapterToDelete)
    }
    // KMK <--

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(
        readerChapter: ReaderChapter,
        page: ReaderPage,
        // SY -->
        hasExtraPage: Boolean,
        // SY <--
    ) {
        // A list position, not Page.index: this value is persisted as last_page_read, and a page
        // inserted by a tall-image split carries a synthetic index that would be written as-is.
        val pageIndex = readerChapter.positionOf(page).takeIf { it >= 0 } ?: page.index
        val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()
        val isSyncEnabled = syncPreferences.isSyncEnabled()

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        // KMK --> Whether this page finishes the chapter, resolved before the incognito branch
        // below. The achievement hook needs it in both modes: incognito deliberately writes no
        // progress or history, so the old "fire from inside the !incognito block" arrangement
        // meant an incognito completion was never observed at all - which left incognito_reader
        // and the incognito rotating tier unearnable, since nothing else calls the handler.
        val reachedLastPage = readerChapter.pages?.lastIndex == pageIndex ||
            // SY -->
            (hasExtraPage && readerChapter.pages?.lastIndex?.minus(1) == pageIndex)
        // SY <--

        if (!incognitoMode && page.status !is Page.State.Error) {
            // Written in the coordinates the list will have on the next open, not the ones it has
            // now: a split shifted every position after it, and the list that survives the close is
            // the un-split one. pageIndex stays above because everything else here - the progress
            // display, requestedPage, and the read check below - is about the list as it stands.
            readerChapter.chapter.last_page_read = readerChapter.savedIndexOf(page)

            if (reachedLastPage) {
                updateChapterProgressOnComplete(readerChapter)

                // SY -->
                // Check if syncing is enabled for chapter read:
                if (isSyncEnabled && syncTriggerOpt.syncOnChapterRead) {
                    SyncDataJob.startNow(context)
                }
                // SY <--
            }

            updateChapter.await(
                ChapterUpdate(
                    id = readerChapter.chapter.id!!,
                    read = readerChapter.chapter.read,
                    lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                ),
            )

            // KMK -->
            if (readerChapter.chapter.read) {
                manga?.let { currentManga ->
                    viewModelScope.launchNonCancellable { completeRereadIfNeeded.await(currentManga.id) }
                }
            }
            // KMK <--
            // SY -->
            // Check if syncing is enabled for chapter open:
            if (isSyncEnabled && syncTriggerOpt.syncOnChapterOpen && readerChapter.chapter.last_page_read == 0) {
                SyncDataJob.startNow(context)
            }
            // SY <--
        }

        // KMK --> The achievement hook, outside the incognito branch and driven by the same
        // last-page test as the progress write above. Keyed on the chapter id so a chapter that
        // is re-displayed - page turned back and forth, or revisited in a later session - counts
        // once. `mangaId` starts the speedrun clock; 0 simply means no clock.
        if (reachedLastPage && page.status !is Page.State.Error) {
            val chapterIdValue = readerChapter.chapter.id
            if (chapterIdValue != null && achievementCountedChapterIds.add(chapterIdValue)) {
                val mangaIdValue = manga?.id ?: 0L
                val readingMode = getMangaReadingMode()
                viewModelScope.launchNonCancellable {
                    readerAchievementHandler.onChapterRead(readingMode, incognitoMode, mangaIdValue)
                }
            }
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        // KMK --> Completion side effects (webhook, sound, started/finished events) fire once per
        // chapter. The viewer calls this again every time the last page is *displayed*, so
        // swiping forward and back re-ran all of it: the webhook fired again and the completion
        // sound played again for a chapter that had already finished. The read flag is still set
        // every time (it is idempotent) and the DB write below still persists the page position,
        // so only the notification side of a completion is gated.
        // KMK <--
        val chapterIdValue = readerChapter.chapter.id
        val firstCompletion = chapterIdValue != null && chapterCompletionHandledFor.add(chapterIdValue)
        // KMK --> Read within this session, for the decisions that consult the snapshot below.
        // KMK <--
        if (chapterIdValue != null) chaptersReadThisSession += chapterIdValue
        if (!firstCompletion) return
        // KMK -->
        val deferBetweenChapters = readerPreferences.chapterCompletionSoundRandomLocation().get() &&
            kotlin.random.Random.nextBoolean()
        // If defer is chosen but there is no next chapter, fall back to immediate playback
        // so the sound is not lost when the manga is finished.
        val hasNextChapter = chapterList.any { it.chapter.id != readerChapter.chapter.id && !it.chapter.read } ||
            state.value.viewerChapters?.nextChapter != null
        if (deferBetweenChapters && hasNextChapter) {
            pendingBetweenChapterSound = true
        } else {
            chapterCompleteSoundPlayer.playChapterCompleteSound()
        }
        manga?.let { currentManga ->
            // KMK --> "First chapter of the series", counted against the snapshot *plus* what has
            // been read this session. The snapshot alone reported the same answer for every
            // chapter of the opening session - it showed nothing read, because it is the state as
            // of open time - so MangaStarted, and the "new manga" webhook built on it, fired for
            // every chapter the user finished until they closed the reader.
            // KMK <--
            val wasFirstReadChapter = unfilteredChapterList.count { chapter ->
                chapter.id != readerChapter.chapter.id &&
                    (chapter.read || chapter.id in chaptersReadThisSession)
            } == 0
            val chapterData = buildMap {
                put("manga", currentManga.title)
                put("chapter", readerChapter.chapter.name)
                if (webhookPreferences.includeReadingTime().get()) {
                    // KMK -->
                    // Accumulated active reading time (backgrounded spans excluded),
                    // consumed here so the next chapter starts fresh.
                    val totalSeconds = webhookReadingTimeTracker.consumeTotalSeconds()
                    if (totalSeconds > 0) {
                        put("time_spent", WebhookNotifier.formatReadingDuration(totalSeconds))
                        put("time_spent_seconds", totalSeconds.toString())
                    }
                    // KMK <--
                }
            }
            appEventBus.emit(
                AppEvent.ChapterRead(
                    webhookData = chapterData,
                    sourceId = currentManga.source,
                    mangaId = currentManga.id,
                ),
            )
            if (wasFirstReadChapter) {
                appEventBus.emit(
                    AppEvent.MangaStarted(
                        mangaTitle = currentManga.title,
                        chapterName = readerChapter.chapter.name,
                        sourceId = currentManga.source,
                        mangaId = currentManga.id,
                    ),
                )
            }
            // KMK --> "Skip to the last chapter without reading the middle". Needs the whole
            // list, so it is judged here rather than on a page turn: the completed chapter has
            // to be the final one, and something before it has to still be unread.
            //
            // Judged on chapter *numbers*, not on list position. `unfilteredChapterList` arrives
            // ordered by `source_order`, which is the order the source returned - for most sources
            // newest first, so its last element is chapter 1, not the final chapter. The old test
            // (`completedIndex == lastIndex && any unread before it`) therefore only ever fired
            // for whoever finished chapter 1, and mislabelled a legitimate read of the last
            // chapter of a series whose middle was already read. A recognized chapter number is
            // order-independent, which is what "the last chapter" has to mean.
            //
            // `readerChapter.chapter` is the legacy [Chapter] here, so the number is spelled
            // `chapter_number` and "recognized" uses the same `>= 0f` rule the domain model's
            // `isRecognizedNumber` is; widened to Double so it compares against the list below on
            // one type.
            val completedNumber = readerChapter.chapter.chapter_number.toDouble()
            val isFinalChapter = readerChapter.chapter.chapter_number >= 0f &&
                unfilteredChapterList.none { it.isRecognizedNumber && it.chapterNumber > completedNumber }
            val skippedEarlier = unfilteredChapterList.any { chapter ->
                chapter.id != readerChapter.chapter.id &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber < completedNumber &&
                    !chapter.read &&
                    chapter.id !in chaptersReadThisSession
            }
            if (isFinalChapter && skippedEarlier) {
                runCatching { achievementManager.incrementCounter("spoiler_jumps") }
                // The perfect-day rotating tier reads "10 chapters without skipping", so a skip
                // has to be recorded as well as counted - otherwise the day still qualifies.
                runCatching { achievementManager.onChapterSkipped() }
            }
            // KMK <--
            // KMK --> "Every chapter read" against the snapshot *plus* this session's reads. The snapshot
            // alone could never satisfy it for the chapter that completed the series - that row
            // was still `read = false` as of open time - so finishing a series never registered
            // until the reader had been closed and reopened.
            // KMK <--
            if (unfilteredChapterList.isNotEmpty() &&
                unfilteredChapterList.all { it.read || it.id in chaptersReadThisSession } &&
                !currentManga.rereading &&
                mangaCompletedFiredFor.add(currentManga.id)
            ) {
                val isPermanent = try {
                    achievementManager.isPermanentStatus(currentManga.status)
                } catch (_: Exception) {
                    true
                }
                appEventBus.emit(
                    AppEvent.MangaCompleted(
                        mangaTitle = currentManga.title,
                        finished = isPermanent,
                        sourceId = currentManga.source,
                        mangaId = currentManga.id,
                    ),
                )
                // KMK --> The chapter list is in scope and is exactly what the length tiers
                // ("single-chapter", "200+", "500+") are measured against. The manga id makes
                // the completion countable once - the reader redraws this last page every time
                // the series is reopened - and the reading mode resolves the "complete a manga
                // in <mode>" tiers, which cannot be judged at a chapter read.
                readerAchievementHandler.onMangaCompleted(
                    status = currentManga.status,
                    mangaId = currentManga.id,
                    totalChapters = unfilteredChapterList.size.toLong(),
                    readingModeFlag = getMangaReadingMode(),
                )
                // KMK <--
            }
        }
        // KMK <--
        // SY -->
        if (manga?.isEhBasedManga() == true) {
            viewModelScope.launchNonCancellable {
                val chapterUpdates = unfilteredChapterList
                    .filter { it.sourceOrder > readerChapter.chapter.source_order }
                    .map { chapter ->
                        ChapterUpdate(id = chapter.id, read = true)
                    }
                updateChapter.awaitAll(chapterUpdates)
            }
        }
        // SY <--

        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead().get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
                ) {
                    ChapterUpdate(id = chapter.id, read = true)
                        // KMK -->
                        .also { deleteDupChapterIfNeeded(ReaderChapter(chapter.copy(read = true))) }
                    // KMK <--
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    fun restartReadTimer() {
        chapterReadStartTime = Instant.now().toEpochMilli()
        // KMK -->
        // Resuming must never discard already-banked reading time; it only opens a
        // new active segment (no-op when one is already open).
        webhookReadingTimeTracker.resume()
        // KMK <--
    }

    // KMK -->
    /**
     * Banks the currently open reading segment. Called when the reader loses
     * foreground (activity onPause) so backgrounded time is paused, not counted.
     */
    fun pauseWebhookReadingTime() {
        webhookReadingTimeTracker.pause()
    }

    /**
     * Discards accumulated reading time. Called when switching chapters, mirroring
     * the per-chapter scope of the history session timer.
     */
    fun resetWebhookReadingTime() {
        webhookReadingTimeTracker.reset()
    }
    // KMK <--

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    suspend fun updateHistory() {
        getCurrentChapter()?.let { readerChapter ->
            if (incognitoMode) return@let

            val chapterId = readerChapter.chapter.id!!
            val endTime = Date()
            val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

            upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
            // KMK --> Whole minutes, and no rounding up. `coerceAtLeast(1L)` banked a minute for
            // every chapter, including one abandoned seconds in, which is what made the reading
            // time tiers (1h .. 1000h) and the rank that reads the total reachable without the
            // reading. Sub-minute sessions now bank nothing; the remainder is lost rather than
            // credited early, which is the same rule the webhook's second-level counter uses.
            // KMK <--
            if (sessionReadDuration >= MINUTE_MS) {
                readerAchievementHandler.onReadingTimeMinutes(sessionReadDuration / MINUTE_MS)
            }
            chapterReadStartTime = null
        }
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = manga?.source?.let { sourceManager.getOrStub(it) } as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = if (manga?.let { isMergedSourceId(it.source) } == true) {
            state.value.mergedManga?.get(sChapter.manga_id)?.source?.let { sourceId ->
                sourceManager.getOrStub(sourceId) as? HttpSource
            }
        } else {
            getSource()
        } ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val bookmarked = !chapter.bookmark
        chapter.bookmark = bookmarked

        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!,
                    bookmark = bookmarked,
                ),
            )
        }

        mutableState.update {
            it.copy(
                bookmarked = bookmarked,
            )
        }
    }

    // SY -->
    fun toggleBookmark(chapterId: Long, bookmarked: Boolean) {
        val chapter = chapterList.find { it.chapter.id == chapterId }?.chapter ?: return
        chapter.bookmark = bookmarked
        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapterId,
                    bookmark = bookmarked,
                ),
            )
        }
    }
    // SY <--

    /**
     * Returns the viewer position used by this manga or the default one.
     *
     * Per-manga first, then the manga's categories, then the global default. The middle step is
     * the per-category override: a category can name a reading mode (webtoon for a long-strip
     * shelf, vertical for a manhwa one) and every manga in it opens that way unless the manga
     * itself overrides. The stored value never becomes the manga's own `readingMode`, so removing
     * the category override restores the global default rather than leaving a copy behind.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode().get()
        val manga = manga ?: return default
        val readingMode = ReadingMode.fromPreference(manga.readingMode.toInt())
        // SY -->
        return when {
            resolveDefault && readingMode == ReadingMode.DEFAULT && readerPreferences.useAutoWebtoon().get() -> {
                manga.defaultReaderType(manga.mangaType(sourceName = sourceManager.get(manga.source)?.name))
                    ?: categoryReadingMode()
                    ?: default
            }
            resolveDefault && readingMode == ReadingMode.DEFAULT -> categoryReadingMode() ?: default
            else -> manga.readingMode.toInt()
        }
        // SY <--
    }

    // KMK -->
    /**
     * The reading mode configured on any of this manga's categories, or null when none is set.
     *
     * Resolved from the categories fetched at init rather than from a live query: the reader opens
     * on one manga, and a category change mid-session is not something the viewer should pick up
     * under the user mid-page.
     */
    private fun categoryReadingMode(): Int? {
        if (mangaCategoryIds.isEmpty()) return null
        val parentOf = mangaCategoryIds.mapNotNull { id -> mangaCategoryParents[id]?.let { id to it } }.toMap()
        val stored = categoryReaderPreferences.resolveForManga(mangaCategoryIds.toList(), parentOf) ?: return null
        // Stored as ReadingMode's flag value; anything unrecognised (an older build's string, a
        // hand-edited pref) falls through to the global default rather than pinning the viewer.
        val flag = stored.toIntOrNull() ?: return null
        val mode = ReadingMode.fromPreference(flag)
        return if (mode == ReadingMode.DEFAULT) null else mode.flagValue
    }

    // KMK <--

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        viewModelScope.launch {
            withIOContext {
                setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
                val currChapters = state.value.viewerChapters
                if (currChapters != null) {
                    // Save current page: chapterPageIndex, not last_page_read. The chapter is not
                    // being reloaded here, so its page list is the live one and requestedPage is
                    // indexed against that - while last_page_read is stored in un-split coordinates,
                    // which in a chapter holding a tall image would land a page or two early.
                    val currChapter = currChapters.currChapter
                    currChapter.requestedPage = chapterPageIndex.takeIf { it >= 0 }
                        ?: currChapter.chapter.last_page_read

                    mutableState.update {
                        it.copy(
                            manga = getManga.await(manga.id),
                            viewerChapters = currChapters,
                        )
                    }
                    eventChannel.send(Event.ReloadViewerChapters)
                }
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType().get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page: chapterPageIndex, not last_page_read, for the same reason as
                // above - the live list is not being replaced, so the live position is the one that
                // means something here.
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = chapterPageIndex.takeIf { it >= 0 }
                    ?: currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    // SY -->
    fun toggleCropBorders(): Boolean {
        val readingMode = getMangaReadingMode()
        val isPagerType = ReadingMode.isPagerType(readingMode)
        val isWebtoon = ReadingMode.WEBTOON.flagValue == readingMode
        return if (isPagerType) {
            readerPreferences.cropBorders().toggle()
        } else if (isWebtoon) {
            readerPreferences.cropBordersWebtoon().toggle()
        } else {
            readerPreferences.cropBordersContinuousVertical().toggle()
        }
    }
    // SY <--

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.chapter.displayNumber(page)}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    // SY -->
    fun showEhUtils(visible: Boolean) {
        mutableState.update { it.copy(ehUtilsVisible = visible) }
    }

    fun setIndexChapterToShift(index: Long?) {
        mutableState.update { it.copy(indexChapterToShift = index) }
    }

    fun setIndexPageToShift(index: Int?) {
        mutableState.update { it.copy(indexPageToShift = index) }
    }

    fun openChapterListDialog() {
        mutableState.update { it.copy(dialog = Dialog.ChapterList) }
    }

    fun setDoublePages(doublePages: Boolean) {
        mutableState.update { it.copy(doublePages = doublePages) }
    }

    fun openAutoScrollHelpDialog() {
        mutableState.update { it.copy(dialog = Dialog.AutoScrollHelp) }
    }

    fun openBoostPageHelp() {
        mutableState.update { it.copy(dialog = Dialog.BoostPageHelp) }
    }

    fun openRetryAllHelp() {
        mutableState.update { it.copy(dialog = Dialog.RetryAllHelp) }
    }

    fun toggleAutoScroll(enabled: Boolean) {
        mutableState.update { it.copy(autoScroll = enabled) }
    }

    fun setAutoScrollFrequency(frequency: String) {
        mutableState.update { it.copy(ehAutoscrollFreq = frequency) }
    }
    // SY <--

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage/* SY --> */, extraPage: ReaderPage? = null/* SY <-- */) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page, extraPage)) }
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    // KMK --> Text chapters get a table of contents and an in-chapter search. Both are opt-in by the
    // chapter: a source that cannot produce one must not put a button on screen that opens nothing, so
    // the activity only offers these when the loader says it can answer.
    fun openNovelContentsDialog() {
        mutableState.update { it.copy(dialog = Dialog.NovelContents) }
    }

    fun openNovelSearchDialog() {
        mutableState.update { it.copy(dialog = Dialog.NovelSearch) }
    }

    /** The full-screen viewer for an illustration the reader long-pressed on inside the open book. */
    fun openNovelImageDialog(imageUrl: String) {
        mutableState.update { it.copy(dialog = Dialog.NovelImageView(imageUrl)) }
    }
    // KMK <--

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage(useExtraPage: Boolean) {
        // SY -->
        val page = if (useExtraPage) {
            (state.value.dialog as? Dialog.PageActions)?.extraPage
        } else {
            (state.value.dialog as? Dialog.PageActions)?.page
        }
        // SY <--
        // KMK --> The page's bytes are the precondition for every action in this sheet, and a text
        // chapter's page is a document with no image stream — so the stream, not the page status, is
        // what decides whether there is anything to act on. `page.stream!!` below used to be the
        // check, which crashed on the IO worker with a bare NPE the first time a book reached here.
        val readyPage = page?.takeIf { it.status == Page.State.Ready } ?: return
        val stream = readyPage.stream ?: return
        // KMK <--
        val manga = manga ?: return

        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, readyPage)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga().get()) {
            DiskUtil.buildValidFilename(manga.title)
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = stream,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    // SY -->
    fun saveImages() {
        val (firstPage, secondPage) = (state.value.dialog as? Dialog.PageActions ?: return)
        val viewer = state.value.viewer as? PagerViewer ?: return
        val isLTR = (viewer !is R2LPagerViewer) xor (viewer.config.invertDoublePages)
        val bg = viewer.config.pageCanvasColor

        if (firstPage.status != Page.State.Ready) return
        if (secondPage?.status != Page.State.Ready) return

        val manga = manga ?: return

        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = saveImages(
                    page1 = firstPage,
                    page2 = secondPage,
                    isLTR = isLTR,
                    bg = bg,
                    matchHeights = viewer.config.matchDoublePageHeights,
                    location = Location.Pictures.create(DiskUtil.buildValidFilename(manga.title)),
                    manga = manga,
                )
                eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    private fun saveImages(
        page1: ReaderPage,
        page2: ReaderPage,
        isLTR: Boolean,
        @ColorInt bg: Int,
        matchHeights: Boolean,
        location: Location,
        manga: Manga,
    ): Uri {
        // KMK --> A missing stream is an error with a message, not a null dereference: this runs on
        // the IO worker inside a coroutine, so the caller's `try` is long gone and an NPE here takes
        // the process down with nothing in the log but a type name.
        val stream1 = page1.stream ?: throw Exception("Page ${page1.number} has no image stream")
        ImageUtil.findImageType(stream1) ?: throw Exception("Not an image")
        val stream2 = page2.stream ?: throw Exception("Page ${page2.number} has no image stream")
        ImageUtil.findImageType(stream2) ?: throw Exception("Not an image")
        val imageBitmap = ImageDecoder.newInstance(stream1())?.decode()!!
        val imageBitmap2 = ImageDecoder.newInstance(stream2())?.decode()!!

        val chapter = page1.chapter.chapter

        // Build destination file.
        val filenameSuffix = " - ${page1.number}-${page2.number}.jpg"
        val filename = DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix

        return imageSaver.save(
            image = Image.Page(
                inputStream = { ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, isLTR, 0, bg, matchHeights).inputStream() },
                name = filename,
                location = location,
            ),
        )
    }
    // SY <--

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(
        copyToClipboard: Boolean,
        // SY -->
        useExtraPage: Boolean,
        // SY <--
    ) {
        // SY -->
        val page = if (useExtraPage) {
            (state.value.dialog as? Dialog.PageActions)?.extraPage
        } else {
            (state.value.dialog as? Dialog.PageActions)?.page
        }
        // SY <--
        // KMK --> See `saveImage`: the bytes are the precondition, and a document has none. This is
        // the `stream!!` the crash report pointed at — a long press in a book opened this sheet, and
        // every option in it died here on the IO worker.
        val readyPage = page?.takeIf { it.status == Page.State.Ready } ?: return
        val stream = readyPage.stream ?: return
        // KMK <--
        val manga = manga ?: return

        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, readyPage)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = stream,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                val event = if (copyToClipboard) {
                    Event.CopyImage(uri)
                } else {
                    Event.ShareImage(uri, readyPage)
                }
                eventChannel.send(event)
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    // SY -->
    fun shareImages(copyToClipboard: Boolean) {
        val (firstPage, secondPage) = (state.value.dialog as? Dialog.PageActions ?: return)
        val viewer = state.value.viewer as? PagerViewer ?: return
        val isLTR = (viewer !is R2LPagerViewer) xor (viewer.config.invertDoublePages)
        val bg = viewer.config.pageCanvasColor

        if (firstPage.status != Page.State.Ready) return
        if (secondPage?.status != Page.State.Ready) return
        val manga = manga ?: return

        val destDir = context.cacheImageDir

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = saveImages(
                    page1 = firstPage,
                    page2 = secondPage,
                    isLTR = isLTR,
                    bg = bg,
                    matchHeights = viewer.config.matchDoublePageHeights,
                    location = Location.Cache,
                    manga = manga,
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, firstPage, secondPage))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }
    // SY <--

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover(useExtraPage: Boolean) {
        // SY -->
        val page = if (useExtraPage) {
            (state.value.dialog as? Dialog.PageActions)?.extraPage
        } else {
            (state.value.dialog as? Dialog.PageActions)?.page
        }
        // SY <--
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(coverManager, stream(), updateManga, coverCache)
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (_: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    // KMK -->
    /**
     * Writes the image of the selected page to a temporary cache file so it can
     * be cropped before being applied as cover. Returns null on failure.
     */
    suspend fun getCoverEditUri(useExtraPage: Boolean): Uri? = withIOContext {
        val dialog = state.value.dialog as? Dialog.PageActions
        val page = if (useExtraPage) dialog?.extraPage else dialog?.page
        if (page?.status != Page.State.Ready) return@withIOContext null
        val stream = page.stream ?: return@withIOContext null

        runCatching {
            val dir = File(context.cacheDir, "cover_edit").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "source")
            file.outputStream().use { output -> stream().copyTo(output) }
            Uri.fromFile(file)
        }.getOrNull()
    }

    fun setCoverFromUri(uri: Uri) {
        val currentManga = manga ?: return
        viewModelScope.launchNonCancellable {
            val result = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    currentManga.editCover(coverManager, stream, updateManga, coverCache)
                }
                if (currentManga.isLocal() || currentManga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (_: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    /**
     * Sets the image [imageUrl] names — an illustration of the open book, long-pressed in the
     * viewer — as cover, and notifies the UI of the result.
     *
     * The stream is resolved from the loader rather than from the viewer on purpose: the dialog
     * outlives a viewer that an orientation change may already have rebuilt, and the loader is the
     * object whose lifetime *is* the open chapter. [launchNonCancellable] for the same reason the
     * other cover paths use it — a write that a cancelled flow would interrupt halfway is a cover
     * the user believed was set.
     */
    fun setBookImageAsCover(imageUrl: String) {
        val currentManga = manga ?: return
        viewModelScope.launchNonCancellable {
            val result = try {
                if (!currentManga.isLocal() && !currentManga.favorite) {
                    SetAsCoverResult.AddToLibraryFirst
                } else {
                    val stream = withIOContext { bookImageStream(imageUrl) }
                    if (stream == null) {
                        SetAsCoverResult.Error
                    } else {
                        stream.use { currentManga.editCover(coverManager, it, updateManga, coverCache) }
                        SetAsCoverResult.Success
                    }
                }
            } catch (_: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    /**
     * Reads the bytes of the image [imageUrl] names — the zoom viewer's payload.
     *
     * The bytes rather than a stream: the viewer holds its own copy and decodes it off the main
     * thread, so nothing here needs to stay open, and a stream left in a Compose state would be a
     * resource a recomposition has no reason to close.
     *
     * Suspending rather than posting back through dialog state: the viewer outlives the read —
     * dismisses, rotations and chapter swaps all arrive while the bytes are in flight — and a
     * cancelled read simply fails to produce an image, which the viewer already renders as "could
     * not decode".
     */
    suspend fun getBookImageBytes(imageUrl: String): ByteArray? = withIOContext {
        runCatching { bookImageStream(imageUrl)?.use { stream -> stream.readBytes() } }.getOrNull()
    }

    /**
     * Opens an image inside the open book, or null when the URL is not one the chapter can answer.
     *
     * The hit test hands over exactly two spellings: a `book.internal` reference, which the
     * chapter's [eu.kanade.tachiyomi.source.text.TextResourceSource] streams out of the archive,
     * and a `data:` URI the document inliner embedded in the markup, which decodes into the bytes
     * the engine drew. Everything else — a plate the book links at from a real server — is not
     * this book's art to copy, and comes back as null.
     */
    private fun bookImageStream(imageUrl: String): InputStream? {
        val loader = state.value.currentChapter?.pageLoader as? TextPageLoader ?: return null
        return when {
            EpubArchiveUrls.isArchiveUrl(imageUrl) -> loader.resourceSource?.openResource(imageUrl)
            imageUrl.startsWith(DATA_URI_PREFIX, ignoreCase = true) -> dataUriStream(imageUrl)
            else -> null
        }
    }

    /** Decodes a `data:` URI into the bytes it carries, or null when it is not one this reads. */
    private fun dataUriStream(dataUri: String): InputStream? {
        val payload = dataUri.substringAfter(',', "").trim()
        val header = dataUri.substringBefore(',').removePrefix("data:").lowercase()
        if (payload.isEmpty() || !header.endsWith(";base64")) return null
        val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return ByteArrayInputStream(bytes)
    }
    // KMK <--

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack().get()) return

        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val mergedManga = state.value.mergedManga
        // SY -->
        val manga = if (mergedManga.isNullOrEmpty()) {
            manga
        } else {
            mergedManga[chapter.chapter.manga_id]
        } ?: return
        // SY <--

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
            tempFileManager.deleteTempFiles()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val currentPage: Int = -1,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val menuVisible: Boolean = false,
        @field:IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,

        // SY -->
        /** for display page number in double-page mode */
        val currentPageText: String = "",
        val meta: RaisedSearchMetadata? = null,
        val mergedManga: Map<Long, Manga>? = null,
        val ehUtilsVisible: Boolean = false,
        val lastShiftDoubleState: Boolean? = null,
        val indexPageToShift: Int? = null,
        val indexChapterToShift: Long? = null,
        val doublePages: Boolean = false,
        val dateRelativeTime: Boolean = true,
        val autoScroll: Boolean = false,
        val isAutoScrollEnabled: Boolean = false,
        val ehAutoscrollFreq: String = "",
        // SY <--

        // Mihon -->
        val initError: Throwable? = null,
        // Mihon <--
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog

        // SY -->
        data object ChapterList : Dialog
        // SY <--

        // KMK --> Text-chapter surfaces. Model-free where they can be: what they act on is the open
        // chapter, which the activity reads off the viewer when the sheet opens, so a dialog state
        // that has to be built per chapter would need invalidating on every chapter change for no
        // benefit. The image dialog carries the one thing the chapter cannot answer on its own —
        // which image the long press landed on.
        data object NovelContents : Dialog
        data object NovelSearch : Dialog

        data class NovelImageView(
            /** URL of the pressed illustration, spelled as the engine's hit test handed it over. */
            val imageUrl: String,
        ) : Dialog
        // KMK <--

        data class PageActions(
            val page: ReaderPage,
            // SY -->
            val extraPage: ReaderPage? = null,
            // SY <--
        ) : Dialog

        // SY -->
        data object AutoScrollHelp : Dialog
        data object RetryAllHelp : Dialog
        data object BoostPageHelp : Dialog
        // SY <--
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(
            val uri: Uri,
            val page: ReaderPage,
            // SY -->
            val secondPage: ReaderPage? = null,
            // SY <--
        ) : Event
        data class CopyImage(val uri: Uri) : Event
    }

    private companion object {
        /** Whole minutes only: see [updateHistory]'s reading-time banking. */
        const val MINUTE_MS = 60_000L

        /** Opening form of a `data:` URI — the spelling the document inliner embeds with. */
        private const val DATA_URI_PREFIX = "data:"
    }
}
