package eu.kanade.tachiyomi.data.track.animeplanet

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.interceptor.AbstractCookieTrackerInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

class AnimePlanetInterceptor(
    private val animePlanet: AnimePlanet,
) : AbstractCookieTrackerInterceptor(
    baseUrl = ANIME_PLANET_URL,
    getPersistedHeader = { animePlanet.restoreCookieHeader() },
    persistHeader = { animePlanet.saveCookieHeader(it) },
) {

    override fun intercept(chain: Interceptor.Chain): Response {
        val cookieHeader = cookieHeader()
        val authRequest = chain.request().newBuilder()
            .addHeader("Cookie", cookieHeader)
            .header("User-Agent", "Houri v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()
        return chain.proceed(authRequest)
    }

    companion object {
        private val ANIME_PLANET_URL = "https://www.anime-planet.com".toHttpUrl()
    }
}
