package eu.kanade.tachiyomi.data.track.animeplanet

import eu.kanade.tachiyomi.BuildConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class AnimePlanetInterceptor(
    private val animePlanet: AnimePlanet,
) : Interceptor {

    /**
     * Persistent cookie store. Cookies are serialised to/from the tracker
     * preference string so they survive process death.
     */
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val existing = cookieStore[url.host].orEmpty().toMutableList()
            // Replace cookies with the same name
            val newNames = cookies.map { it.name }.toSet()
            val filtered = existing.filter { it.name !in newNames }.toMutableList()
            filtered.addAll(cookies)
            cookieStore[url.host] = filtered
            persistCookies()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host].orEmpty()
                .filter { !it.hasExpired() }
        }
    }

    fun getCookieJar(): CookieJar = cookieJar

    /**
     * Seed the cookie store from the raw cookie header string captured
     * by [AnimePlanetLoginActivity].
     */
    fun restoreFromCookieHeader(cookieHeader: String?) {
        if (cookieHeader.isNullOrBlank()) return
        val url = ANIME_PLANET_URL
        cookieHeader.split("; ")
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isNotEmpty() && trimmed.contains("=")) {
                    val parts = trimmed.split("=", limit = 2)
                    Cookie.Builder()
                        .domain(url.host)
                        .path("/")
                        .name(parts[0])
                        .value(parts[1])
                        .build()
                } else {
                    null
                }
            }
            .let { cookies ->
                if (cookies.isNotEmpty()) {
                    cookieStore[url.host] = cookies.toMutableList()
                }
            }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val cookies = cookieJar.loadForRequest(originalRequest.url)
        if (cookies.isEmpty()) {
            throw IOException("Not authenticated with AnimePlanet")
        }

        val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }

        val authRequest = originalRequest.newBuilder()
            .addHeader("Cookie", cookieHeader)
            .header("User-Agent", "Houri v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(cookieHeader: String?) {
        cookieStore.clear()
        if (!cookieHeader.isNullOrBlank()) {
            restoreFromCookieHeader(cookieHeader)
            persistCookies()
        }
    }

    private fun persistCookies() {
        val header = cookieStore.values.flatten()
            .joinToString("; ") { "${it.name}=${it.value}" }
        animePlanet.saveCookieHeader(header)
    }

    private fun Cookie.hasExpired(): Boolean {
        val expiry = expiresAt
        return expiry != 0L && System.currentTimeMillis() > expiry
    }

    init {
        // Restore persisted cookies on construction
        val persisted = animePlanet.restoreCookieHeader()
        if (!persisted.isNullOrBlank()) {
            restoreFromCookieHeader(persisted)
        }
    }

    companion object {
        private val ANIME_PLANET_URL = "https://www.anime-planet.com".toHttpUrl()
    }
}
