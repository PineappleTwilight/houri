package eu.kanade.tachiyomi.data.track.animeplanet

import eu.kanade.tachiyomi.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class AnimePlanetInterceptor(
    private val animePlanet: AnimePlanet,
) : Interceptor {

    private var sessionCookie: String? = animePlanet.restoreSession()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val cookie = sessionCookie ?: throw IOException("Not authenticated with AnimePlanet")

        val authRequest = originalRequest.newBuilder()
            .addHeader("Cookie", "session=$cookie")
            .header("User-Agent", "Houri v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(cookie: String?) {
        this.sessionCookie = cookie
    }
}
