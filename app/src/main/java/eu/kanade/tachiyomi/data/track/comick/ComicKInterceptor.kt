package eu.kanade.tachiyomi.data.track.comick

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.interceptor.AbstractCookieTrackerInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class ComicKInterceptor(
    private val comicK: ComicK,
) : AbstractCookieTrackerInterceptor(
    baseUrl = COMICK_URL,
    getPersistedHeader = { comicK.restoreCookieHeader() },
    persistHeader = { comicK.saveCookieHeader(it) },
) {

    override fun intercept(chain: Interceptor.Chain): Response {
        val header = cookieHeader()
        if (!header.contains("ory_kratos_session=")) {
            throw IOException("Not authenticated with ComicK")
        }
        val authRequest = chain.request().newBuilder()
            .header("Cookie", header)
            .header("User-Agent", "Houri v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .header("Referer", "https://comick.dev/")
            .build()
        return chain.proceed(authRequest)
    }

    override fun normalizeCookieHeader(raw: String): String {
        val trimmed = raw.trim()
        // Legacy bare value without "=" — wrap as session cookie
        return if (!trimmed.contains("=")) "ory_kratos_session=$trimmed" else trimmed
    }

    companion object {
        private val COMICK_URL = "https://comick.dev".toHttpUrl()
    }
}
