package eu.kanade.presentation.webview

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebView
import com.kevinnzou.web.rememberWebViewState
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import tachiyomi.presentation.core.components.material.Scaffold

@Composable
fun ComicKLoginWebViewScreen(
    onUp: () -> Unit,
    onPageFinished: (view: WebView, url: String) -> Unit,
) {
    val state = rememberWebViewState(url = "https://comick.dev")
    val loading by produceState(true) {
        CookieManager.getInstance().removeAllCookies { value = false }
    }

    Scaffold(
        topBar = {
            Box {
                AppBar(
                    title = "ComicK login",
                    navigateUp = onUp,
                    navigationIcon = Icons.Outlined.Close,
                )
                when (val loadingState = state.loadingState) {
                    is LoadingState.Initializing -> LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    is LoadingState.Loading -> {
                        val animatedProgress by animateFloatAsState(
                            loadingState.progress,
                            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                            label = "webview_loading",
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                        )
                    }
                    else -> {}
                }
            }
        },
    ) { contentPadding ->
        if (loading) return@Scaffold

        val webClient = remember {
            object : AccompanistWebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    onPageFinished(view, url ?: return)
                }
            }
        }

        Box(Modifier.padding(contentPadding)) {
            WebView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                onCreated = { webView -> webView.setDefaultSettings() },
                client = webClient,
            )
        }
    }
}
