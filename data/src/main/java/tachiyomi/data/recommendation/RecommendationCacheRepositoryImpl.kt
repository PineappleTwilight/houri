// KMK -->
package tachiyomi.data.recommendation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.recommendation.model.CachedRecommendation
import tachiyomi.domain.recommendation.repository.RecommendationCacheRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RecommendationCacheRepositoryImpl(
    private val handler: DatabaseHandler,
) : RecommendationCacheRepository {

    override suspend fun getBySourceMangaId(sourceMangaId: Long): List<CachedRecommendation> {
        return handler.awaitList {
            manga_recommendationsQueries.selectBySourceMangaId(
                sourceMangaId = sourceMangaId,
                cachedRecommendationMapper,
            )
        }
    }

    override suspend fun getAll(): List<CachedRecommendation> {
        return handler.awaitList {
            manga_recommendationsQueries.selectAll(cachedRecommendationMapper)
        }
    }

    override suspend fun insert(cachedRecommendation: CachedRecommendation) {
        return handler.await(inTransaction = true) {
            manga_recommendationsQueries.insert(
                sourceMangaId = cachedRecommendation.sourceMangaId,
                recSourceName = cachedRecommendation.recSourceName,
                recSourceCategoryResId = cachedRecommendation.recSourceCategoryResId.toLong(),
                recAssociatedSourceId = cachedRecommendation.recAssociatedSourceId,
                results = cachedRecommendation.results,
                cachedAt = cachedRecommendation.cachedAt,
            )
        }
    }

    override suspend fun insertAll(cachedRecommendations: List<CachedRecommendation>) {
        return handler.await(inTransaction = true) {
            cachedRecommendations.forEach {
                manga_recommendationsQueries.insert(
                    sourceMangaId = it.sourceMangaId,
                    recSourceName = it.recSourceName,
                    recSourceCategoryResId = it.recSourceCategoryResId.toLong(),
                    recAssociatedSourceId = it.recAssociatedSourceId,
                    results = it.results,
                    cachedAt = it.cachedAt,
                )
            }
        }
    }

    override suspend fun deleteBySourceMangaId(sourceMangaId: Long) {
        return handler.await {
            manga_recommendationsQueries.deleteBySourceMangaId(sourceMangaId = sourceMangaId)
        }
    }

    override suspend fun deleteAll() {
        return handler.await { manga_recommendationsQueries.deleteAll() }
    }

    override suspend fun deleteExpired(expiryTimestamp: Long) {
        return handler.await {
            manga_recommendationsQueries.deleteExpired(expiryTimestamp = expiryTimestamp)
        }
    }
}
// KMK <--
