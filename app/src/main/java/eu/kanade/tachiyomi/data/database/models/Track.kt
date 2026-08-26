@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.data.database.models

import java.io.Serializable

// TODO(upstream-parity): This mutable snake_case interface is Mihon upstream's standard
//  tracker transfer model, used by every tracker service. Do NOT migrate it to
//  tachiyomi.domain.track.model.Track casually - that is a full tracker-service-layer
//  rewrite (~44 files) that diverges from upstream for style-only gain.
interface Track : Serializable {

    var id: Long?

    var manga_id: Long

    var tracker_id: Long

    var remote_id: Long

    var library_id: Long?

    var title: String

    var last_chapter_read: Double

    var total_chapters: Long

    var score: Double

    var status: Long

    var started_reading_date: Long

    var finished_reading_date: Long

    var tracking_url: String

    var private: Boolean

    // KMK -->
    var reread_count: Int
    // KMK <--

    fun copyPersonalFrom(other: Track, copyRemotePrivate: Boolean = true) {
        last_chapter_read = other.last_chapter_read
        score = other.score
        status = other.status
        started_reading_date = other.started_reading_date
        finished_reading_date = other.finished_reading_date
        if (copyRemotePrivate) private = other.private
        // KMK -->
        reread_count = other.reread_count
        // KMK <--
    }

    companion object {
        fun create(serviceId: Long): Track = TrackImpl().apply {
            tracker_id = serviceId
        }
    }
}
