package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import exh.debug.DebugToggles
import exh.eh.EHentaiUpdateHelper
import exh.log.xLogD
import exh.metadata.metadata.base.FlatMetadata
import exh.source.isEhBasedManga
import exh.source.isMergedSourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.source.service.SourceManager

/**
 * Assembles the raw data streams feeding the manga details screen: local chapters,
 * merged-source chapters, flat metadata and merged-manga data, combined so any
 * change re-emits a consistent [DetailData] snapshot.
 *
 * Also detects E-Hentai root-gallery redirects while observing the chain and
 * surfaces them through [rootRedirects].
 */
internal class MangaDetailsPipeline(
    private val mangaId: Long,
    private val scope: CoroutineScope,
    private val getMangaAndChapters: GetMangaWithChapters,
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId,
    private val getFlatMetadata: GetFlatMetadataById,
    private val getMergedMangaById: GetMergedMangaById,
    private val getMergedReferencesById: GetMergedReferencesById,
    private val sourceManager: SourceManager,
    private val downloadCache: DownloadCache,
    private val downloadManager: DownloadManager,
    private val libraryPreferences: LibraryPreferences,
) {

    private val updateHelper: EHentaiUpdateHelper by lazy { globalAppGraph.eHentaiUpdateHelper }

    private val _rootRedirects = MutableSharedFlow<Long>()
    val rootRedirects: SharedFlow<Long> = _rootRedirects

    fun details(): Flow<DetailData> {
        return getMangaAndChapters.subscribe(mangaId, applyFilter = true).distinctUntilChanged()
            // SY -->
            .combine(
                getMergedChaptersByMangaId.subscribe(mangaId, true, applyFilter = true)
                    .distinctUntilChanged(),
            ) { (manga, chapters), mergedChapters ->
                if (isMergedSourceId(manga.source)) {
                    manga to mergedChapters
                } else {
                    manga to chapters
                }
            }
            .onEach { (manga, chapters) ->
                if (chapters.isNotEmpty() &&
                    manga.isEhBasedManga() &&
                    DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled
                ) {
                    // Check for gallery in library and accept manga with lowest id
                    // Find chapters sharing same root
                    scope.launch {
                        try {
                            val (acceptedChain) = updateHelper.findAcceptedRootAndDiscardOthers(manga.source, chapters)
                            // Redirect if we are not the accepted root
                            if (manga.id != acceptedChain.manga.id && acceptedChain.manga.favorite) {
                                // Update if any of our chapters are not in accepted manga's chapters
                                xLogD("Found accepted manga %s", manga.url)
                                _rootRedirects.emit(acceptedChain.manga.id)
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "Error loading accepted chapter chain" }
                        }
                    }
                }
            }
            .combine(
                getFlatMetadata.subscribe(mangaId)
                    .distinctUntilChanged(),
            ) { pair, flatMetadata ->
                DetailData(pair.first, pair.second, flatMetadata)
            }
            .combine(
                combine(
                    getMergedMangaById.subscribe(mangaId)
                        .distinctUntilChanged(),
                    getMergedReferencesById.subscribe(mangaId)
                        .distinctUntilChanged(),
                ) { manga, references ->
                    if (manga.isNotEmpty()) {
                        MergedMangaData(
                            references,
                            manga.associateBy { it.id },
                            references.map { it.mangaSourceId }.distinct()
                                .map { sourceManager.getOrStub(it) },
                        )
                    } else {
                        null
                    }
                },
            ) { state, mergedData ->
                state.copy(mergedData = mergedData)
            }
            .combine(downloadCache.changes) { state, _ -> state }
            .combine(downloadManager.queueState) { state, _ -> state }
            // KMK -->
            // Value discarded; re-emission on toggle is what refreshes the chapter list
            .combine(libraryPreferences.smartScanlatorMerge().changes()) { state, _ -> state }
        // KMK <--
    }

    /**
     * A consistent snapshot of everything needed to render the chapter list.
     */
    internal data class DetailData(
        val manga: Manga,
        val chapters: List<Chapter>,
        val flatMetadata: FlatMetadata?,
        val mergedData: MergedMangaData? = null,
    )
}
