// KMK -->
package tachiyomi.domain.recommendation.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.recommendation.repository.RecommendationCacheRepository

@Inject
class DeleteCachedRecommendations(
    private val repository: RecommendationCacheRepository,
) {

    suspend fun deleteBySourceMangaId(sourceMangaId: Long) = withNonCancellableContext {
        try {
            repository.deleteBySourceMangaId(sourceMangaId)
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }
    }

    suspend fun deleteAll() = withNonCancellableContext {
        try {
            repository.deleteAll()
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }
    }

    suspend fun deleteExpired(expiryTimestamp: Long) = withNonCancellableContext {
        try {
            repository.deleteExpired(expiryTimestamp)
            Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }
    }

    sealed class Result {
        data object Success : Result()
        data class InternalError(val error: Throwable) : Result()
    }
}
// KMK <--
