package eu.kanade.domain.track.interactor

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import exh.md.utils.FollowStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.SyncCategoriesWithTrackStatus
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.service.TrackerProgressSync

@Inject
class TrackChapter(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
    // KMK -->
    private val syncCategoriesWithTrackStatus: SyncCategoriesWithTrackStatus,
    // KMK <--
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

            val trackPrefs = try {
                mihon.app.di.globalAppGraph.trackPreferences
            } catch (_: Exception) {
                null
            }
            val autoSync = try {
                trackPrefs?.autoSyncProgressFromTrackers()?.get()
            } catch (_: Exception) {
                true
            } ?: true
            val preferredId = try {
                trackPrefs?.getPreferredTrackerForManga(mangaId)
            } catch (_: Exception) {
                null
            }
            val effectivePreferred = if (!autoSync) {
                null
            } else {
                preferredId ?: try {
                    val cats = mihon.app.di.globalAppGraph.getCategories.await(mangaId).map { it.id }
                    cats.firstNotNullOfOrNull { trackPrefs?.getPreferredTrackerForCategory(it) }
                } catch (_: Exception) {
                    null
                }
            }

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
                            val globalMax = TrackerProgressSync.maxProgress(tracks)
                            val toUpdate = if (effectivePreferred != null && track.trackerId != effectivePreferred) {
                                val prefTrack = tracks.find { it.trackerId == effectivePreferred }
                                val prefChapter = prefTrack?.lastChapterRead ?: chapterNumber
                                val syncChapter = maxOf(chapterNumber, prefChapter, globalMax)
                                refreshed.copy(lastChapterRead = syncChapter)
                            } else {
                                val syncChapter = maxOf(chapterNumber, globalMax)
                                refreshed.copy(lastChapterRead = syncChapter)
                            }
                            val withCompletion = if (
                                toUpdate.totalChapters > 0 &&
                                toUpdate.lastChapterRead >= toUpdate.totalChapters &&
                                // KMK --> MangaBaka's total is a live "released so far" count, so the
                                // series' publishing status vetoes the completion when it is still
                                // releasing. The tracker hook fails closed on any error.
                                service.shouldAutoCompleteOnCatchUp(toUpdate.toDbTrack())
                                // KMK <--
                            ) {
                                toUpdate.copy(status = service.getCompletionStatus())
                            } else {
                                toUpdate
                            }
                            // KMK --> leaving a not-started list starts the clock when no start date is set
                            val withStartDate =
                                if (service.hasNotStartedReading(track.status) &&
                                    !service.hasNotStartedReading(withCompletion.status) &&
                                    withCompletion.startDate <= 0L
                                ) {
                                    withCompletion.copy(startDate = System.currentTimeMillis())
                                } else {
                                    withCompletion
                                }
                            // KMK <--
                            // KMK --> The remote service is what promotes the track to Reading/Completed on a chapter read;
                            // this interactor persists only the local completion decision, so READING
                            // never lands in the local row. Without this the status categories wait
                            // for a RefreshTracks run, which is manual-only - and never fire at all
                            // for the completion case, because by then the local status already
                            // equals the remote one and RefreshTracks' "did not change" guard skips it.
                            val remoteUpdated = service.update(withStartDate.toDbTrack(), true)
                            val effectiveStatus = if (withStartDate.status != refreshed.status) {
                                withStartDate.status
                            } else {
                                remoteUpdated.status
                            }
                            if (effectiveStatus != track.status) {
                                runCatching {
                                    val parsed = TrackStatus.parseTrackerStatus(
                                        trackerManager,
                                        track.trackerId,
                                        effectiveStatus,
                                    )
                                    syncCategoriesWithTrackStatus.await(track.mangaId, parsed?.int)
                                }
                            }
                            // KMK <--
                            insertTrack.await(withStartDate)
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
