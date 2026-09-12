package tachiyomi.domain.track.service

import tachiyomi.domain.track.model.Track
import kotlin.math.abs

@Deprecated("Use TrackerProgressSync", ReplaceWith("TrackerProgressSync"))
object UnifiedTrackerHelper {
    fun hasMismatch(tracks: List<Track>): Boolean = TrackerProgressSync.hasMismatch(tracks)

    fun mismatchedIds(tracks: List<Track>): Set<Long> = TrackerProgressSync.mismatchedIds(tracks)

    fun hasError(tracks: List<Track>, errorTrackerIds: Set<Long>): Boolean =
        TrackerProgressSync.hasError(tracks, errorTrackerIds)

    fun preferredValue(tracks: List<Track>, preferredId: Long?): Double? =
        TrackerProgressSync.preferredValue(tracks, preferredId)
}
