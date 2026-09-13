package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

/**
 * Creates the [PageLoader] for a chapter. The default [Default] chain handles
 * downloaded → merged → local → http sources; custom chapter types register
 * via [Registry.register] with a lower [PageLoaderProvider.order].
 */
interface PageLoaderProvider {
    val order: Int
    fun create(request: PageLoaderRequest): PageLoader?
}

data class PageLoaderRequest(
    val context: Context,
    val chapter: ReaderChapter,
    val manga: Manga,
    val source: Source,
    val sourceManager: SourceManager,
    val downloadManager: DownloadManager,
    val downloadProvider: DownloadProvider,
    val readerPrefs: ReaderPreferences,
    val mergedReferences: List<MergedMangaReference>,
    val mergedManga: Map<Long, Manga>?,
)

object PageLoaderRegistry {
    private val providers = mutableListOf<PageLoaderProvider>(DefaultPageLoaderProvider)

    fun register(provider: PageLoaderProvider) {
        providers.removeAll { it::class == provider::class }
        providers.add(provider)
        providers.sortBy { it.order }
    }

    fun create(request: PageLoaderRequest): PageLoader {
        for (provider in providers.sortedBy { it.order }) {
            val loader = runCatching { provider.create(request) }.getOrNull()
            if (loader != null) return loader
        }
        error(request.context.stringResource(MR.strings.loader_not_implemented_error))
    }
}

private object DefaultPageLoaderProvider : PageLoaderProvider {
    override val order: Int = 100

    override fun create(request: PageLoaderRequest): PageLoader? {
        val context = request.context
        val chapter = request.chapter
        val manga = request.manga
        val source = request.source
        val dbChapter = chapter.chapter
        fun localLoader(source: LocalSource): PageLoader {
            return source.getFormat(dbChapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
                    is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
                }
            }
        }
        // SY --> merged sources resolve their own per-chapter source first
        if (source is MergedSource) {
            val mangaReference = request.mergedReferences.firstOrNull {
                it.mangaId == chapter.chapter.manga_id
            } ?: error("Merge reference null")
            val resolved = request.sourceManager.get(mangaReference.mangaSourceId)
                ?: error("Source ${mangaReference.mangaSourceId} was null")
            val mergedEntry = request.mergedManga?.get(chapter.chapter.manga_id)
                ?: error("Manga for merged chapter was null")
            val isMergedMangaDownloaded = request.downloadManager.isChapterDownloaded(
                chapterName = chapter.chapter.name,
                chapterScanlator = chapter.chapter.scanlator,
                chapterUrl = chapter.chapter.url,
                mangaTitle = mergedEntry.ogTitle,
                sourceId = mergedEntry.source,
                skipCache = true,
            )
            return when {
                isMergedMangaDownloaded -> DownloadPageLoader(
                    chapter = chapter,
                    manga = mergedEntry,
                    source = resolved,
                    downloadManager = request.downloadManager,
                    downloadProvider = request.downloadProvider,
                )
                resolved is HttpSource -> HttpPageLoader(chapter, resolved)
                resolved is LocalSource -> localLoader(resolved)
                else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
            }
        }
        // SY <--
        val isDownloaded = request.downloadManager.isChapterDownloaded(
            chapterName = dbChapter.name,
            chapterScanlator = dbChapter.scanlator,
            chapterUrl = dbChapter.url,
            // SY -->
            mangaTitle = manga.ogTitle,
            // SY <--
            sourceId = manga.source,
            skipCache = true,
        )
        return when {
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                request.downloadManager,
                request.downloadProvider,
            )
            source is LocalSource -> localLoader(source)
            source is HttpSource -> HttpPageLoader(chapter, source)
            source is StubSource -> error(
                context.stringResource(MR.strings.source_not_installed, source.toString()),
            )
            else -> null
        }
    }
}
