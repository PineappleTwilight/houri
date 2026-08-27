package tachiyomi.data.source

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MetadataMangasPage
import exh.metadata.metadata.RaisedSearchMetadata
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaMetadataRepository

abstract class EHentaiPagingSource(
    source: Source,
    networkToLocalManga: NetworkToLocalManga,
    private val mangaMetadataRepository: MangaMetadataRepository,
) : BaseSourcePagingSource(source, networkToLocalManga) {

    override suspend fun getPageLoadResult(
        params: LoadParams<Long>,
        mangasPage: MangasPage,
    ): LoadResult.Page<Long, Pair<Manga, RaisedSearchMetadata?>> {
        mangasPage as MetadataMangasPage
        val metadata = mangasPage.mangasMetadata

        val manga = mangasPage.mangas
            .mapIndexed { index, sManga -> sManga.toDomainManga(source.id) to metadata.getOrNull(index) }
            .filter { seenManga.add(it.first.url) }
            // KMK -->
            .let { pairs -> networkToLocalManga(pairs.map { it.first }).zip(pairs.map { it.second }) }
        // KMK <--

        // Persist browse metadata for non-library entries so details page shows chips/rating/page count immediately
        for ((domainManga, raised) in manga) {
            if (raised != null && !domainManga.favorite) {
                val existing = try {
                    mangaMetadataRepository.getMetadataById(domainManga.id)
                } catch (_: Exception) {
                    null
                }
                if (existing == null) {
                    try {
                        raised.mangaId = domainManga.id
                        mangaMetadataRepository.insertMetadata(raised)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        return LoadResult.Page(
            data = manga,
            prevKey = null,
            nextKey = mangasPage.nextKey,
        )
    }
}

class EHentaiSearchPagingSource(
    source: Source,
    val query: String,
    val filters: FilterList,
    networkToLocalManga: NetworkToLocalManga,
    mangaMetadataRepository: MangaMetadataRepository,
) : EHentaiPagingSource(source, networkToLocalManga, mangaMetadataRepository) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getSearchManga(currentPage, query.sanitize(), filters)
    }
}

class EHentaiPopularPagingSource(
    source: Source,
    networkToLocalManga: NetworkToLocalManga,
    mangaMetadataRepository: MangaMetadataRepository,
) : EHentaiPagingSource(source, networkToLocalManga, mangaMetadataRepository) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class EHentaiLatestPagingSource(
    source: Source,
    networkToLocalManga: NetworkToLocalManga,
    mangaMetadataRepository: MangaMetadataRepository,
) : EHentaiPagingSource(source, networkToLocalManga, mangaMetadataRepository) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}
