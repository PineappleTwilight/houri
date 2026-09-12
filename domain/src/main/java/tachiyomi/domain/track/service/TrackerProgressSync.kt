package tachiyomi.domain.track.service

import tachiyomi.domain.track.model.Track
import kotlin.math.abs

object TrackerProgressSync {

    private const val EPSILON = 0.01

    fun hasMismatch(tracks: List<Track>): Boolean {
        if (tracks.size < 2) return false
        val max = tracks.maxOfOrNull { it.lastChapterRead } ?: return false
        return tracks.any { abs(it.lastChapterRead - max) > EPSILON }
    }

    fun mismatchedIds(tracks: List<Track>): Set<Long> {
        if (tracks.size < 2) return emptySet()
        val max = tracks.maxOfOrNull { it.lastChapterRead } ?: return emptySet()
        return tracks.filter { abs(it.lastChapterRead - max) > EPSILON }.map { it.trackerId }.toSet()
    }

    fun hasError(tracks: List<Track>, errorTrackerIds: Set<Long>): Boolean {
        if (tracks.isEmpty()) return false
        return errorTrackerIds.isNotEmpty()
    }

    fun preferredValue(tracks: List<Track>, preferredId: Long?): Double? {
        if (preferredId == null) return null
        return tracks.find { it.trackerId == preferredId }?.lastChapterRead
    }

    fun resolvePreferredTrack(
        tracks: List<Track>,
        preferredTrackerId: Long?,
    ): Track? {
        if (tracks.isEmpty()) return null
        if (preferredTrackerId != null) {
            tracks.find { it.trackerId == preferredTrackerId }?.let { return it }
        }
        return tracks.maxByOrNull { it.lastChapterRead }
    }

    fun shouldHighlightMismatch(
        tracks: List<Track>,
        preferredId: Long?,
    ): Boolean {
        if (preferredId == null) return false
        val preferred = tracks.find { it.trackerId == preferredId } ?: return false
        val max = tracks.maxOfOrNull { it.lastChapterRead } ?: return false
        return abs(preferred.lastChapterRead - max) > EPSILON
    }

    fun maxProgress(tracks: List<Track>): Double = tracks.maxOfOrNull { it.lastChapterRead } ?: 0.0
}
