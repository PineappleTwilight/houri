package eu.kanade.tachiyomi.data.track

import tachiyomi.domain.track.model.Track

/**
 * Coordinates tracker sync for the unified-tracker feature (pref per-manga/per-category
 * preferred tracker). Previously the “which tracker is authoritative?” logic was
 * inlined in `TrackInfoDialogHome` yellow-highlight, `TrackChapter.await`, and
 * `MangaScreenModel` fill-metadata, duplicating the `max chapter progress` rule.
 *
 * Single-responsibility: resolves the preferred tracker and syncs chapter progress
 * atomically, keeping the 3 call sites consistent. Pure logic, no Android deps.
 */
object TrackerSyncCoordinator {

    fun resolvePreferredTrack(
        tracks: List<Track>,
        preferredTrackerId: Long?,
    ): Track? = tracks.find { it.trackerId == preferredTrackerId } ?: tracks.maxByOrNull { it.lastChapterRead }

    fun shouldHighlightMismatch(
        tracks: List<Track>,
        preferredId: Long?,
    ): Boolean {
        val preferred = tracks.find { it.trackerId == preferredId } ?: return false
        val max = tracks.maxOfOrNull { it.lastChapterRead } ?: return false
        return preferred.lastChapterRead != max
    }

    fun maxProgress(tracks: List<Track>): Double = tracks.maxOfOrNull { it.lastChapterRead } ?: 0.0
}
