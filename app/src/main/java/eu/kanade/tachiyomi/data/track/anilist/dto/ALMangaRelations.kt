package eu.kanade.tachiyomi.data.track.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// KMK --> Prequel/sequel relations for the tracker-generalized sequel/prequel provider.
@Serializable
data class ALMangaRelations(
    val data: ALRelationsData,
)

@Serializable
data class ALRelationsData(
    @SerialName("Media")
    val media: ALRelationsMedia,
)

@Serializable
data class ALRelationsMedia(
    val relations: ALRelationsConnection,
)

@Serializable
data class ALRelationsConnection(
    val edges: List<ALRelationEdge>,
)

@Serializable
data class ALRelationEdge(
    val relationType: String,
    val node: ALRelationNode,
)

@Serializable
data class ALRelationNode(
    val id: Long,
    val title: ALRelationTitle,
    val siteUrl: String,
)

@Serializable
data class ALRelationTitle(
    val romaji: String? = null,
    val english: String? = null,
    val userPreferred: String? = null,
) {
    fun display(): String? = userPreferred ?: english ?: romaji
}
// KMK <--
