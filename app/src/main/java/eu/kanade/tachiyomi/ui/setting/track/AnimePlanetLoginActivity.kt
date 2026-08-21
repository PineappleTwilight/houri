package eu.kanade.tachiyomi.ui.setting.track

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import eu.kanade.presentation.webview.AnimePlanetLoginWebViewScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import mihon.app.di.appGraph
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR

class AnimePlanetLoginActivity : BaseActivity() {

    private val trackerManager: TrackerManager by lazy { appGraph.trackerManager }

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

    // KMK -->
    private fun onPageFinished(view: WebView, url: String) {
        // After login, AnimePlanet may redirect through CF challenge pages,
        // error pages, or other domains before setting the session cookie.
        // Just check for the session cookie on every page load — regardless
        // of what domain we're on. Once we have it, grab cookies and finish.
        if (!loginAttempted && hasSessionCookie()) {
            loginAttempted = true
            val cookieHeader = getAllCookies() ?: return
            loginAndFinish(cookieHeader)
        }
    }

    // KMK -->
    private var loginAttempted = false

    private fun hasSessionCookie(): Boolean {
        val cookies = CookieManager.getInstance().getCookie("https://www.anime-planet.com") ?: return false
        val lower = cookies.lowercase()
        return lower.contains("ap=") || lower.contains("rememberme=")
    }

    private fun loginAndFinish(cookieHeader: String) {
        lifecycleScope.launchIO {
            try {
                trackerManager.animePlanet.loginWithCookie(cookieHeader)
                setResult(RESULT_OK)
            } catch (e: Throwable) {
                toast(e.message.toString())
            } finally {
                finish()
            }
        }
    }
    // KMK <--

    // KMK -->
    /**
     * Capture all cookies from the WebView CookieManager for anime-planet.com.
     * Returns them as a raw cookie header string (name=value; name2=value2).
     *
     * Important cookies captured:
     * - "session" — primary auth cookie
     * - "ap" — auth cookie
     * - "REMEMBER ME" — persistent login cookie
     * - "cf_*" — Cloudflare challenge cookies
     * - "xf_user", "xf_session" — XenForo forum cookies
     */
    private fun getAllCookies(): String? {
        val rawCookies = CookieManager.getInstance().getCookie("https://www.anime-planet.com") ?: return null
        // CookieManager returns cookies in "name=value; name2=value2" format already
        // But we need to parse and re-emit to ensure we get a valid header string
        val parsed = rawCookies.split("; ")
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isNotEmpty() && trimmed.contains("=")) trimmed else null
            }
            .joinToString("; ")

        return parsed.ifBlank { null }
    }
    // KMK <--

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
