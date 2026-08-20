// KMK -->
package tachiyomi.domain.recommendation.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.recommendation.model.CachedRecommendation
import tachiyomi.domain.recommendation.repository.RecommendationCacheRepository

@Inject
class GetCachedRecommendations(
    private val repository: RecommendationCacheRepository,
) {

    suspend fun awaitBySourceMangaId(sourceMangaId: Long): List<CachedRecommendation> {
        return repository.getBySourceMangaId(sourceMangaId)
    }

    suspend fun awaitAll(): List<CachedRecommendation> {
        return repository.getAll()
    }
}
// KMK <--
