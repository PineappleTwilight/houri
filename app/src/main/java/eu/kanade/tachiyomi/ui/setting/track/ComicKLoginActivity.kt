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
import eu.kanade.presentation.webview.ComicKLoginWebViewScreen
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

class ComicKLoginActivity : BaseActivity() {

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
            ComicKLoginWebViewScreen(
                onUp = { finish() },
                onPageFinished = ::onPageFinished,
            )
        }
    }

    private fun onPageFinished(view: WebView, url: String) {
        val parsedUrl = url.toUri()
        val isComick = parsedUrl.host.equals("comick.dev", ignoreCase = true)
        if (!isComick) return

        // Check if we have the session cookie after login
        val sessionCookie = getSessionCookie() ?: return

        // Extract username from user page or use a default
        val username = if (parsedUrl.path?.startsWith("/user/") == true) {
            parsedUrl.lastPathSegment.orEmpty()
        } else {
            // Navigate to user page to get username
            if (!navigatedToProfile) {
                navigatedToProfile = true
                view.loadUrl("https://comick.dev/user")
            }
            return
        }

        if (username.isNotBlank()) {
            loginAndFinish(sessionCookie, username)
        }
    }

    private var navigatedToProfile = false

    private fun hasSessionCookie(): Boolean {
        val cookies = CookieManager.getInstance().getCookie("https://comick.dev") ?: return false
        return cookies.split("; ")
            .mapNotNull { HttpCookie.parse(it).firstOrNull() }
            .firstOrNull { it.name.equals("ory_kratos_session", ignoreCase = true) }
            ?.value
            ?.isNotBlank() == true
    }

    private fun loginAndFinish(sessionCookie: String, username: String) {
        lifecycleScope.launchIO {
            try {
                trackerManager.comicK.loginWithCookie(sessionCookie, username)
                setResult(RESULT_OK)
            } catch (e: Throwable) {
                toast(e.message.toString())
            } finally {
                finish()
            }
        }
    }

    private fun getSessionCookie(): String? {
        val cookies = CookieManager.getInstance().getCookie("https://comick.dev") ?: return null

        // Parse all cookies from the cookie string
        val parsedCookies = cookies.split("; ")
            .mapNotNull { HttpCookie.parse(it).firstOrNull() }

        // Get the ory_kratos_session cookie value
        val session = parsedCookies
            .firstOrNull { it.name.equals("ory_kratos_session", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }

        return session
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
            return Intent(context, ComicKLoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}
