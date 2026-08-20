// KMK -->
package tachiyomi.domain.recommendation.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.recommendation.interactor.DeleteCachedRecommendations.Result
import tachiyomi.domain.recommendation.model.CachedRecommendation
import tachiyomi.domain.recommendation.repository.RecommendationCacheRepository

@Inject
class InsertCachedRecommendations(
    private val repository: RecommendationCacheRepository,
) {

    suspend fun insert(cachedRecommendation: CachedRecommendation) = withNonCancellableContext {
        try {
            repository.insert(cachedRecommendation)
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }
    }

    suspend fun insertAll(cachedRecommendations: List<CachedRecommendation>) = withNonCancellableContext {
        try {
            repository.insertAll(cachedRecommendations)
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }
    }
}
// KMK <--
