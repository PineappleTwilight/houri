package tachiyomi.data.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.MERGED_SOURCE_ID
import exh.source.isEhBasedSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaMetadataRepository
import tachiyomi.domain.source.model.SourceWithCount
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.model.Source as DomainSource

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SourceRepositoryImpl(
    private val sourceManager: SourceManager,
    private val handler: DatabaseHandler,
    private val networkToLocalManga: NetworkToLocalManga,
    private val mangaMetadataRepository: MangaMetadataRepository,
) : SourceRepository {

    override fun getSources(): Flow<List<DomainSource>> {
        return sourceManager.sources.map { sources ->
            sources.map {
                mapSourceToDomainSource(it).copy(
                    supportsLatest = it.supportsLatest,
                )
            }
        }
    }

    override fun getOnlineSources(): Flow<List<DomainSource>> {
        return sourceManager.sources.map { sources ->
            sources
                .filterIsInstance<HttpSource>()
                .map(::mapSourceToDomainSource)
        }
    }

    override fun getSourcesWithFavoriteCount(): Flow<List<Pair<DomainSource, Long>>> {
        return combine(
            handler.subscribeToList { mangasQueries.getSourceIdWithFavoriteCount() },
            sourceManager.sources,
        ) { sourceIdWithFavoriteCount, _ -> sourceIdWithFavoriteCount }
            .map {
                // SY -->
                it.filterNot { it.source == MERGED_SOURCE_ID }
                    // SY <--
                    .map { (sourceId, count) ->
                        val source = sourceManager.getOrStub(sourceId)
                        val domainSource = mapSourceToDomainSource(source).copy(
                            isStub = source is StubSource,
                        )
                        domainSource to count
                    }
            }
    }

    override fun getSourcesWithNonLibraryManga(): Flow<List<SourceWithCount>> {
        val sourceIdWithNonLibraryManga =
            handler.subscribeToList { mangasQueries.getSourceIdsWithNonLibraryManga() }
        return sourceIdWithNonLibraryManga.map { sourceId ->
            sourceId.map { (sourceId, count) ->
                val source = sourceManager.getOrStub(sourceId)
                val domainSource = mapSourceToDomainSource(source).copy(
                    isStub = source is StubSource,
                )
                SourceWithCount(domainSource, count)
            }
        }
    }

    override fun search(
        sourceId: Long,
        query: String,
        filterList: FilterList,
    ): SourcePagingSource {
        val source = sourceManager.getOrStub(sourceId)
        // SY -->
        if (source.isEhBasedSource()) {
            return EHentaiSearchPagingSource(source, query, filterList, networkToLocalManga, mangaMetadataRepository)
        }
        // SY <--
        return SourceSearchPagingSource(source, query, filterList, networkToLocalManga)
    }

    override fun getPopular(sourceId: Long): SourcePagingSource {
        val source = sourceManager.getOrStub(sourceId)
        // SY -->
        if (source.isEhBasedSource()) {
            return EHentaiPopularPagingSource(source, networkToLocalManga, mangaMetadataRepository)
        }
        // SY <--
        return SourcePopularPagingSource(source, networkToLocalManga)
    }

    override fun getLatest(sourceId: Long): SourcePagingSource {
        val source = sourceManager.getOrStub(sourceId)
        // SY -->
        if (source.isEhBasedSource()) {
            return EHentaiLatestPagingSource(source, networkToLocalManga, mangaMetadataRepository)
        }
        // SY <--
        return SourceLatestPagingSource(source, networkToLocalManga)
    }

    private fun mapSourceToDomainSource(source: Source): DomainSource = DomainSource(
        id = source.id,
        lang = source.lang,
        name = source.name,
        supportsLatest = false,
        isStub = false,
    )
}
