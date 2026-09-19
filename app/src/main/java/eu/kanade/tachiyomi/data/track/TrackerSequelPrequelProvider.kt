package eu.kanade.tachiyomi.data.track

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.track.service.TrackPreferences
import exh.md.related.MangaDexSequelPrequelProvider
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.SequelPrequelProvider
import tachiyomi.domain.manga.model.SequelPrequelEntry
import tachiyomi.domain.track.interactor.GetTracks

// KMK -->
/**
 * Sequel/prequel provider that follows tracker metadata instead of MangaDex:
 * the per-manga preferred tracker first, then the appwide priority tracker,
 * then every other logged-in tracker in order. Each service reports its
 * relations via [BaseTracker.getRelatedEntries] (null when unsupported —
 * currently only AniList), so services added later plug in with one override.
 * Results stay cached in
 * [tachiyomi.domain.manga.interactor.RelatedMangaCache] (24h TTL), so tracker
 * APIs see at most one fetch per manga per day.
 *
 * When no tracker in the chain yields relations (untracked manga, or none of
 * the bound services expose relations), the MangaDex API is tried as a
 * last resort for MangaDex-based manga, preserving the pre-rework behavior.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TrackerSequelPrequelProvider(
    private val getManga: GetManga,
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val trackPreferences: TrackPreferences,
    private val mangaDexFallback: MangaDexSequelPrequelProvider,
) : SequelPrequelProvider {
    override suspend fun fetch(mangaId: Long, preferredTrackerId: Long?): List<SequelPrequelEntry> {
        getManga.await(mangaId) ?: return emptyList()
        val tracks = try {
            getTracks.await(mangaId)
        } catch (_: Exception) {
            emptyList()
        }
        val orderedIds = (
            listOfNotNull(preferredTrackerId, trackPreferences.getPriorityTrackerId()) +
                trackerManager.trackers.filter { it.isLoggedIn }.map { it.id }
            ).distinct()
        for (trackerId in orderedIds) {
            val service = trackerManager.get(trackerId) as? BaseTracker ?: continue
            if (!service.isLoggedIn) continue
            val remoteId = tracks.find { it.trackerId == trackerId }?.remoteId?.takeIf { it > 0 } ?: continue
            val entries = try {
                service.getRelatedEntries(remoteId)
            } catch (_: Exception) {
                null
            }.orEmpty()
            if (entries.isNotEmpty()) return entries
        }
        return try {
            mangaDexFallback.fetch(mangaId, preferredTrackerId)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
// KMK <--
