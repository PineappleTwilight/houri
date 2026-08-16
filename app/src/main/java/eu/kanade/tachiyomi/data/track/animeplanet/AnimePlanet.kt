package eu.kanade.tachiyomi.data.track.animeplanet

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

class AnimePlanet(id: Long) : BaseTracker(id, "AnimePlanet"), DeletableTracker {

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val PLAN_TO_READ = 4L
        const val DROPPED = 5L
        const val REREADING = 6L

        // KMK -->
        private const val ANON_USERNAME = "animeplanet-user"
        // KMK <--

        private val SCORE_LIST = (0..10)
            .map { if (it == 0) "-" else it.toString() }
            .toImmutableList()
    }

    private val interceptor by lazy { AnimePlanetInterceptor(this) }

    private val api by lazy { AnimePlanetApi(id, interceptor, client) }

    override fun getLogo(): Int = R.drawable.brand_animeplanet

    override fun getStatusList(): List<Long> {
        return listOf(READING, COMPLETED, ON_HOLD, PLAN_TO_READ, DROPPED, REREADING)
    }

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        PLAN_TO_READ -> MR.strings.plan_to_read
        DROPPED -> MR.strings.dropped
        REREADING -> MR.strings.repeating
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = REREADING

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> = SCORE_LIST

    override fun indexToScore(index: Int): Double = index.toDouble()

    override fun displayScore(track: DomainTrack): String = track.score.toInt().toString()

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (track.status != COMPLETED && didReadChapter) {
            track.status = READING
        }
        val slug = extractSlugFromUrl(track.tracking_url)
        if (slug != null) {
            api.updateMangaListEntry(
                getUsername(),
                track.remote_id,
                slug,
                track.status,
                track.last_chapter_read.toInt(),
                track.score,
            )
        }
        return track
    }

    override suspend fun delete(track: DomainTrack) {
        val slug = extractSlugFromUrl(track.remoteUrl)
        if (slug != null) {
            api.deleteMangaFromList(getUsername(), track.remoteId, slug)
        }
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        if (track.remote_id == 0L && track.tracking_url.isNotBlank()) {
            val slug = extractSlugFromUrl(track.tracking_url)
            if (slug != null) {
                track.remote_id = api.getMangaId(slug)
            }
        }

        val status = if (hasReadChapters) READING else PLAN_TO_READ
        track.status = status
        return track
    }

    override suspend fun search(query: String): List<TrackSearch> {
        return api.search(query)
    }

    override suspend fun refresh(track: Track): Track {
        val slug = extractSlugFromUrl(track.tracking_url)
        if (slug != null) {
            val manga = api.getManga(slug)
            track.remote_id = manga.remote_id
            track.title = manga.title
        }
        return track
    }

    override suspend fun login(username: String, password: String) = loginWithCookie(password, username)

    // KMK -->
    suspend fun loginWithCookie(sessionCookie: String, username: String = "") {
        saveCredentials(username.ifBlank { ANON_USERNAME }, sessionCookie)
        interceptor.newAuth(sessionCookie)
    }
    // KMK <--

    fun restoreSession(): String? {
        return trackPreferences.trackPassword(this).get().ifBlank { null }
    }

    private fun extractSlugFromUrl(url: String): String? {
        return Regex("""/manga/([^/?]+)""").find(url)?.groupValues?.get(1)
    }

    // KMK -->
    override fun hasNotStartedReading(status: Long): Boolean = status == PLAN_TO_READ
    // KMK <--
}
