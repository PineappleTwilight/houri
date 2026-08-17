package eu.kanade.tachiyomi.ui.setting.track

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import eu.kanade.presentation.webview.AnimePlanetLoginWebViewScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.net.HttpCookie

class AnimePlanetLoginActivity : BaseActivity() {

    private val trackerManager: TrackerManager by injectLazy()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }
        super.onCreate(savedInstanceState)

        if (!WebViewUtil.supportsWebView(this)) {
            toast(MR.strings.information_webview_required, Toast.LENGTH_LONG)
            finish()
            return
        }

        setComposeContent {
            AnimePlanetLoginWebViewScreen(
                onUp = { finish() },
                onPageFinished = ::onPageFinished,
            )
        }
    }

    private fun onPageFinished(view: WebView, url: String) {
        val parsedUrl = url.toUri()
        val isAnimePlanet = parsedUrl.host.equals("www.anime-planet.com", ignoreCase = true)
        if (!isAnimePlanet) return

        val isUserProfile = parsedUrl.path?.startsWith("/users/") == true
        if (isUserProfile) {
            val sessionCookie = getSessionCookie() ?: return
            val username = parsedUrl.lastPathSegment.orEmpty()
            if (username.isNotBlank()) {
                loginAndFinish(sessionCookie, username)
            }
            return
        }

        // KMK -->
        // After login, AnimePlanet redirects to the homepage ("/") instead of
        // the user's profile. Detect the session cookie and navigate to the
        // user's profile page to extract the username.
        if (hasSessionCookie() && !navigatedToProfile) {
            navigatedToProfile = true
            view.loadUrl("https://www.anime-planet.com/users/")
        }
        // KMK <--
    }

    // KMK -->
    private var navigatedToProfile = false

    private fun hasSessionCookie(): Boolean {
        val cookies = CookieManager.getInstance().getCookie("https://www.anime-planet.com") ?: return false
        return cookies.split("; ")
            .mapNotNull { HttpCookie.parse(it).firstOrNull() }
            .firstOrNull { it.name.equals("session", ignoreCase = true) }
            ?.value
            ?.isNotBlank() == true
    }

    private fun loginAndFinish(sessionCookie: String, username: String) {
        lifecycleScope.launchIO {
            try {
                trackerManager.animePlanet.loginWithCookie(sessionCookie, username)
                setResult(RESULT_OK)
            } catch (e: Throwable) {
                toast(e.message.toString())
            } finally {
                finish()
            }
        }
    }
    // KMK <--

    private fun getSessionCookie(): String? {
        val cookies = CookieManager.getInstance().getCookie("https://www.anime-planet.com") ?: return null
        return cookies.split("; ")
            .mapNotNull { HttpCookie.parse(it).firstOrNull() }
            .firstOrNull { it.name.equals("session", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    init {
        registerSecureActivity(this)
    }

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, AnimePlanetLoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}
