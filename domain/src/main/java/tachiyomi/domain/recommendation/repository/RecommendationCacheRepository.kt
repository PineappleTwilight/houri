// KMK -->
package tachiyomi.domain.recommendation.repository

import tachiyomi.domain.recommendation.model.CachedRecommendation

interface RecommendationCacheRepository {

    suspend fun getBySourceMangaId(sourceMangaId: Long): List<CachedRecommendation>

    suspend fun getAll(): List<CachedRecommendation>

    suspend fun insert(cachedRecommendation: CachedRecommendation)

    suspend fun insertAll(cachedRecommendations: List<CachedRecommendation>)

    suspend fun deleteBySourceMangaId(sourceMangaId: Long)

    suspend fun deleteAll()

    suspend fun deleteExpired(expiryTimestamp: Long)
}
// KMK <--
