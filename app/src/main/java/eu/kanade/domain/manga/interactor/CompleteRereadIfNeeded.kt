package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.source.MERGED_SOURCE_ID
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

@Inject
class CompleteRereadIfNeeded(
    private val mangaRepository: MangaRepository,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val trackerManager: TrackerManager,
    private val getIncognitoState: GetIncognitoState,
    private val trackPreferences: TrackPreferences,
) {

    /**
     * Completes an active reread when every chapter of the manga is marked as read again:
     * increments the local reread count, clears the rereading state and pushes the new
     * count/status to any logged in trackers.
     *
     * Local state is always recorded, even in incognito mode; tracker pushes are not.
     */
    suspend fun await(mangaId: Long): Boolean {
        val manga = mangaRepository.getMangaById(mangaId)
        if (!manga.rereading) return false

        val chapters = if (manga.source == MERGED_SOURCE_ID) {
            getMergedChaptersByMangaId.await(manga.id, dedupe = false)
        } else {
            getChaptersByMangaId.await(manga.id)
        }
        if (chapters.isEmpty() || !chapters.all { it.read }) return false

        mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                rereadCount = manga.rereadCount + 1,
                rereading = false,
                rereadStartedAt = 0L,
            ),
        )

        syncTrackers(manga.source, mangaId, manga.rereadCount + 1)

        return true
    }

    private suspend fun syncTrackers(sourceId: Long, mangaId: Long, rereadCount: Int) {
        if (getIncognitoState.await(sourceId)) return
        if (!trackPreferences.autoUpdateTrack().get()) return

        getTracks.await(mangaId).forEach { track ->
            val service = trackerManager.get(track.trackerId)
            if (service == null || !service.isLoggedIn) return@forEach

            try {
                if (service.supportsRereadCount) {
                    // Refresh first so remote-only fields are preserved before pushing the final state
                    val dbTrack = runCatching { service.refresh(track.toDbTrack()) }
                        .getOrElse {
                            logcat(LogPriority.WARN) { "Failed to refresh ${service.name} track for manga $mangaId" }
                            track.toDbTrack()
                        }
                    dbTrack.reread_count = rereadCount
                    if (dbTrack.status == service.getRereadingStatus()) {
                        dbTrack.status = service.getCompletionStatus()
                        if (dbTrack.total_chapters != 0L) {
                            dbTrack.last_chapter_read = dbTrack.total_chapters.toDouble()
                        }
                    }
                    service.update(dbTrack)
                    dbTrack.toDomainTrack(idRequired = false)?.let { insertTrack.await(it) }
                } else if (track.status == service.getRereadingStatus()) {
                    service.setRemoteStatus(track.toDbTrack(), service.getCompletionStatus())
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to push reread completion to ${service.name} for manga $mangaId" }
            }
        }
    }
}
