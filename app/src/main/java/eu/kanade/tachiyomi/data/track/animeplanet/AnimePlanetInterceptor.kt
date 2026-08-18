package eu.kanade.tachiyomi.data.track.animeplanet

import eu.kanade.tachiyomi.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class AnimePlanetInterceptor(
    private val animePlanet: AnimePlanet,
) : Interceptor {

    /**
     * The full cookie header string to send with requests.
     * This includes all cookies captured from the WebView session:
     * - "session" (auth)
     * - "ap" (auth)
     * - "REMEMBER ME" (auth)
     * - "cf_*" (Cloudflare)
     * - "xf_user", "xf_session" (XenForo)
     */
    private var cookieHeader: String? = animePlanet.restoreCookieHeader()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val cookies = cookieHeader ?: throw IOException("Not authenticated with AnimePlanet")

        val authRequest = originalRequest.newBuilder()
            .addHeader("Cookie", cookies)
            .header("User-Agent", "Houri v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(cookie: String?) {
        this.cookieHeader = cookie
    }
}
