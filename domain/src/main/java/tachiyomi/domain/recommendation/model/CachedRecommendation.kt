// KMK -->
package tachiyomi.domain.recommendation.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

data class CachedRecommendation(
    val id: Long,
    val sourceMangaId: Long,
    val recSourceName: String,
    val recSourceCategoryResId: Int,
    val recAssociatedSourceId: Long?,
    val results: JsonObject,
    val cachedAt: Long,
) : Serializable
// KMK <--
