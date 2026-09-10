package eu.kanade.tachiyomi.data.track.mangabaka.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val TITLE_PRIORITIES = listOf("en", "ja-Latn", "ja", "ko-Latn", "ko", "zh-Latn", "zh")

@Serializable
data class MangaBakaItemResult(
    val data: MangaBakaItem,
)

@Serializable
data class MangaBakaSearchResult(
    val data: List<MangaBakaItem>,
)

@Serializable
data class MangaBakaItem(
    val id: Long,
    val cover: MangaBakaCover,
    val authors: List<String>?,
    val artists: List<String>?,
    val description: String?,
    val published: MangaBakaPublishData,
    val status: String,
    val type: String,
    val rating: Double?,
    val titles: List<MangaBakaItemTitle>?,
) {
    fun chooseBestTitle(): String {
        val bestTitlePerLanguage = TITLE_PRIORITIES.associateWith { lang ->
            titles?.filter { it.language == lang }
                ?.minByOrNull {
                    when {
                        it.isPrimary -> 0
                        "official" in it.traits -> 1
                        "native" in it.traits -> 2
                        else -> 3
                    }
                }
        }

        return TITLE_PRIORITIES
            .firstNotNullOfOrNull { bestTitlePerLanguage[it]?.title }
            ?: titles?.firstOrNull()?.title
            ?: "ID: $id - Could not find name! (report on the MangaBaka Discord)"
    }
}

@Serializable
data class MangaBakaCover(
    val raw: MangaBakaRawCover? = null,
    val x150: MangaBakaScaledCover? = null,
    val x250: MangaBakaScaledCover? = null,
    val x350: MangaBakaScaledCover? = null,
)

@Serializable
data class MangaBakaScaledCover(
    val x1: String?,
    val x2: String? = null,
    val x3: String? = null,
)

@Serializable
data class MangaBakaRawCover(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    val format: String? = null,
)

@Serializable
data class MangaBakaPublishData(
    @SerialName("start_date")
    val startDate: String?,
)

@Serializable
data class MangaBakaItemTitle(
    val language: String,
    val traits: List<String>,
    val title: String,
    @SerialName("is_primary")
    val isPrimary: Boolean,
)
