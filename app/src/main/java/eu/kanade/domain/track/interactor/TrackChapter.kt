package eu.kanade.domain.track.interactor

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import exh.md.utils.FollowStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

@Inject
class TrackChapter(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
) {

    /**
     * Fetches updated tracking data from all logged in trackers.
     * Then update chapter progress to all trackers.
     * This does not update local chapters' read status.
     */
    suspend fun await(context: Context, mangaId: Long, chapterNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            val tracks = getTracks.await(mangaId)
            if (tracks.isEmpty()) return@withNonCancellableContext

            val preferredId = try { mihon.app.di.globalAppGraph.trackPreferences.getPreferredTrackerForManga(mangaId) } catch (_: Exception) { null }
            val effectivePreferred = preferredId ?: try {
                val cats = mihon.app.di.globalAppGraph.getCategories.await(mangaId).map { it.id }
                cats.firstNotNullOfOrNull { mihon.app.di.globalAppGraph.trackPreferences.getPreferredTrackerForCategory(it) }
            } catch (_: Exception) { null }

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                val isRereadingTrack = track.status == service?.getRereadingStatus()
                if (
                    service == null ||
                    !service.isLoggedIn ||
                    (chapterNumber <= track.lastChapterRead && !isRereadingTrack) ||
                    (service is MdList && track.status == FollowStatus.UNFOLLOWED.long)
                ) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        try {
                            val refreshed = service.refresh(track.toDbTrack()).toDomainTrack(idRequired = true)!!
                            val toUpdate = if (effectivePreferred != null && track.trackerId != effectivePreferred) {
                                val prefTrack = tracks.find { it.trackerId == effectivePreferred }
                                val prefChapter = prefTrack?.lastChapterRead ?: chapterNumber
                                val syncChapter = maxOf(chapterNumber, prefChapter)
                                refreshed.copy(lastChapterRead = syncChapter)
                            } else {
                                refreshed.copy(lastChapterRead = chapterNumber)
                            }
                            val withCompletion = if (toUpdate.totalChapters > 0 && toUpdate.lastChapterRead >= toUpdate.totalChapters) {
                                toUpdate.copy(status = service.getCompletionStatus())
                            } else toUpdate
                            service.update(withCompletion.toDbTrack(), true)
                            insertTrack.await(withCompletion)
                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            delayedTrackingStore.add(track.id, chapterNumber)
                            if (setupJobOnFailure) {
                                DelayedTrackingUpdateJob.setupTask(context)
                            }
                            throw e
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.WARN, it) }
        }
    }
}
