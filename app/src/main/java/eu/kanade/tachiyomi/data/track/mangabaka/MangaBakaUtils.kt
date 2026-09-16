package eu.kanade.tachiyomi.data.track.mangabaka

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.model.SManga

fun Track.toApiStatus() = when (status) {
    MangaBaka.CONSIDERING -> "considering"
    MangaBaka.COMPLETED -> "completed"
    MangaBaka.DROPPED -> "dropped"
    MangaBaka.PAUSED -> "paused"
    MangaBaka.PLAN_TO_READ -> "plan_to_read"
    MangaBaka.READING -> "reading"
    MangaBaka.REREADING -> "rereading"
    else -> "reading"
}

// KMK -->
fun TrackSearch.toMangaMetadata(remoteId: Long): TrackMangaMetadata = TrackMangaMetadata(
    remoteId = remoteId,
    title = title,
    thumbnailUrl = cover_url,
    description = summary,
    authors = authors.joinToString(", ").ifBlank { null },
    tags = tags,
    status = when (publishing_status) {
        "completed" -> SManga.COMPLETED.toLong()
        "releasing" -> SManga.ONGOING.toLong()
        "upcoming" -> SManga.PUBLISHING_FINISHED.toLong()
        "cancelled" -> SManga.CANCELLED.toLong()
        "hiatus" -> SManga.ON_HIATUS.toLong()
        else -> null
    },
)
// KMK <--
