package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.lifecycle.Lifecycle
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.all.MergedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowWithLifecycle
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import exh.source.isEhBasedManga

/**
 * Mirrors download queue status/progress into the chapter list and owns the
 * download-related user actions (start/now/cancel/delete, bulk download presets).
 *
 * Screen-model state is accessed through the provider/callback parameters; flows are
 * lifecycle-aware so mirroring stops when the screen stops.
 */
internal class ChapterDownloadsController(
    private val scope: CoroutineScope,
    private val lifecycle: Lifecycle,
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val sourceManager: SourceManager,
    private val provideSuccessState: () -> MangaScreenModel.State.Success?,
    private val onUpdateState: ((MangaScreenModel.State.Success) -> MangaScreenModel.State.Success) -> Unit,
    private val provideFilteredChapters: () -> List<ChapterList.Item>?,
    private val provideAllChapters: () -> List<ChapterList.Item>?,
    private val skipFiltered: () -> Boolean,
    private val isFavorited: () -> Boolean,
    private val snackbarHostState: SnackbarHostState,
    private val onToggleFavorite: () -> Unit,
    private val onClearSelection: () -> Unit,
    private val onDeleteChapters: (List<Chapter>) -> Unit,
) {

    fun observeDownloads() {
        // SY -->
        val isMergedSource = provideSuccessState()?.source is MergedSource
        val mergedIds = if (isMergedSource) {
            provideSuccessState()?.mergedData?.manga?.keys.orEmpty()
        } else {
            emptySet()
        }
        // SY <--
        scope.launchIO {
            downloadManager.statusFlow()
                .filter {
                    /* SY --> */ if (isMergedSource) {
                        it.manga.id in mergedIds
                    } else {
                        /* SY <-- */ it.manga.id ==
                            provideSuccessState()?.manga?.id
                    }
                }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }

        scope.launchIO {
            downloadManager.progressFlow()
                .filter {
                    /* SY --> */ if (isMergedSource) {
                        it.manga.id in mergedIds
                    } else {
                        /* SY <-- */ it.manga.id ==
                            provideSuccessState()?.manga?.id
                    }
                }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }
    }

    fun updateDownloadState(download: Download) {
        onUpdateState { successState ->
            val modifiedIndex = successState.chapters.indexOfFirst { it.id == download.chapter.id }
            if (modifiedIndex < 0) return@onUpdateState successState

            val newChapters = successState.chapters.toMutableList().apply {
                val item = removeAt(modifiedIndex)
                    .copy(downloadState = download.status, downloadProgress = download.progress)
                add(modifiedIndex, item)
            }
            successState.copy(chapters = newChapters)
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    fun hasDownloads(): Boolean {
        val manga = provideSuccessState()?.manga ?: return false
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    fun deleteDownloads() {
        val state = provideSuccessState() ?: return
        // SY -->
        if (state.source is MergedSource) {
            val mergedManga = state.mergedData?.manga?.map { it.value to sourceManager.getOrStub(it.value.source) }
            mergedManga?.forEach { (manga, source) ->
                downloadManager.deleteManga(manga, source)
            }
        } else {
            /* SY <-- */ downloadManager.deleteManga(state.manga, state.source)
        }
    }

    private fun getUnreadChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered()) provideFilteredChapters().orEmpty() else provideAllChapters().orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> !chapter.read && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getUnreadChaptersSorted(): List<Chapter> {
        val manga = provideSuccessState()?.manga ?: return emptyList()
        val chaptersSorted = getUnreadChapters().sortedWith(getChapterSort(manga))
            // SY -->
            .let {
                if (manga.isEhBasedManga()) it.reversed() else it
            }
        // SY <--
        return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
    }

    private fun getBookmarkedChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered()) provideFilteredChapters().orEmpty() else provideAllChapters().orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> chapter.bookmark && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        val successState = provideSuccessState() ?: return

        scope.launchNonCancellable {
            if (startNow) {
                val chapterId = chapters.singleOrNull()?.id ?: return@launchNonCancellable
                downloadManager.startDownloadNow(chapterId)
            } else {
                downloadChapters(chapters)
            }

            if (!isFavorited() && !successState.hasPromptedToAddBefore) {
                onUpdateState { state ->
                    state.copy(hasPromptedToAddBefore = true)
                }
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited()) {
                    onToggleFavorite()
                }
            }
        }
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items.map { it.chapter }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    downloadManager.startDownloads()
                }
            }
            ChapterDownloadAction.START_NOW -> {
                val chapter = items.singleOrNull()?.chapter ?: return
                startDownload(listOf(chapter), true)
            }
            ChapterDownloadAction.CANCEL -> {
                val chapterId = items.singleOrNull()?.id ?: return
                cancelDownload(chapterId)
            }
            ChapterDownloadAction.DELETE -> {
                onDeleteChapters(items.map { it.chapter })
            }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChaptersSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChaptersSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChaptersSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChaptersSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapters()
            DownloadAction.BOOKMARKED_CHAPTERS -> getBookmarkedChapters()
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    fun cancelDownload(chapterId: Long) {
        downloadManager.cancelQueuedDownload(chapterId)?.let(::updateDownloadState)
    }

    fun downloadChapters(chapters: List<Chapter>) {
        // SY -->
        val state = provideSuccessState() ?: return
        if (state.source is MergedSource) {
            chapters.groupBy { it.mangaId }.forEach { map ->
                val manga = state.mergedData?.manga?.get(map.key) ?: return@forEach
                downloadManager.downloadChapters(manga, map.value)
            }
        } else {
            // SY <--
            val manga = state.manga
            downloadManager.downloadChapters(manga, chapters)
        }
        onClearSelection()
    }

    fun downloadNewChapters(chapters: List<Chapter>) {
        scope.launchNonCancellable {
            val manga = provideSuccessState()?.manga ?: return@launchNonCancellable
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)

            if (chaptersToDownload.isNotEmpty() /* SY --> */ && !manga.isEhBasedManga() /* SY <-- */) {
                downloadChapters(chaptersToDownload)
            }
        }
    }
}
