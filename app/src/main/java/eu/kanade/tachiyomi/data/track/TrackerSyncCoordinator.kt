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
@Deprecated("Use TrackerProgressSync", ReplaceWith("TrackerProgressSync", "tachiyomi.domain.track.service.TrackerProgressSync"))
object TrackerSyncCoordinator {

    fun resolvePreferredTrack(
        tracks: List<Track>,
        preferredTrackerId: Long?,
    ): Track? = tachiyomi.domain.track.service.TrackerProgressSync.resolvePreferredTrack(tracks, preferredTrackerId)

    fun shouldHighlightMismatch(
        tracks: List<Track>,
        preferredId: Long?,
    ): Boolean = tachiyomi.domain.track.service.TrackerProgressSync.shouldHighlightMismatch(tracks, preferredId)

    fun maxProgress(tracks: List<Track>): Double =
        tachiyomi.domain.track.service.TrackerProgressSync.maxProgress(tracks)
}
