package eu.kanade.tachiyomi.data.track.comick

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.comick.ComicKApi.Companion.TYPE_COMPLETED
import eu.kanade.tachiyomi.data.track.comick.ComicKApi.Companion.TYPE_DROPPED
import eu.kanade.tachiyomi.data.track.comick.ComicKApi.Companion.TYPE_ON_HOLD
import eu.kanade.tachiyomi.data.track.comick.ComicKApi.Companion.TYPE_PLANNING
import eu.kanade.tachiyomi.data.track.comick.ComicKApi.Companion.TYPE_READING
import eu.kanade.tachiyomi.data.track.comick.ComicKApi.Companion.TYPE_UNFOLLOW
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

class ComicK(id: Long) : BaseTracker(id, "ComicK"), DeletableTracker {

    companion object {
        // Tracker status constants
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val PLAN_TO_READ = 4L
        const val DROPPED = 5L

        private val SCORE_LIST = (0..10)
            .map { if (it == 0) "-" else it.toString() }
            .toImmutableList()

        private const val SEARCH_ID_PREFIX = "id:"
        private const val ANON_USERNAME = "comick-user"
    }

    private val interceptor by lazy { ComicKInterceptor(this) }

    private val api by lazy { ComicKApi(id, client, interceptor) }

    override fun getLogo(): Int = R.drawable.brand_comick

    override fun getStatusList(): List<Long> {
        return listOf(READING, COMPLETED, ON_HOLD, PLAN_TO_READ, DROPPED)
    }

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        PLAN_TO_READ -> MR.strings.plan_to_read
        DROPPED -> MR.strings.dropped
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = READING // ComicK has no re-reading concept

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> = SCORE_LIST

    override fun indexToScore(index: Int): Double = index.toDouble()

    override fun displayScore(track: DomainTrack): String = track.score.toInt().toString()

    /**
     * Map tracker status to ComicK API type.
     */
    private fun statusToApiType(status: Long): Int = when (status) {
        READING -> TYPE_READING
        COMPLETED -> TYPE_COMPLETED
        ON_HOLD -> TYPE_ON_HOLD
        PLAN_TO_READ -> TYPE_PLANNING
        DROPPED -> TYPE_DROPPED
        else -> TYPE_UNFOLLOW
    }

    /**
     * Map ComicK API type to tracker status.
     */
    private fun apiTypeToStatus(type: Int): Long = when (type) {
        TYPE_READING -> READING
        TYPE_COMPLETED -> COMPLETED
        TYPE_ON_HOLD -> ON_HOLD
        TYPE_PLANNING -> PLAN_TO_READ
        TYPE_DROPPED -> DROPPED
        else -> PLAN_TO_READ
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (track.status != COMPLETED) {
            if (didReadChapter) {
                track.status = READING
                if (track.started_reading_date == 0L) {
                    track.started_reading_date = System.currentTimeMillis()
                }
            }
        }
        api.followComic(track.remote_id, statusToApiType(track.status))
        return track
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val remoteId = track.remote_id
        if (remoteId == 0L) return track

        // Check if already following
        val followStatus = api.getFollowStatus(remoteId)
        if (followStatus != null) {
            // Already following - read current status
            track.status = apiTypeToStatus(followStatus.t)
        } else {
            // Not following - set initial status
            val status = if (hasReadChapters) READING else PLAN_TO_READ
            track.status = status
            api.followComic(remoteId, statusToApiType(status))
        }
        return track
    }

    override suspend fun search(query: String): List<TrackSearch> {
        if (query.startsWith(SEARCH_ID_PREFIX)) {
            query.substringAfter(SEARCH_ID_PREFIX).trim().let { hid ->
                return api.getMangaDetails(hid)?.let { listOf(it) } ?: emptyList()
            }
        }
        return api.search(query)
    }

    override suspend fun refresh(track: Track): Track {
        val followStatus = api.getFollowStatus(track.remote_id)
        if (followStatus != null) {
            track.status = apiTypeToStatus(followStatus.t)
        }
        return track
    }

    override suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        val remote = api.getMangaDetails(track.remoteId.toString()) ?: throw Exception("Could not find manga")
        return TrackMangaMetadata(
            remoteId = track.remoteId,
            title = remote.title,
            thumbnailUrl = remote.cover_url,
            description = remote.summary,
            authors = remote.authors?.joinToString(", "),
        )
    }

    override suspend fun login(username: String, password: String) = loginWithCookie(password, username)

    /**
     * Login with session cookie. The cookie is the value of the ory_kratos_session cookie.
     */
    suspend fun loginWithCookie(sessionCookie: String, username: String = "") {
        saveCredentials(username.ifBlank { ANON_USERNAME }, sessionCookie)
        interceptor.newAuth(sessionCookie)
    }

    fun restoreSession(): String? {
        return trackPreferences.trackPassword(this).get().ifBlank { null }
    }

    /**
     * Persist the current cookie header string to preferences so the
     * [ComicKInterceptor] can restore its [CookieJar] on restart.
     */
    fun saveCookieHeader(cookieHeader: String) {
        trackPreferences.trackPassword(this).set(cookieHeader)
    }

    /**
     * Returns the full cookie header string for API requests.
     * Backward-compatible: old installs stored just the session value,
     * new installs store the full "name=value; name2=value2" cookie header.
     */
    fun restoreCookieHeader(): String? {
        val stored = trackPreferences.trackPassword(this).get().ifBlank { null } ?: return null
        return if (stored.contains("=") && stored.contains("; ")) {
            stored
        } else if (stored.contains("=")) {
            stored
        } else {
            // Legacy: bare value without key — assume it's the session cookie
            "ory_kratos_session=$stored"
        }
    }

    override fun hasNotStartedReading(status: Long): Boolean = status == PLAN_TO_READ

    override suspend fun delete(track: DomainTrack) {
        api.unfollowComic(track.remoteId)
    }

    override fun logout() {
        super.logout()
        interceptor.newAuth(null)
    }
}
