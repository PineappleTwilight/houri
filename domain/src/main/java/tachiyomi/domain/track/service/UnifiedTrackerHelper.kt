package tachiyomi.domain.track.service

import tachiyomi.domain.track.model.Track
import kotlin.math.abs

object UnifiedTrackerHelper {
    fun hasMismatch(tracks: List<Track>): Boolean {
        if (tracks.size < 2) return false
        val values = tracks.map { it.lastChapterRead }.distinct()
        return values.size > 1
    }

    fun mismatchedIds(tracks: List<Track>): Set<Long> {
        if (tracks.size < 2) return emptySet()
        val max = tracks.maxOf { it.lastChapterRead }
        return tracks.filter { abs(it.lastChapterRead - max) > 0.01 }.map { it.trackerId }.toSet()
    }

    fun hasError(tracks: List<Track>, errorTrackerIds: Set<Long>): Boolean {
        return errorTrackerIds.isNotEmpty()
    }

    fun preferredValue(tracks: List<Track>, preferredId: Long?): Double? {
        if (preferredId == null) return null
        return tracks.find { it.trackerId == preferredId }?.lastChapterRead
    }
}
