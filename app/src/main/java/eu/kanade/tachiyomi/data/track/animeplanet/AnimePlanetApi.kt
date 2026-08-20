package eu.kanade.tachiyomi.data.track.animeplanet

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import java.net.URLEncoder

class AnimePlanetApi(
    private val trackId: Long,
    private val interceptor: AnimePlanetInterceptor,
    private val client: OkHttpClient,
) {

    private val authClient by lazy {
        client.newBuilder()
            .addInterceptor(interceptor)
            .build()
    }

    // KMK --> login moved to AnimePlanetLoginActivity (webview-based cookie capture)
    // KMK <--

    suspend fun search(query: String): List<TrackSearch> = withIOContext {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/manga/all?name=$encodedQuery"

        val request = Request.Builder().url(url).build()
        val response = authClient.newCall(request).awaitSuccess()
        val document = response.asJsoup()

        document.select(".card").mapNotNull { card ->
            try {
                val link = card.selectFirst("a[href*=\"/manga/\"]") ?: return@mapNotNull null
                val href = link.attr("href")
                if (href.isBlank()) return@mapNotNull null

                val slug = href.substringAfterLast("/manga/").substringBefore("?")
                if (slug.isBlank()) return@mapNotNull null

                val title = link.text()
                    .removePrefix("Add to list ")
                    .ifBlank {
                        card.selectFirst("img")?.attr("alt") ?: ""
                    }

                val coverUrl = card.selectFirst("img")?.attr("src") ?: ""

                TrackSearch.create(trackId).apply {
                    remote_id = 0
                    this.title = title
                    cover_url = coverUrl
                    tracking_url = "$BASE_URL/manga/$slug"
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun getManga(slug: String): TrackSearch = withIOContext {
        val url = "$BASE_URL/manga/$slug"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).awaitSuccess()
        val document = response.asJsoup()

        val idScript = document.select("script").firstOrNull { script ->
            script.html().contains("AP_VARS") && script.html().contains("ENTRY_INFO")
        }

        val mangaId = idScript?.let { script ->
            val idMatch = Regex("""id:\s*["'](\d+)["']""").find(script.html())
            idMatch?.groupValues?.get(1)?.toLongOrNull()
        } ?: 0L

        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val coverUrl = document.selectFirst("img[src*=\"cdn.anime-planet.com\"]")?.attr("src") ?: ""
        val description = document.selectFirst("meta[name=\"description\"]")?.attr("content") ?: ""

        TrackSearch.create(trackId).apply {
            remote_id = mangaId
            this.title = title
            cover_url = coverUrl
            summary = description
            tracking_url = url
        }
    }

    suspend fun getMangaId(slug: String): Long = withIOContext {
        val url = "$BASE_URL/manga/$slug"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).awaitSuccess()
        val document = response.asJsoup()

        val idScript = document.select("script").firstOrNull { script ->
            script.html().contains("AP_VARS") && script.html().contains("ENTRY_INFO")
        }

        idScript?.let { script ->
            val idMatch = Regex("""id:\s*["'](\d+)["']""").find(script.html())
            idMatch?.groupValues?.get(1)?.toLongOrNull()
        } ?: 0L
    }

    suspend fun updateMangaListEntry(
        username: String,
        mangaId: Long,
        slug: String,
        status: Long,
        progress: Int,
        rating: Double,
    ): Boolean = withIOContext {
        val (token, apiStatus) = getTokenAndApiStatus(slug, status)

        val statusUrl = "$BASE_URL/api/list/status/manga/$mangaId/$apiStatus/$token"
        val statusRequest = Request.Builder()
            .url(statusUrl)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        val statusResponse = authClient.newCall(statusRequest).await()
        if (!statusResponse.isSuccessful) return@withIOContext false

        val updateUrl = "$BASE_URL/api/list/update/manga/$mangaId/$progress/0/$token"
        val updateRequest = Request.Builder()
            .url(updateUrl)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        authClient.newCall(updateRequest).await()

        true
    }

    suspend fun deleteMangaFromList(username: String, mangaId: Long, slug: String): Boolean = withIOContext {
        val token = getToken(slug) ?: return@withIOContext false

        val statusUrl = "$BASE_URL/api/list/status/manga/$mangaId/0/$token"
        val request = Request.Builder()
            .url(statusUrl)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        val response = authClient.newCall(request).await()
        response.isSuccessful
    }

    private suspend fun getToken(slug: String): String? = withIOContext {
        try {
            val url = "$BASE_URL/manga/$slug"
            val request = Request.Builder().url(url).build()
            val response = authClient.newCall(request).awaitSuccess()
            val document = response.asJsoup()

            document.select("script").firstOrNull { script ->
                script.html().contains("var TOKEN")
            }?.let { script ->
                val tokenMatch = Regex("""var TOKEN\s*=\s*['"]([^'"]+)['"]""").find(script.html())
                tokenMatch?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTokenAndApiStatus(slug: String, internalStatus: Long): Pair<String, Int> = withIOContext {
        val token = getToken(slug) ?: throw Exception("Could not extract token from $slug page")
        val apiStatus = internalStatusToApiStatus(internalStatus)
        Pair(token, apiStatus)
    }

    private fun internalStatusToApiStatus(status: Long): Int {
        return when (status) {
            AnimePlanet.READING -> 2
            AnimePlanet.COMPLETED -> 1
            AnimePlanet.ON_HOLD -> 5
            AnimePlanet.PLAN_TO_READ -> 4
            AnimePlanet.DROPPED -> 3
            AnimePlanet.REREADING -> 6
            else -> 4
        }
    }

    companion object {
        private const val BASE_URL = "https://www.anime-planet.com"
    }

    data class MangaListEntry(
        val mangaId: Long,
        val status: Long,
        val progress: Int,
        val rating: Double,
    )
}
