package eu.kanade.presentation.more.settings.screen.downloaded

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.data.cache.ChapterCacheInfo
import eu.kanade.tachiyomi.data.cache.ChapterCacheRetention
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackStack
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetNonLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

// KMK -->

@Composable
fun DownloadedContentScreen() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val model = metroViewModel<DownloadedContentViewModel>()
    val state by model.state.collectAsState()
    val retentionHours by model.retentionHours.collectAsState(initial = model.currentRetentionHours())
    var showDeleteAllConfirmation by remember { mutableStateOf(false) }

    // Countdown ticker - recomputes "expires in" lines without rebuilding the state.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    when (val s = state) {
        is DownloadedContentViewModel.State.Loading -> LoadingScreen()
        is DownloadedContentViewModel.State.Ready -> {
            val onDeleted: (Boolean) -> Unit = { ok ->
                context.toast(
                    if (ok) KMR.strings.downloaded_content_deleted else KMR.strings.downloaded_content_delete_error,
                )
            }

            if (showDeleteAllConfirmation) {
                AlertDialog(
                    title = { Text(text = stringResource(KMR.strings.downloaded_content_delete_all)) },
                    text = { Text(text = stringResource(KMR.strings.downloaded_content_delete_all_confirm)) },
                    onDismissRequest = { showDeleteAllConfirmation = false },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteAllConfirmation = false
                                model.deleteAll(onDeleted)
                            },
                        ) {
                            Text(text = stringResource(KMR.strings.downloaded_content_delete_all))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteAllConfirmation = false }) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }

            Scaffold(
                topBar = { scrollBehavior ->
                    AppBar(
                        title = stringResource(KMR.strings.pref_downloaded_content),
                        navigateUp = backStack::removeLastOrNull,
                        actions = {
                            if (s.entries.isNotEmpty()) {
                                IconButton(onClick = { showDeleteAllConfirmation = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(KMR.strings.downloaded_content_delete_all),
                                    )
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                },
            ) { contentPadding ->
                if (s.entries.isEmpty()) {
                    EmptyScreen(
                        message = stringResource(KMR.strings.downloaded_content_empty),
                        modifier = Modifier.padding(contentPadding),
                    )
                } else {
                    val grouped = remember(s.entries) { s.entries.groupBy { it.sourceName } }
                    LazyColumn(
                        modifier = Modifier.padding(contentPadding),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        grouped.forEach { (sourceName, entries) ->
                            item(key = "header-$sourceName") {
                                Text(
                                    text = sourceName,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            top = MaterialTheme.padding.small,
                                            start = MaterialTheme.padding.medium,
                                            end = MaterialTheme.padding.medium,
                                        ),
                                )
                            }
                            items(entries, key = { it.key }) { entry ->
                                DownloadedContentEntryRow(
                                    entry = entry,
                                    retentionMs = retentionHours * 3_600_000L,
                                    nowMs = nowMs,
                                    onDeleteEntry = { model.deleteEntry(entry, onDeleted) },
                                    onDeleteChapterDir = { dirName ->
                                        model.deleteChapterDir(entry, dirName, onDeleted)
                                    },
                                    onDeleteDbChapter = { chapter ->
                                        model.deleteDbChapter(entry, chapter, onDeleted)
                                    },
                                    onDeleteCachedChapter = { cached ->
                                        model.deleteCachedChapter(cached, onDeleted)
                                    },
                                )
                            }
                        }
                        if (s.unattributedBytes > 0L) {
                            item(key = "unattributed-footer") {
                                Text(
                                    text = stringResource(
                                        KMR.strings.downloaded_content_unattributed_footer,
                                        Formatter.formatFileSize(context, s.unattributedBytes),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(MaterialTheme.padding.medium),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedContentEntryRow(
    entry: DownloadedContentViewModel.Entry,
    retentionMs: Long,
    nowMs: Long,
    onDeleteEntry: () -> Unit,
    onDeleteChapterDir: (String) -> Unit,
    onDeleteDbChapter: (Chapter) -> Unit,
    onDeleteCachedChapter: (DownloadedContentViewModel.CachedChapterRow) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember(entry.key) { mutableStateOf(false) }
    var showDeleteConfirmation by remember(entry.key) { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        AlertDialog(
            title = { Text(text = entry.title) },
            text = { Text(text = stringResource(KMR.strings.downloaded_content_delete_entry_confirm, entry.title)) },
            onDismissRequest = { showDeleteConfirmation = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteEntry()
                    },
                ) {
                    Text(text = stringResource(KMR.strings.downloaded_content_delete_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(
                    start = MaterialTheme.padding.medium,
                    end = MaterialTheme.padding.small,
                )
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        ) {
            AsyncImage(
                model = entry.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(Color(0x1F888888)),
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (entry.manga != null) {
                            KMR.strings.downloaded_content_badge_not_in_library
                        } else {
                            KMR.strings.downloaded_content_badge_unknown
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.manga != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    text = buildString {
                        append(stringResource(KMR.strings.downloaded_content_chapters_count, entry.totalChapterCount))
                        append(" · ")
                        append(
                            entry.sizeBytes?.let { Formatter.formatFileSize(context, it) }
                                ?: stringResource(KMR.strings.downloaded_content_size_unknown),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { showDeleteConfirmation = true }) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(KMR.strings.downloaded_content_delete_all),
                )
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
        }

        if (expanded) {
            if (entry.manga != null) {
                val downloaded = entry.dbChapters.filter { it.dirName != null }
                if (downloaded.isNotEmpty()) {
                    SectionHeader(stringResource(KMR.strings.downloaded_content_downloaded_header))
                    downloaded.forEach { row ->
                        ChapterDirRow(
                            title = row.chapter.name,
                            onDelete = { row.dirName?.let { onDeleteDbChapter(row.chapter) } },
                        )
                    }
                }
            } else if (entry.chapterDirNames.isNotEmpty()) {
                SectionHeader(stringResource(KMR.strings.downloaded_content_downloaded_header))
                entry.chapterDirNames.sorted().forEach { dirName ->
                    ChapterDirRow(
                        title = dirName,
                        onDelete = { onDeleteChapterDir(dirName) },
                    )
                }
            }

            if (entry.cachedChapters.isNotEmpty()) {
                SectionHeader(stringResource(KMR.strings.downloaded_content_cached_header))
                entry.cachedChapters.forEach { cached ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = MaterialTheme.padding.large,
                                end = MaterialTheme.padding.small,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cached.chapter.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = cachedStatusText(cached, retentionMs, nowMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = Formatter.formatFileSize(context, cached.info.totalBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { onDeleteCachedChapter(cached) }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(KMR.strings.downloaded_content_delete_cached_chapter),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun cachedStatusText(
    cached: DownloadedContentViewModel.CachedChapterRow,
    retentionMs: Long,
    nowMs: Long,
): String {
    return when {
        retentionMs <= 0L -> stringResource(
            KMR.strings.downloaded_content_cached_ago,
            formatDuration(ChapterCacheRetention.ageMs(cached.info.oldestLastModifiedMs, nowMs)),
        ) + " · " + stringResource(KMR.strings.downloaded_content_kept_until_space)
        ChapterCacheRetention.remainingMs(cached.info.oldestLastModifiedMs, nowMs, retentionMs) <= 0L ->
            stringResource(KMR.strings.downloaded_content_removed_next_sweep)
        else -> stringResource(
            KMR.strings.downloaded_content_expires_in,
            formatDuration(
                ChapterCacheRetention.remainingMs(cached.info.oldestLastModifiedMs, nowMs, retentionMs),
            ),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.padding.large,
                end = MaterialTheme.padding.medium,
                top = MaterialTheme.padding.extraSmall,
            ),
    )
}

@Composable
private fun ChapterDirRow(
    title: String,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.padding.large,
                end = MaterialTheme.padding.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
            )
        }
    }
}

/** Joins a remaining/elapsed duration into e.g. "2d 5h", "5h 3min", "12min". */
private fun formatDuration(ms: Long): String {
    val parts = ChapterCacheRetention.displayParts(ms)
    return buildString {
        if (parts.days > 0L) append("${parts.days}d")
        if (parts.hours > 0L) {
            if (isNotEmpty()) append(' ')
            append("${parts.hours}h")
        }
        if (parts.minutes > 0L) {
            if (isNotEmpty()) append(' ')
            append("${parts.minutes}min")
        }
        if (isEmpty()) append("<1min")
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DownloadedContentViewModel : StateViewModel<DownloadedContentViewModel.State>(
    DownloadedContentViewModel.State.Loading,
) {

    private val downloadProvider: DownloadProvider = globalAppGraph.downloadProvider
    private val downloadManager: DownloadManager = globalAppGraph.downloadManager
    private val getFavorites: GetFavorites = globalAppGraph.getFavorites
    private val getNonLibraryManga: GetNonLibraryManga = globalAppGraph.getNonLibraryManga

    val retentionHours: Flow<Int> = globalAppGraph.libraryPreferences.chapterCacheRetention().changes()

    fun currentRetentionHours(): Int = globalAppGraph.libraryPreferences.chapterCacheRetention().get()

    init {
        // Opening the screen is a sweep point: whatever is past its retention goes now, so the
        // cached-chapter lists never show entries a sweep would have already dropped.
        viewModelScope.launchNonCancellable {
            globalAppGraph.chapterCache.sweepExpired(currentRetentionHours() * 3_600_000L)
            rebuild()
        }
    }

    fun deleteEntry(entry: Entry, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launchNonCancellable {
            val ok = try {
                deleteEntryInternal(entry)
                true
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to delete content of ${entry.mangaDirName}" }
                false
            }
            rebuild()
            withUIContext { onComplete(ok) }
        }
    }

    fun deleteAll(onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launchNonCancellable {
            val entries = (state.value as? State.Ready)?.entries.orEmpty()
            var ok = true
            entries.forEach { entry ->
                try {
                    deleteEntryInternal(entry)
                } catch (e: Throwable) {
                    ok = false
                    logcat(LogPriority.ERROR, e) { "Failed to delete content of ${entry.mangaDirName}" }
                }
            }
            rebuild()
            withUIContext { onComplete(ok) }
        }
    }

    fun deleteChapterDir(entry: Entry, chapterDirName: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launchNonCancellable {
            val ok = try {
                val source = globalAppGraph.sourceManager.getOrStub(entry.sourceId)
                downloadProvider.findMangaDirByDirName(entry.mangaDirName, source)
                    ?.findFile(chapterDirName)
                    ?.delete()
                globalAppGraph.downloadCache.removeChapterDirNamesByIdName(
                    entry.sourceId,
                    entry.mangaDirName,
                    listOf(chapterDirName),
                )
                true
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to delete chapter dir $chapterDirName" }
                false
            }
            rebuild()
            withUIContext { onComplete(ok) }
        }
    }

    fun deleteDbChapter(entry: Entry, chapter: Chapter, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launchNonCancellable {
            val ok = try {
                val manga = entry.manga
                if (manga != null) {
                    val source = globalAppGraph.sourceManager.getOrStub(entry.sourceId)
                    downloadManager.deleteChapters(
                        chapters = listOf(chapter),
                        manga = manga,
                        source = source,
                        ignoreCategoryExclusion = true,
                    )
                }
                true
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to delete chapter ${chapter.name}" }
                false
            }
            rebuild()
            withUIContext { onComplete(ok) }
        }
    }

    fun deleteCachedChapter(cached: CachedChapterRow, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launchNonCancellable {
            val ok = try {
                globalAppGraph.chapterCache.removePageListFromCache(cached.chapter)
                true
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to delete cached pages of ${cached.chapter.name}" }
                false
            }
            rebuild()
            withUIContext { onComplete(ok) }
        }
    }

    /**
     * Deletes one entry's on-disk content. DB-known entries go through [DownloadManager]
     * (queue + cache bookkeeping); folders that match no row are deleted raw and forgotten by
     * name. The manga row itself is never touched - that is Clear database's job.
     */
    private suspend fun deleteEntryInternal(entry: Entry) {
        val source = globalAppGraph.sourceManager.getOrStub(entry.sourceId)
        val manga = entry.manga
        if (manga != null) {
            downloadManager.deleteManga(manga, source)
            return
        }

        downloadProvider.findMangaDirByDirName(entry.mangaDirName, source)?.delete()
        globalAppGraph.downloadCache.removeMangaDirByIdName(entry.sourceId, entry.mangaDirName)

        val sourceDir = downloadProvider.findSourceDir(source)
        if (sourceDir?.listFiles()?.isEmpty() == true) {
            sourceDir.delete()
            globalAppGraph.downloadCache.removeSource(source)
        }
    }

    /**
     * The source-api [Source] has no visualName; mirrors the domain model's
     * "Name (LANG)" rendering so grouping headers match the rest of the app.
     */
    private fun sourceDisplayName(source: Source): String {
        return if (source.lang.isBlank()) source.name else "${source.name} (${source.lang.uppercase()})"
    }

    private fun rebuild() {
        viewModelScope.launchNonCancellable {
            val entries = buildEntries()
            mutableState.update {
                State.Ready(entries, unattributedBytes(entries))
            }
        }
    }

    private suspend fun buildEntries(): List<Entry> {
        val downloadCache = globalAppGraph.downloadCache
        val chapterCache = globalAppGraph.chapterCache
        val chapterRepository = globalAppGraph.chapterRepository
        val sourceManager = globalAppGraph.sourceManager

        val snapshot = downloadCache.snapshotDownloadedMangaDirs()
        val libraryDirNames = getFavorites.await()
            .mapTo(HashSet()) { it.source to downloadProvider.getMangaDirName(it.ogTitle) }
        val nonLibraryBySource = getNonLibraryManga.await().groupBy { it.source }

        return snapshot
            .filterNot { it.sourceId == 0L } // local source content is the user's own files
            .filterNot { (it.sourceId to it.mangaDirName) in libraryDirNames }
            .map { dir ->
                val source = sourceManager.getOrStub(dir.sourceId)
                val manga = nonLibraryBySource[dir.sourceId]?.firstOrNull {
                    downloadProvider.getMangaDirName(it.ogTitle) == dir.mangaDirName
                }
                val chapters = manga?.let { chapterRepository.getChapterByMangaId(it.id) }.orEmpty()
                val dbChapters = if (manga != null) {
                    chapters.map { chapter ->
                        val dirName = downloadProvider.getValidChapterDirNames(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                        ).firstOrNull { it in dir.chapterDirNames }
                        ChapterRow(chapter, dirName)
                    }
                } else {
                    emptyList()
                }
                val cachedChapters = if (manga != null) {
                    chapters.mapNotNull { chapter ->
                        chapterCache.getChapterCacheInfo(chapter)?.let { CachedChapterRow(chapter, it) }
                    }
                } else {
                    emptyList()
                }
                val size = try {
                    downloadProvider.findMangaDirByDirName(dir.mangaDirName, source)
                        ?.let { DiskUtil.getDirectorySize(it) }
                } catch (e: Throwable) {
                    logcat(LogPriority.DEBUG, e) { "Failed to measure ${dir.mangaDirName}" }
                    null
                }
                Entry(
                    sourceId = dir.sourceId,
                    sourceName = sourceDisplayName(source),
                    mangaDirName = dir.mangaDirName,
                    title = manga?.title ?: dir.mangaDirName,
                    cover = manga?.asMangaCover(),
                    manga = manga,
                    dbChapters = dbChapters,
                    chapterDirNames = dir.chapterDirNames,
                    cachedChapters = cachedChapters,
                    sizeBytes = size,
                )
            }
            .filter { it.dbChapters.isNotEmpty() || it.chapterDirNames.isNotEmpty() || it.cachedChapters.isNotEmpty() }
            .sortedByDescending { it.sizeBytes ?: -1L }
    }

    private fun unattributedBytes(entries: List<Entry>): Long {
        val attributed = entries.sumOf { entry -> entry.cachedChapters.sumOf { it.info.totalBytes } }
        return (globalAppGraph.chapterCache.sizeBytes - attributed).coerceAtLeast(0L)
    }

    @Immutable
    sealed interface State {
        data object Loading : State

        @Immutable
        data class Ready(
            val entries: List<Entry>,
            val unattributedBytes: Long,
        ) : State
    }

    @Immutable
    data class Entry(
        val sourceId: Long,
        val sourceName: String,
        val mangaDirName: String,
        val title: String,
        val cover: MangaCover?,
        val manga: Manga?,
        /** DB chapters matched to their on-disk dir name; null dir = not downloaded. */
        val dbChapters: List<ChapterRow>,
        /** On-disk chapter dir names for entries that match no DB row. */
        val chapterDirNames: Set<String>,
        val cachedChapters: List<CachedChapterRow>,
        val sizeBytes: Long?,
    ) {
        val key: String get() = "$sourceId-$mangaDirName"
        val totalChapterCount: Int get() = if (dbChapters.isNotEmpty()) dbChapters.size else chapterDirNames.size
    }

    @Immutable
    data class ChapterRow(
        val chapter: Chapter,
        val dirName: String?,
    )

    @Immutable
    data class CachedChapterRow(
        val chapter: Chapter,
        val info: ChapterCacheInfo,
    )
}
// KMK <--
