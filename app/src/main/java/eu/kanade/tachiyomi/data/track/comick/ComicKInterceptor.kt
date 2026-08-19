package eu.kanade.tachiyomi.data.track.comick

import eu.kanade.tachiyomi.BuildConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class ComicKInterceptor(
    private val comicK: ComicK,
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
        }
    }

    fun getCookieJar(): CookieJar = cookieJar

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val cookies = cookieJar.loadForRequest(originalRequest.url)
        val hasSession = cookies.any { it.name == "ory_kratos_session" }
        if (!hasSession) {
            throw IOException("Not authenticated with ComicK")
        }

        val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }

        val authRequest = originalRequest.newBuilder()
            .header("Cookie", cookieHeader)
            .header("User-Agent", "Houri v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .header("Referer", "https://comick.dev/")
            .build()

        return chain.proceed(authRequest)
    }

    /**
     * Seed the cookie store from the raw cookie header string captured
     * by [ComicKLoginActivity].
     */
    fun restoreFromCookieHeader(cookieHeader: String?) {
        if (cookieHeader.isNullOrBlank()) return
        val url = COMICK_URL
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

    fun newAuth(cookie: String?) {
        cookieStore.clear()
        if (!cookie.isNullOrBlank()) {
            // The login captures the ory_kratos_session value; wrap as a proper cookie
            restoreFromCookieHeader("ory_kratos_session=$cookie")
            persistCookies()
        }
    }

    private fun persistCookies() {
        val header = cookieStore.values.flatten()
            .joinToString("; ") { "${it.name}=${it.value}" }
        comicK.saveCookieHeader(header)
    }

    init {
        // Restore persisted cookies on construction
        val persisted = comicK.restoreCookieHeader()
        if (!persisted.isNullOrBlank()) {
            restoreFromCookieHeader(persisted)
        }
    }

    companion object {
        private val COMICK_URL = "https://comick.dev".toHttpUrl()
    }
}
