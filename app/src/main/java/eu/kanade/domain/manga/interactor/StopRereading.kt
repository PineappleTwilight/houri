package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.source.MERGED_SOURCE_ID
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.interactor.GetTracks

@Inject
class StopRereading(
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
        if (!manga.rereading) return Result.Failure

        // Clear the flag first so restoring chapter read state below cannot
        // be mistaken for a completed reread
        mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                rereading = false,
                rereadStartedAt = 0L,
            ),
        )

        val chapters = if (manga.source == MERGED_SOURCE_ID) {
            getMergedChaptersByMangaId.await(manga.id, dedupe = false)
        } else {
            getChaptersByMangaId.await(manga.id)
        }
        val startedAt = manga.rereadStartedAt
        val chaptersToRestore = chapters.filter { chapter ->
            !chapter.read && (startedAt == 0L || chapter.dateFetch <= startedAt)
        }
        setReadStatus.await(
            read = true,
            manually = false,
            chapters = chaptersToRestore.toTypedArray(),
        )

        syncTrackers(manga)

        return Result.Success
    }

    private suspend fun syncTrackers(manga: Manga) {
        if (getIncognitoState.await(manga.source)) return
        if (!trackPreferences.autoUpdateTrack().get()) return

        getTracks.await(manga.id).forEach { track ->
            val service = trackerManager.get(track.trackerId)
            if (service == null || !service.isLoggedIn) return@forEach

            try {
                if (
                    track.status == service.getRereadingStatus() &&
                    service.getCompletionStatus() != -1L
                ) {
                    service.setRemoteStatus(track.toDbTrack(), service.getCompletionStatus())
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to restore tracker status on ${service.name} for manga ${manga.id}" }
            }
        }
    }

    sealed interface Result {
        data object Success : Result
        data object Failure : Result
    }
}
