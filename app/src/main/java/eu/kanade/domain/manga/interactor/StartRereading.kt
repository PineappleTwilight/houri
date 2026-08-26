package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import exh.md.utils.FollowStatus
import exh.source.isMergedSourceId
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.interactor.GetTracks

@Inject
class StartRereading(
    private val mangaRepository: MangaRepository,
    private val setReadStatus: SetReadStatus,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId,
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val getIncognitoState: GetIncognitoState,
    private val trackPreferences: TrackPreferences,
) {

    suspend fun await(manga: Manga): Result {
        if (!manga.favorite || manga.rereading) return Result.Failure

        val now = System.currentTimeMillis()
        mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                rereading = true,
                rereadStartedAt = now,
            ),
        )

        val chapters = chaptersOf(manga)
        setReadStatus.await(
            read = false,
            manually = false,
            chapters = chapters.toTypedArray(),
        )

        syncTrackers(manga)

        return Result.Success(now)
    }

    private suspend fun chaptersOf(manga: Manga) = if (isMergedSourceId(manga.source)) {
        getMergedChaptersByMangaId.await(manga.id, dedupe = false)
    } else {
        getChaptersByMangaId.await(manga.id)
    }

    private suspend fun syncTrackers(manga: Manga) {
        if (getIncognitoState.await(manga.source)) return
        if (!trackPreferences.autoUpdateTrack().get()) return

        getTracks.await(manga.id).forEach { track ->
            val service = trackerManager.get(track.trackerId)
            if (
                service == null ||
                !service.isLoggedIn ||
                service.getRereadingStatus() == -1L ||
                // SY -->
                (service is MdList && track.status == FollowStatus.UNFOLLOWED.long)
                // SY <--
            ) {
                return@forEach
            }
            try {
                service.setRemoteStatus(track.toDbTrack(), service.getRereadingStatus())
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to mark rereading on ${service.name} for manga ${manga.id}" }
            }
        }
    }

    sealed interface Result {
        data class Success(val startedAt: Long) : Result
        data object Failure : Result
    }
}
