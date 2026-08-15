// KMK -->
package tachiyomi.data.recommendation

import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.recommendation.model.CachedRecommendation

val cachedRecommendationMapper: (
    Long,
    Long,
    String,
    Long,
    Long?,
    JsonObject,
    Long,
) -> CachedRecommendation =
    { id, sourceMangaId, recSourceName, recSourceCategoryResId, recAssociatedSourceId, results, cachedAt ->
        CachedRecommendation(
            id = id,
            sourceMangaId = sourceMangaId,
            recSourceName = recSourceName,
            recSourceCategoryResId = recSourceCategoryResId.toInt(),
            recAssociatedSourceId = recAssociatedSourceId,
            results = results,
            cachedAt = cachedAt,
        )
    }
// KMK <--
