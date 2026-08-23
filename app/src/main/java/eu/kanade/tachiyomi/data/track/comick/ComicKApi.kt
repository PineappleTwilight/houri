package eu.kanade.tachiyomi.data.track.comick

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

class ComicKApi(
    private val trackId: Long,
    private val client: OkHttpClient,
    private val interceptor: ComicKInterceptor,
) {

    private val json: Json by lazy { globalAppGraph.json }

    private val authClient by lazy {
        client.newBuilder()
            .addInterceptor(interceptor)
            .build()
    }

    /**
     * Search for comics by query.
     * GET https://api.comick.dev/v1.0/search?q={query}&limit=20&tachiyomi=true
     */
    suspend fun search(query: String): List<TrackSearch> = withIOContext {
        val url = "$BASE_URL/v1.0/search?q=$query&limit=20&tachiyomi=true"
        val request = Request.Builder().url(url)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).awaitSuccess()
        val data = with(json) { response.parseAs<List<ComicKSearchResult>>() }

        data.map { result ->
            TrackSearch.create(trackId).apply {
                remote_id = result.id
                title = result.title
                cover_url = result.md_covers?.firstOrNull()?.let { COVER_URL_PREFIX + it.b2key }.orEmpty()
                tracking_url = "$BASE_SITE_URL/comic/${result.slug}"
                summary = result.desc.orEmpty()
                publishing_status = when (result.status) {
                    1 -> "Ongoing"
                    2 -> "Completed"
                    3 -> "Cancelled"
                    4 -> "Hiatus"
                    else -> ""
                }
                publishing_type = result.media_type.orEmpty()
            }
        }
    }

    /**
     * Get comic details by HID.
     * GET https://api.comick.dev/v1.0/comic/{hid}?tachiyomi=true
     */
    suspend fun getMangaDetails(hid: String): TrackSearch? = withIOContext {
        try {
            val url = "$BASE_URL/v1.0/comic/$hid?tachiyomi=true"
            val request = Request.Builder().url(url)
                .addHeader("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).awaitSuccess()
            val data = with(json) { response.parseAs<ComicKComicResponse>() }

            TrackSearch.create(trackId).apply {
                remote_id = data.comic.id
                title = data.comic.title
                cover_url = data.comic.md_covers?.firstOrNull()?.let { COVER_URL_PREFIX + it.b2key }.orEmpty()
                tracking_url = "$BASE_SITE_URL/comic/${data.comic.slug}"
                summary = data.comic.desc.orEmpty()
                publishing_status = when (data.comic.status) {
                    1 -> "Ongoing"
                    2 -> "Completed"
                    3 -> "Cancelled"
                    4 -> "Hiatus"
                    else -> ""
                }
                publishing_type = data.comic.media_type.orEmpty()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Follow a comic / set reading status.
     * POST https://api.comick.dev/follow
     * Body: {"id": <numeric_id>, "t": <status_type>}
     *
     * Status types: 0=unfollow, 1=Reading, 2=Completed, 3=On-Hold, 4=Dropped, 5=Planning
     */
    suspend fun followComic(comicId: Long, statusType: Int): Boolean = withIOContext {
        try {
            val body = """{"id":$comicId,"t":$statusType}"""
            val requestBody = body.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$BASE_URL/follow")
                .post(requestBody)
                .addHeader("Accept", "*/*")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "https://comick.dev/")
                .build()

            val response = authClient.newCall(request).awaitSuccess()
            response.code in 200..299
        } catch (e: Exception) {
            // KMK -->
            logcat(LogPriority.WARN, e) { "ComicK follow update failed for comic $comicId" }
            // KMK <--
            false
        }
    }

    /**
     * Get the current follow status for a comic.
     * GET https://api.comick.dev/user/follow/comic/{id}
     * Returns the follow info if the user is following, 404 if not.
     */
    suspend fun getFollowStatus(comicId: Long): ComicKFollowResponse? = withIOContext {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/user/follow/comic/$comicId")
                .addHeader("Accept", "*/*")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "https://comick.dev/")
                .build()

            val response = authClient.newCall(request).awaitSuccess()
            with(json) { response.parseAs<ComicKFollowResponse>() }
        } catch (e: Exception) {
            if (e !is HttpException || e.code != 404) {
                // KMK -->
                // A 404 simply means the comic is not followed; anything else is a real failure
                logcat(LogPriority.WARN, e) { "ComicK follow status fetch failed for comic $comicId" }
                // KMK <--
            }
            null
        }
    }

    /**
     * Unfollow a comic.
     * POST https://api.comick.dev/follow
     * Body: {"id": <numeric_id>, "t": 0}
     */
    suspend fun unfollowComic(comicId: Long): Boolean = followComic(comicId, 0)

    companion object {
        const val BASE_URL = "https://api.comick.dev"
        const val BASE_SITE_URL = "https://comick.dev"
        private const val USER_AGENT = "Houri (https://github.com/PineappleTwilight/komikku-pineapple)"
        private const val COVER_URL_PREFIX = "https://meo.comick.pictures/"

        // ComicK follow status types
        const val TYPE_UNFOLLOW = 0
        const val TYPE_READING = 1
        const val TYPE_COMPLETED = 2
        const val TYPE_ON_HOLD = 3
        const val TYPE_DROPPED = 4
        const val TYPE_PLANNING = 5
    }

    // --- DTOs ---

    @Serializable
    data class ComicKSearchResult(
        val id: Long = 0,
        val hid: String = "",
        val slug: String = "",
        val title: String = "",
        val desc: String? = null,
        val status: Int = 0,
        val media_type: String? = null,
        val md_covers: List<ComicKCover>? = null,
    )

    @Serializable
    data class ComicKCover(
        val b2key: String = "",
    )

    @Serializable
    data class ComicKComicResponse(
        val comic: ComicKComic,
    )

    @Serializable
    data class ComicKComic(
        val id: Long = 0,
        val hid: String = "",
        val slug: String = "",
        val title: String = "",
        val desc: String? = null,
        val status: Int = 0,
        val media_type: String? = null,
        val md_covers: List<ComicKCover>? = null,
    )

    @Serializable
    data class ComicKFollowResponse(
        val id: Long = 0,
        val comic_id: Long = 0,
        val user_id: String = "",
        val t: Int = 0,
    )
}
