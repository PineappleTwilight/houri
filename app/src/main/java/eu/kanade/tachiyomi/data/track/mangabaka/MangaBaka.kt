package eu.kanade.tachiyomi.data.track.mangabaka

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.core.TrackerLoginMode
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaOAuth
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.SequelPrequelEntry
import tachiyomi.domain.manga.model.SequelPrequelRelation
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

class MangaBaka(id: Long) : BaseTracker(id, "MangaBaka"), DeletableTracker {

    private val json: Json by lazy { globalAppGraph.json }

    private val interceptor by lazy { MangaBakaInterceptor(this) }
    private val api by lazy { MangaBakaApi(id, client, interceptor) }

    override val supportsReadingDates: Boolean = true
    override val supportsPrivateTracking: Boolean = true

    private val scorePreference = trackPreferences.mangabakaScoreType()

    override fun getLogo(): Int = R.drawable.brand_mangabaka

    override fun getStatusList(): List<Long> {
        return listOf(READING, COMPLETED, PAUSED, DROPPED, PLAN_TO_READ, REREADING, CONSIDERING)
    }

    override fun getStatus(status: Long): StringResource? = when (status) {
        CONSIDERING -> MR.strings.considering
        COMPLETED -> MR.strings.completed
        DROPPED -> MR.strings.dropped
        PAUSED -> MR.strings.paused
        PLAN_TO_READ -> MR.strings.plan_to_read
        READING -> MR.strings.reading
        REREADING -> MR.strings.repeating
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = REREADING

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> = getScoreRange().map(Int::toString).toImmutableList()

    override fun get10PointScore(track: DomainTrack): Double = normalizeScore(track.score, 100.0)

    override fun indexToScore(index: Int): Double {
        val range = getScoreRange().toList()
        // KMK --> defensive: score wheel / universal sync can pass ends; clamp instead of throwing
        if (range.isEmpty()) return 0.0
        return range[index.coerceIn(range.indices)].toDouble()
    }

    override fun displayScore(track: DomainTrack): String {
        // KMK --> fractional remote ratings (e.g. 85.5) and values off the user's step
        // grid have no exact wheel entry; snap to the nearest valid step so the
        // selector round-trips instead of crashing on indexOf(-1).
        val steps = getScoreRange().toList()
        if (steps.isEmpty()) return "0"
        return steps.minByOrNull { kotlin.math.abs(it - track.score.toInt()) }?.toString() ?: "0"
    }

    override fun hasNotStartedReading(status: Long): Boolean = status == PLAN_TO_READ

    override fun getLoginMode(): TrackerLoginMode = TrackerLoginMode.OAUTH

    override fun getAuthUrl(): String? = MangaBakaApi.authUrl().toString()

    override suspend fun update(
        track: Track,
        didReadChapter: Boolean,
    ): Track {
        if (track.status != COMPLETED && didReadChapter) {
            if (
                track.total_chapters > 0 &&
                track.last_chapter_read.toLong() == track.total_chapters &&
                isSeriesFinished(track)
            ) {
                track.status = COMPLETED
                track.finished_reading_date = System.currentTimeMillis()
            } else if (track.status != REREADING) {
                track.status = READING
                if (track.last_chapter_read == 1.0) {
                    track.started_reading_date = System.currentTimeMillis()
                }
            }
        }

        return api.updateLibManga(track)
    }

    /**
     * Whether the series itself is finished, as MangaBaka reports it.
     *
     * ### Why catching up is not the same as finishing
     *
     * The rule above fires on `last_chapter_read == total_chapters`, and for a series that is still
     * running that equality is temporary: the chapter count grows with every release, so the reader
     * hits it again a few weeks later. Each time they did, the entry was flipped to Completed — and
     * the moment it was next touched, `update()`'s own `status != COMPLETED` guard stopped it ever
     * going back to Reading. A reader keeping up with a monthly series therefore ended up with a wall
     * of wrongly-completed entries they had to fix by hand.
     *
     * So the count is now necessary but not sufficient, and MangaBaka's own publishing status decides.
     * `releasing`, `hiatus`, `upcoming` and `cancelled` all mean there is more to come, or that the
     * author intends to come back to it; only `completed` means the count is the end.
     *
     * Fetched here rather than kept on the track because that is the only moment it can change the
     * answer: `didReadChapter` and the count are already checked before this runs, so a reader who is
     * behind never pays for the request. The domain `Track` carries no publishing status — it is one
     * model for fourteen services, and a MangaBaka-only column on it would be that service's business
     * in everyone else's table — and it changes server-side between sessions, so caching it would make
     * the rule wrong in the other direction.
     *
     * **Any failure answers false.** An unreachable API or an unparseable response must not be able to
     * mark a series finished; the cost of being wrong that way is a status the reader set by hand being
     * overwritten, while the cost of the other way is one missed auto-completion they can also do by
     * hand.
     */
    private suspend fun isSeriesFinished(track: Track): Boolean {
        // `remote_id` is already the series id `getSeriesItem` takes — there is nothing to parse, and
        // a row that never synced carries 0 rather than a string that would need reading.
        val remoteId = track.remote_id
        if (remoteId <= 0L) return false
        val status = runCatching { api.getSeriesItem(remoteId)?.status }.getOrNull()
        return status?.trim()?.equals(FINISHED_STATUS, ignoreCase = true) == true
    }

    // KMK --> The refresh path: an ongoing series' live total is "released so far", so the
    // caught-up check must ask the series' publishing status, not just compare counts.
    override suspend fun shouldAutoCompleteOnCatchUp(track: Track): Boolean = isSeriesFinished(track)
    // KMK <--

    override suspend fun bind(
        track: Track,
        hasReadChapters: Boolean,
    ): Track {
        val remoteTrack = api.findLibManga(track) ?: run {
            track.status = if (hasReadChapters) READING else PLAN_TO_READ
            track.score = 0.0

            try {
                return api.addLibManga(track)
            } catch (e: HttpException) {
                // KMK --> 409 means the entry already exists server-side (added on the
                // website, or a lost find response); adopt it instead of crashing.
                if (e.code != 409) throw e
                api.findLibManga(track) ?: throw e
            }
        }
        track.copyPersonalFrom(remoteTrack, copyRemotePrivate = false)
        track.title = remoteTrack.title
        track.remote_id = remoteTrack.remote_id

        if (track.status != COMPLETED) {
            val isRereading = track.status == REREADING
            track.status = if (!isRereading && hasReadChapters) READING else track.status
        }

        return update(track)
    }

    override suspend fun search(query: String): List<TrackSearch> {
        if (query.startsWith(SEARCH_ID_PREFIX)) {
            query.substringAfter(SEARCH_ID_PREFIX).toIntOrNull()?.let { id ->
                return api.getMangaDetails(id)?.let { listOf(it) } ?: emptyList()
            }
        }

        return api.search(query)
    }

    override suspend fun refresh(track: Track): Track {
        val remoteTrack = api.findLibManga(track) ?: throw Exception("Could not find manga")
        track.copyPersonalFrom(remoteTrack)
        track.remote_id = remoteTrack.remote_id
        track.title = remoteTrack.title
        // KMK --> refresh is also how a series' recorded chapter count reaches an entry that was
        // bound before the field was read: without it the row never learns the total, and
        // update()'s completion rule — "progress equals the last chapter" — can never fire.
        // Same line every sibling tracker carries (AniList, Kitsu, Hikka, …).
        track.total_chapters = remoteTrack.total_chapters
        // KMK <--
        return track
    }

    override suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        val remote = api.getMangaDetails(track.remoteId.toInt()) ?: throw Exception("Could not find manga")
        // KMK -->
        return remote.toMangaMetadata(track.remoteId)
        // KMK <--
    }

    // KMK --> relations come from the series-detail payload; every known
    // kind resolves and is labeled in the row (per-type toggles not coded yet).
    // Titles need one detail fetch per related id (usually 1-2).
    //
    // `null` and `emptyList()` mean different things here, and the sequel/prequel provider acts
    // on the difference: null is "this service could not tell", emptyList is "this service
    // answered and the series has no relations". They used to be the same thing - the mapping
    // ended in `entries.ifEmpty { null }` - so a series MangaBaka reports as unlinked was treated
    // as unanswerable, and the provider moved on to another tracker (or the MangaDex fallback)
    // and showed *its* relations under the MangaBaka-bound manga. That is how a title MangaBaka
    // lists with no relations at all could show a prequel or a source that has nothing to do
    // with it. Only a failed fetch returns null now.
    override suspend fun getRelatedEntries(remoteId: Long): List<SequelPrequelEntry>? {
        val item = try {
            api.getSeriesItem(remoteId)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "MangaBaka relations lookup failed for '$remoteId'" }
            return null
        } ?: return null
        val entries = item.relationshipsV2.orEmpty().mapNotNull { rel ->
            val relation = SequelPrequelRelation.fromMangaBaka(rel.relationType)
                ?: return@mapNotNull null
            // A series related to itself would render as a card that opens the page it was
            // already on. MangaBaka does not emit one, but the payload is user-editable
            // (`is_manual` on each relationship), so the guard is cheap insurance.
            if (rel.toSeriesId == remoteId) return@mapNotNull null
            val details = try {
                api.getMangaDetails(rel.toSeriesId.toInt())
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "MangaBaka related title lookup failed for '${rel.toSeriesId}'" }
                null
            }
            val title = details?.title?.ifBlank { null } ?: return@mapNotNull null
            SequelPrequelEntry(
                title = title,
                url = details.tracking_url,
                relation = relation,
                trackerId = id,
                // KMK --> details payload already carries the cover; no extra fetch
                coverUrl = details.cover_url.ifBlank { null },
                // KMK <--
                // KMK --> web novels are unreadable in the reader; flag them so
                // the click handler routes them to the browser instead.
                isWebNovel = details.publishing_type.equals("novel", ignoreCase = true),
                // KMK <--
            )
        }
        return entries
    }

    override fun parseRelatedEntryId(url: String): Long? {
        // tracking_url is "$BASE_URL/<series id>", e.g. https://mangabaka.org/12345. The host is
        // part of the test rather than a nicety: the provider hands this any manga url it is
        // looking at, and "take the text after the last slash, read it as a number" also matches
        // a foreign url that happens to end in digits - which then had that unrelated series'
        // relations rendered under this manga.
        val prefix = MangaBakaApi.BASE_URL
        if (!url.startsWith("$prefix/", ignoreCase = true)) return null
        return url.removePrefix(prefix).trim('/').substringBefore('/').toLongOrNull()
    }
    // KMK <--

    override suspend fun login(username: String, password: String) = login(password)

    suspend fun login(code: String) {
        try {
            val oauth = api.getAccessToken(code)
            interceptor.setAuth(oauth)
            val currentUser = api.getCurrentUser()
            val scoreType = when (currentUser.ratingSteps) {
                1 -> STEP_1
                5 -> STEP_5
                10 -> STEP_10
                20 -> STEP_20
                25 -> STEP_25
                else -> {
                    logcat(LogPriority.WARN) { "Unknown MangaBaka score step size ${currentUser.ratingSteps}, using $STEP_10" }
                    STEP_10
                }
            }
            scorePreference.set(scoreType)
            saveCredentials(currentUser.nickname ?: currentUser.preferredUsername ?: currentUser.id, oauth.accessToken)
            try {
                mihon.app.di.globalAppGraph.preferenceStore.getString("mangabaka_code_verifier", "").delete()
            } catch (_: Exception) {}
            try {
                mihon.app.di.globalAppGraph.preferenceStore.getString("mangabaka_oauth_state", "").delete()
            } catch (_: Exception) {}
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "MangaBaka OAuth failed" }
            logout()
            throw e
        }
    }

    fun saveToken(oauth: MangaBakaOAuth?) {
        trackPreferences.trackToken(this).set(json.encodeToString(oauth))
    }

    fun restoreToken(): MangaBakaOAuth? {
        return try {
            json.decodeFromString(trackPreferences.trackToken(this).get())
        } catch (_: Exception) {
            null
        }
    }

    fun verifyOAuthState(state: String): Boolean = api.verifyOAuthState(state)

    override fun logout() {
        super.logout()
        trackPreferences.trackToken(this).delete()
        interceptor.setAuth(null)
    }

    override suspend fun delete(track: DomainTrack) {
        api.deleteLibManga(track)
    }

    private fun getScoreRange(): IntProgression = when (scorePreference.get()) {
        STEP_1 -> STEP_1_SCORES
        STEP_5 -> STEP_5_SCORES
        STEP_10 -> STEP_10_SCORES
        STEP_20 -> STEP_20_SCORES
        STEP_25 -> STEP_25_SCORES
        // KMK --> unknown/stale stored value (e.g. from older builds) falls back to STEP_10
        // instead of throwing and breaking the score dialog
        else -> STEP_10_SCORES
    }

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val PAUSED = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 5L
        const val REREADING = 6L
        const val CONSIDERING = 7L

        const val STEP_1 = "STEP_1"
        const val STEP_5 = "STEP_5"
        const val STEP_10 = "STEP_10"
        const val STEP_20 = "STEP_20"
        const val STEP_25 = "STEP_25"

        private val STEP_1_SCORES = IntRange(0, 100)
        private val STEP_5_SCORES = IntRange(0, 100).step(5)
        private val STEP_10_SCORES = IntRange(0, 100).step(10)
        private val STEP_20_SCORES = IntRange(0, 100).step(20)
        private val STEP_25_SCORES = IntRange(0, 100).step(25)

        private const val SEARCH_ID_PREFIX = "id:"

        /**
         * The only publishing status that means the chapter count is the end of the series.
         *
         * Deliberately an allow-list rather than a "not still releasing" check: `upcoming`, `hiatus`
         * and `cancelled` are all states a series can leave the count open in, and a status this
         * client has not seen before should not silently count as finished. See [isSeriesFinished].
         */
        private const val FINISHED_STATUS = "completed"
    }
}
