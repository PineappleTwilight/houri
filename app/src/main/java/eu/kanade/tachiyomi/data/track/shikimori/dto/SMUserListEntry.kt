package eu.kanade.tachiyomi.data.track.shikimori.dto

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
import eu.kanade.tachiyomi.data.track.shikimori.toTrackStatus
import kotlinx.serialization.Serializable

@Serializable
data class SMUserListEntry(
    val id: Long,
    val chapters: Double,
    val score: Int,
    val status: String,
    // KMK -->
    val rewatches: Int = 0,
    // KMK <--
) {
    fun toTrack(trackId: Long, manga: SMManga): Track {
        return Track.create(trackId).apply {
            title = manga.name
            remote_id = this@SMUserListEntry.id
            total_chapters = manga.chapters
            library_id = this@SMUserListEntry.id
            last_chapter_read = this@SMUserListEntry.chapters
            score = this@SMUserListEntry.score.toDouble()
            status = toTrackStatus(this@SMUserListEntry.status)
            tracking_url = ShikimoriApi.BASE_URL + manga.url
            // KMK -->
            reread_count = this@SMUserListEntry.rewatches
            // KMK <--
        }
    }
}
