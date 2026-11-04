package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.updaterEnabled
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.navigation.NewUpdateRoute
import mihon.core.navigation.OpenSourceLicensesRoute
import mihon.core.navigation.RoadmapRoute
import mihon.core.navigation.util.LocalBackButtonVisibility
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LinkIcon
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.Discord
import tachiyomi.presentation.core.icons.Github
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
fun AboutScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val backVisibility = LocalBackButtonVisibility.current
    val backStack = LocalBackStack.current
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var versionTapTimes by remember { mutableStateOf(listOf<Long>()) }
    val versionTitle = stringResource(MR.strings.version)

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.pref_category_about),
                navigateUp = {
                    backStack.removeLastOrNull()
                    Unit
                }.takeIf { backVisibility },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        ScrollbarLazyColumn(
            contentPadding = contentPadding,
        ) {
            item {
                LogoHeader()
            }

            item {
                TextPreferenceWidget(
                    title = versionTitle,
                    subtitle = getVersionName(withBuildDate = true),
                    onPreferenceClick = {
                        context.copyToClipboard(
                            versionTitle,
                            getVersionName(withBuildDate = true),
                        )
                        val now = System.currentTimeMillis()
                        val pruned = (versionTapTimes.filter { now - it < 60_000 } + now)
                        versionTapTimes = pruned
                        if (eu.kanade.presentation.more.settings.RamGateState.isDisabled.value) {
                            context.toast("RAM gate already disabled")
                        } else if (pruned.size >= 10) {
                            // Routed through RamGateState so the RAM-gated settings rows
                            // recompose immediately; a bare SharedPreferences write left
                            // them on their pre-toggle verdict.
                            eu.kanade.presentation.more.settings.RamGateState.setDisabled(context, true)
                            context.toast("RAM gate disabled – all features unlocked")
                        }
                    },
                )
            }

            if (updaterEnabled) {
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.check_for_updates),
                        widget = {
                            AnimatedVisibility(visible = isCheckingUpdates) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp,
                                )
                            }
                        },
                        onPreferenceClick = {
                            if (!isCheckingUpdates) {
                                scope.launch {
                                    isCheckingUpdates = true

                                    checkVersion(
                                        context = context,
                                        onAvailableUpdate = { result ->
                                            val updateScreen = NewUpdateRoute(
                                                versionName = result.release.version,
                                                changelogInfo = result.release.info,
                                                releaseLink = result.release.releaseLink,
                                                downloadLink = result.release.downloadLink,
                                            )
                                            backStack.add(updateScreen)
                                        },
                                        onFinish = {
                                            isCheckingUpdates = false
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }

            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.whats_new),
                    onPreferenceClick = { uriHandler.openUri(RELEASE_URL) },
                )
            }

            if (isReleaseBuildType || isDebugBuildType) {
                item {
                    TextPreferenceWidget(
                        title = stringResource(KMR.strings.whats_coming),
                        onPreferenceClick = {
                            backStack.add(RoadmapRoute)
                        },
                    )
                }
            }
            // KMK <--

            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.help_translate),
                    onPreferenceClick = {
                        uriHandler.openUri(
                            "https://hosted.weblate.org/engage/komikku-app/",
                        )
                    },
                )
            }

            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.licenses),
                    onPreferenceClick = { backStack.add(OpenSourceLicensesRoute) },
                )
            }

            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.privacy_policy),
                    onPreferenceClick = { uriHandler.openUri("https://komikku-app.github.io/privacy/") },
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    LinkIcon(
                        label = stringResource(MR.strings.website),
                        icon = Icons.Outlined.Public,
                        url = "https://komikku-app.github.io",
                    )
                    LinkIcon(
                        label = "Discord",
                        icon = CustomIcons.Discord,
                        url = "https://discord.gg/JcQZYvCbYH",
                    )
                    // LinkIcon(
                    //     label = "X",
                    //     icon = CustomIcons.X,
                    //     url = "https://x.com/mihonapp",
                    // )
                    // LinkIcon(
                    //     label = "Facebook",
                    //     icon = CustomIcons.Facebook,
                    //     url = "https://facebook.com/mihonapp",
                    // )
                    // LinkIcon(
                    //     label = "Reddit",
                    //     icon = CustomIcons.Reddit,
                    //     url = "https://www.reddit.com/r/mihonapp",
                    // )
                    LinkIcon(
                        label = "GitHub",
                        icon = CustomIcons.Github,
                        url = "https://github.com/PineappleTwilight/komikku-pineapple",
                    )
                }
            }
        }
    }
}

/**
 * Checks version and shows a user prompt if an update is available.
 */
private suspend fun checkVersion(
    context: Context,
    onAvailableUpdate: (GetApplicationRelease.Result.NewUpdate) -> Unit,
    onFinish: () -> Unit,
) {
    val updateChecker = AppUpdateChecker()
    withUIContext {
        try {
            when (val result = withIOContext { updateChecker.checkForUpdate(context, forceCheck = true) }) {
                is GetApplicationRelease.Result.NewUpdate -> {
                    onAvailableUpdate(result)
                }
                is GetApplicationRelease.Result.NoNewUpdate -> {
                    context.toast(MR.strings.update_check_no_new_updates)
                }
                is GetApplicationRelease.Result.OsTooOld -> {
                    context.toast(MR.strings.update_check_eol)
                }
            }
        } catch (e: Exception) {
            context.toast(e.message)
            logcat(LogPriority.ERROR, e)
        } finally {
            onFinish()
        }
    }
}

fun getVersionName(withBuildDate: Boolean): String {
    val build = when {
        isDebugBuildType -> {
            "Debug ${BuildConfig.COMMIT_SHA}".let {
                if (withBuildDate) {
                    "$it (${getFormattedBuildTime()})"
                } else {
                    it
                }
            }
        }

        isPreviewBuildType -> {
            "Beta r${BuildConfig.COMMIT_COUNT}".let {
                if (withBuildDate) {
                    "$it (${BuildConfig.COMMIT_SHA}, ${getFormattedBuildTime()})"
                } else {
                    "$it (${BuildConfig.COMMIT_SHA})"
                }
            }
        }

        else -> {
            "Stable ${BuildConfig.VERSION_NAME}".let {
                if (withBuildDate) {
                    "$it (${getFormattedBuildTime()})"
                } else {
                    it
                }
            }
        }
    }
    // KMK --> The no-MTL flavor installs under its own app id and ships without the AI
    // engine, so it names itself here instead of looking like a broken main build.
    return if (BuildConfig.IS_NOMTL) "$build · No-MTL build" else build
    // KMK <--
}

internal fun getFormattedBuildTime(): String {
    return try {
        LocalDateTime.ofInstant(
            Instant.parse(BuildConfig.BUILD_TIME),
            ZoneId.systemDefault(),
        )
            .toDateTimestampString(
                UiPreferences.dateFormat(
                    globalAppGraph.uiPreferences.dateFormat().get(),
                ),
            )
    } catch (_: Exception) {
        BuildConfig.BUILD_TIME
    }
}
