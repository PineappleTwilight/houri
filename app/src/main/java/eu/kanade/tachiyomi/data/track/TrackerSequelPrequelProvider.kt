package eu.kanade.tachiyomi.data.track

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import exh.md.related.MangaDexSequelPrequelProvider
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.SequelPrequelProvider
import tachiyomi.domain.manga.model.SequelPrequelEntry
import tachiyomi.domain.manga.model.SequelPrequelRelation
import tachiyomi.domain.track.interactor.GetTracks

// KMK -->
/**
 * Sequel/prequel provider that follows the user's preferred tracker instead of
 * assuming MangaDex. AniList-bound entries resolve via the public relations
 * edge; anything else (or an empty AniList result) falls back to the MangaDex
 * provider, which no-ops for non-MangaDex sources. Results stay cached in
 * [tachiyomi.domain.manga.interactor.RelatedMangaCache] (24h TTL), so tracker
 * APIs see at most one fetch per manga per day.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TrackerSequelPrequelProvider(
    private val getManga: GetManga,
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val mangaDexProvider: MangaDexSequelPrequelProvider,
) : SequelPrequelProvider {
    override suspend fun fetch(mangaId: Long, preferredTrackerId: Long?): List<SequelPrequelEntry> {
        getManga.await(mangaId) ?: return emptyList()
        val tracks = try {
            getTracks.await(mangaId)
        } catch (_: Exception) {
            emptyList()
        }
        val anilistTrack = tracks.find { it.trackerId == preferredTrackerId && it.trackerId == TrackerManager.ANILIST }
            ?: tracks.find { it.trackerId == TrackerManager.ANILIST }?.takeIf { preferredTrackerId == null }
        if (anilistTrack != null && anilistTrack.remoteId > 0) {
            val relations = try {
                (trackerManager.get(TrackerManager.ANILIST) as? Anilist)
                    ?.getMangaRelations(anilistTrack.remoteId)
            } catch (_: Exception) {
                null
            }
            val entries = relations?.data?.media?.relations?.edges?.mapNotNull { edge ->
                val relation = SequelPrequelRelation.fromAniList(edge.relationType)
                    ?.takeIf { it == SequelPrequelRelation.PREQUEL || it == SequelPrequelRelation.SEQUEL }
                    ?: return@mapNotNull null
                val title = edge.node.title.display()?.ifBlank { null } ?: return@mapNotNull null
                SequelPrequelEntry(
                    title = title,
                    url = edge.node.siteUrl,
                    relation = relation,
                    trackerId = TrackerManager.ANILIST,
                )
            }.orEmpty()
            if (entries.isNotEmpty()) return entries
        }
        return try {
            mangaDexProvider.fetch(mangaId, preferredTrackerId)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
// KMK <--
