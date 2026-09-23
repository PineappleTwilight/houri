package eu.kanade.tachiyomi.data.track

import android.content.Context
import androidx.annotation.CallSuper
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.core.TrackerException
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.globalAppGraph
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.SequelPrequelEntry
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track as DomainTrack

abstract class BaseTracker(
    override val id: Long,
    override val name: String,
) : Tracker {

    // KMK -->
    protected val appGraph: AppGraph get() = globalAppGraph
    private val context: Context by lazy { appGraph.context }
    // KMK <--

    val trackPreferences: TrackPreferences by lazy { appGraph.trackPreferences }
    val networkService: NetworkHelper by lazy { appGraph.networkHelper }
    private val addTracks: AddTracks by lazy { appGraph.addTracks }
    private val insertTrack: InsertTrack by lazy { appGraph.insertTrack }

    override val client: OkHttpClient
        get() = networkService.client

    // Application and remote support for reading dates
    override val supportsReadingDates: Boolean = false

    override val supportsPrivateTracking: Boolean = false

    // KMK --> clamp to 0..10 so a bad remote value cannot skew cross-tracker
    // averages (get10PointScore is documented as a 0..10 normalization).
    override fun get10PointScore(track: DomainTrack): Double {
        return track.score.coerceIn(0.0, 10.0)
    }
    // KMK <--

    override fun indexToScore(index: Int): Double {
        return index.toDouble()
    }

    @CallSuper
    override fun logout() {
        trackPreferences.setCredentials(this, "", "")
    }

    override val isLoggedIn: Boolean
        get() = getUsername().isNotEmpty() &&
            getPassword().isNotEmpty()

    override val isLoggedInFlow: Flow<Boolean> by lazy {
        combine(
            trackPreferences.trackUsername(this).changes(),
            trackPreferences.trackPassword(this).changes(),
        ) { username, password ->
            username.isNotEmpty() && password.isNotEmpty()
        }
    }

    override fun getUsername() = trackPreferences.trackUsername(this).get()

    override fun getPassword() = trackPreferences.trackPassword(this).get()

    override fun saveCredentials(username: String, password: String) {
        trackPreferences.setCredentials(this, username, password)
    }

    override suspend fun register(item: Track, mangaId: Long) {
        item.manga_id = mangaId
        try {
            addTracks.bind(this, item, mangaId)
        } catch (e: Throwable) {
            val wrapped = if (e is TrackerException) e else TrackerException.NetworkError(id, e)
            logcat(LogPriority.ERROR, wrapped) { "Failed to register track ${item.title} id=$id" }
            throw wrapped
        }
    }

    override suspend fun setRemoteStatus(track: Track, status: Long) {
        // KMK --> leaving a not-started list starts the clock when no start date is set
        val wasNotStarted = hasNotStartedReading(track.status)
        // KMK <--
        track.status = status
        if (track.status == getCompletionStatus() && track.total_chapters != 0L) {
            track.last_chapter_read = track.total_chapters.toDouble()
        }
        // KMK -->
        if (wasNotStarted && !hasNotStartedReading(status) && track.started_reading_date <= 0L) {
            track.started_reading_date = System.currentTimeMillis()
        }
        // KMK <--
        updateRemote(track)
    }

    override suspend fun setRemoteLastChapterRead(track: Track, chapterNumber: Int): /* KMK --> */ Track /* KMK <-- */ {
        if (
            track.last_chapter_read == 0.0 &&
            track.last_chapter_read < chapterNumber &&
            track.status != getRereadingStatus()
        ) {
            track.status = getReadingStatus()
        }
        track.last_chapter_read = chapterNumber.toDouble()
        if (track.total_chapters != 0L && track.last_chapter_read.toLong() == track.total_chapters) {
            track.status = getCompletionStatus()
            track.finished_reading_date = System.currentTimeMillis()
        }
        updateRemote(track)
        // KMK -->
        return track
        // KMK <--
    }

    override suspend fun setRemoteScore(track: Track, scoreString: String) {
        val scores = getScoreList()
        var index = scores.indexOf(scoreString)
        if (index < 0) {
            // KMK --> score wheel strings can drift from stored values (e.g. fractional
            // MangaBaka ratings like 85.5 displaying as "85" off the STEP_10 grid, or
            // MangaUpdates values with extra decimals). Snap to the closest numeric
            // entry instead of crashing in indexToScore(-1).
            index = scores.indices.minByOrNull {
                val candidate = scores[it].toDoubleOrNull()
                val wanted = scoreString.toDoubleOrNull()
                if (candidate != null && wanted != null) {
                    kotlin.math.abs(candidate - wanted)
                } else {
                    Double.MAX_VALUE
                }
            } ?: return
            if (scores[index].toDoubleOrNull() == null) return
        }
        track.score = indexToScore(index)
        updateRemote(track)
    }

    override suspend fun setRemoteStartDate(track: Track, epochMillis: Long) {
        track.started_reading_date = epochMillis
        updateRemote(track)
    }

    override suspend fun setRemoteFinishDate(track: Track, epochMillis: Long) {
        track.finished_reading_date = epochMillis
        updateRemote(track)
    }

    override suspend fun setRemotePrivate(track: Track, private: Boolean) {
        track.private = private
        updateRemote(track)
    }

    // KMK -->
    override val supportsRereadCount: Boolean = false

    override suspend fun setRemoteRereadCount(track: Track, rereadCount: Int) {
        track.reread_count = rereadCount
        updateRemote(track)
    }
    // KMK <--

    override suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        throw NotImplementedError("Not implemented.")
    }

    // SY -->
    override suspend fun searchById(id: String): TrackSearch? {
        throw NotImplementedError("Not implemented.")
    }
    // SY <--

    // KMK -->
    /**
     * Sequel/prequel entries for this service's [remoteId], or null when the
     * service exposes no relations API. The sequel/prequel provider tries the
     * priority tracker first, then every other logged-in tracker in order.
     */
    open suspend fun getRelatedEntries(remoteId: Long): List<SequelPrequelEntry>? = null

    /**
     * Extracts this service's remote id from a [SequelPrequelEntry.url] it
     * produced (AniList siteUrl, MangaBaka tracking_url), or null when the url
     * is not one of ours. Lets tap resolution build the stub from our own
     * metadata instead of falling through to the website.
     */
    open fun parseRelatedEntryId(url: String): Long? = null
    // KMK <--

    private suspend fun updateRemote(track: Track): Unit = withIOContext {
        try {
            update(track)
            track.toDomainTrack(idRequired = false)?.let {
                insertTrack.await(it)
            }
        } catch (e: Exception) {
            val wrapped = when (e) {
                is TrackerException -> e
                is java.io.IOException -> TrackerException.NetworkError(id, e)
                else -> TrackerException.NetworkError(id, e)
            }
            logcat(LogPriority.ERROR, wrapped) { "Failed to update remote track data id=$id name=$name" }
            throw wrapped
        }
    }
}
