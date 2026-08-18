package eu.kanade.tachiyomi.data.track.comick

import eu.kanade.tachiyomi.BuildConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

class ComicKInterceptor(
    private val comicK: ComicK,
) : Interceptor {

    private var sessionCookie: String? = comicK.restoreSession()

    /**
     * Cookie jar that persists cookies from Comick API responses.
     * This is needed for CSRF tokens which are set by the server.
     */
    private val cookieStore = mutableMapOf<String, List<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val existing = cookieStore[url.host].orEmpty()
            cookieStore[url.host] = existing + cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host].orEmpty()
        }
    }

    fun getCookieJar(): CookieJar = cookieJar

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val csrfCookie = getCsrfToken()
        val session = sessionCookie

        if (session.isNullOrBlank()) {
            throw IOException("Not authenticated with ComicK")
        }

        val cookieHeader = buildString {
            append("ory_kratos_session=$session")
            if (csrfCookie != null) {
                append("; $csrfCookie")
            }
        }

        val authRequest = originalRequest.newBuilder()
            .header("Cookie", cookieHeader)
            .header("User-Agent", "Houri v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .header("Referer", "https://comick.dev/")
            .build()

        val response = chain.proceed(authRequest)

        // Save any new cookies from the response
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            val url = originalRequest.url
            val parsed = setCookies.mapNotNull { Cookie.parse(url, it) }
            if (parsed.isNotEmpty()) {
                val existing = cookieStore[url.host].orEmpty()
                cookieStore[url.host] = existing + parsed
            }
        }

        return response
    }

    private fun getCsrfToken(): String? {
        // CSRF cookie name contains a hash suffix like csrf_token_efd16ce7...
        val allCookies = cookieStore.values.flatten()
        val csrfCookie = allCookies.find { it.name.startsWith("csrf_token_") }
        return csrfCookie?.let { "${it.name}=${it.value}" }
    }

    fun newAuth(cookie: String?) {
        this.sessionCookie = cookie
    }
}
