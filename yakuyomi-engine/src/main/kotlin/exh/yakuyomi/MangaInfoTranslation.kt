package exh.yakuyomi

import kotlinx.serialization.Serializable

/** Cached on-device translation of a manga's metadata (title + description). */
@Serializable
data class MangaInfoTranslation(
    val title: String,
    val description: String? = null,
)
