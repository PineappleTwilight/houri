package eu.kanade.tachiyomi.ui.main

import android.animation.ValueAnimator
import android.app.SearchManager
import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEventBus
import androidx.navigation3.runtime.result.rememberResultEventBus
import androidx.navigation3.runtime.result.rememberResultEventBusNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.presentation.browse.ExtensionErrorConfirm
import eu.kanade.presentation.browse.ExtensionErrorDisableSystemDialog
import eu.kanade.presentation.browse.ExtensionErrorReportDialog
import eu.kanade.presentation.browse.ExtensionErrorVersionSuppressDialog
import eu.kanade.presentation.components.AppStateBanners
import eu.kanade.presentation.components.CloudflareBypassBannerBackgroundColor
import eu.kanade.presentation.components.DownloadedOnlyBannerBackgroundColor
import eu.kanade.presentation.components.IncognitoModeBannerBackgroundColor
import eu.kanade.presentation.components.IndexingBannerBackgroundColor
import eu.kanade.presentation.components.RestoringBannerBackgroundColor
import eu.kanade.presentation.components.SyncingBannerBackgroundColor
import eu.kanade.presentation.components.UpdatingBannerBackgroundColor
import eu.kanade.presentation.more.settings.screen.ConfigureExhDialog
import eu.kanade.presentation.more.settings.screen.about.WhatsNewDialog
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.BackupRestoreStatus
import eu.kanade.tachiyomi.data.LibraryUpdateStatus
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.extension.ExtensionErrorReport
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.copyExtensionErrorToClipboard
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassStatus
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.home.TabEvent
import eu.kanade.tachiyomi.ui.home.TopLevelRoute
import eu.kanade.tachiyomi.ui.setting.addSettingsRoute
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import eu.kanade.tachiyomi.util.system.isNavigationBarNeedsScrim
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.updaterEnabled
import eu.kanade.tachiyomi.util.view.setComposeContent
import exh.debug.DebugToggles
import exh.eh.EHentaiUpdateWorker
import exh.log.DebugModeOverlay
import exh.source.ExhPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.migration.Migrator
import mihon.core.migration.Migrator.scope
import mihon.core.navigation.AssistContentRoute
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.DeepLinkRoute
import mihon.core.navigation.ExtensionStoresRoute
import mihon.core.navigation.GlobalSearchRoute
import mihon.core.navigation.HomeRoute
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.NewUpdateRoute
import mihon.core.navigation.OnboardingRoute
import mihon.core.navigation.RestoreBackupRoute
import mihon.core.navigation.SettingsRoute
import mihon.core.navigation.SourceFeedRoute
import mihon.core.navigation.appEntries
import mihon.core.navigation.util.AssistContentManager
import mihon.core.navigation.util.LocalAssistContentManager
import mihon.core.navigation.util.LocalBackStack
import mihon.core.navigation.util.LocalTopLevelBackStack
import mihon.core.navigation.util.popUntilRoot
import mihon.core.navigation.util.rememberOverlaySheetSceneStrategy
import mihon.core.navigation.util.rememberTopLevelBackStack
import mihon.core.navigation.util.rememberTwoPaneSettingsSceneStrategy
import mihon.presentation.core.util.isTabletUi
import soup.compose.material.motion.animation.materialSharedAxisXIn
import soup.compose.material.motion.animation.materialSharedAxisXOut
import soup.compose.material.motion.animation.rememberSlideDistance
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState
import java.util.LinkedList
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : BaseActivity() {

    private val libraryPreferences: LibraryPreferences by lazy { globalAppGraph.libraryPreferences }
    private val preferences: BasePreferences by lazy { globalAppGraph.basePreferences }

    // SY -->
    private val exhPreferences: ExhPreferences by lazy { globalAppGraph.exhPreferences }
    // SY <--

    // KMK -->
    private val backupPreferences: BackupPreferences by lazy { globalAppGraph.backupPreferences }
    private val syncPreferences: SyncPreferences by lazy { globalAppGraph.syncPreferences }
    private val backupRestoreStatus: BackupRestoreStatus by lazy { globalAppGraph.backupRestoreStatus }
    private val syncStatus: SyncStatus by lazy { globalAppGraph.syncStatus }
    private val libraryUpdateStatus: LibraryUpdateStatus by lazy { globalAppGraph.libraryUpdateStatus }
    private val networkPreferences: NetworkPreferences by lazy { globalAppGraph.networkPreferences }
    // KMK <--

    private val downloadCache: DownloadCache by lazy { globalAppGraph.downloadCache }
    private val chapterCache: ChapterCache by lazy { globalAppGraph.chapterCache }

    private val getIncognitoState: GetIncognitoState by lazy { globalAppGraph.getIncognitoState }

    // To be checked by splash screen. If true then splash screen will be removed.
    var ready = false

    private var backStack: NavBackStack<NavKey>? = null

    private val assistContentManager = AssistContentManager()

    // AM (CONNECTIONS) -->
    private val connectionsPreferences: ConnectionsPreferences by lazy { globalAppGraph.connectionsPreferences }
    // <-- AM (CONNECTIONS)

    init {
        registerSecureActivity(this)
    }

    // SY -->
    // Idle-until-urgent
    private var firstPaint = false
    private val iuuQueue = LinkedList<() -> Unit>()

    private fun initWhenIdle(task: () -> Unit) {
        // Avoid sync issues by enforcing main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException("Can only be called on main thread!")
        }

        if (firstPaint) {
            task()
        } else {
            iuuQueue += task
        }
    }

    private var runExhConfigureDialog by mutableStateOf(false)
    // SY <--
    private var didMigration by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val isLaunch = savedInstanceState == null

        // Prevent splash screen showing up on configuration changes
        val splashScreen = if (isLaunch) installSplashScreen() else null

        super.onCreate(savedInstanceState)

        if (isLaunch) {
            lifecycleScope.launchIO {
                didMigration = Migrator.awaitAndRelease()
            }
        }

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }

        // SY -->
        @Suppress("KotlinConstantConditions")
        val hasDebugOverlay = (isDebugBuildType || BuildConfig.BUILD_TYPE == "releaseTest")
        // SY <--

        setComposeContent {
            val context = LocalContext.current

            var incognito by remember { mutableStateOf(getIncognitoState.await(null)) }
            val downloadOnly by preferences.downloadedOnly().collectAsState()
            val indexing by downloadCache.isInitializing.collectAsState()
            // KMK -->
            val restoringState by backupRestoreStatus.isRunning.collectAsState()
            val syncingState by syncStatus.isRunning.collectAsState()
            val updatingState by libraryUpdateStatus.isRunning.collectAsState()
            val restoringProgressBanner by backupPreferences.showRestoringProgressBanner().collectAsState()
            val syncingProgressBanner by syncPreferences.showSyncingProgressBanner().collectAsState()
            val updatingProgressBanner by libraryPreferences.showUpdatingProgressBanner().collectAsState()
            val restoring = restoringState && restoringProgressBanner
            val syncing = syncingState && syncingProgressBanner
            val updating = updatingState && updatingProgressBanner
            val restoringProgress by backupRestoreStatus.progress.collectAsState()
            val syncingProgress by syncStatus.progress.collectAsState()
            val updatingProgress by libraryUpdateStatus.progress.collectAsState()
            // KMK <--

            // KMK --> Ported from jobobby04/TachiyomiSY#1124. Debounced because the status flow
            // ticks twice per bypass (entering and leaving), which would otherwise flash it.
            // Declared here, not next to the Scaffold, because statusBarBackgroundColor below reads it.
            val cloudflareBypass by remember {
                CloudflareBypassStatus.active.debounce(500.milliseconds)
            }.collectAsState(initial = emptyList())
            // KMK <--

            // KMK --> The toggle gates the solve itself, not just the strip: the status bar below
            // takes its Cloudflare colour from the same value, so a "hidden" banner that still
            // repainted the bar would leave the one trace of it the user asked to drop.
            val showCloudflareBypassBanner by networkPreferences.showCloudflareBypassBanner.collectAsState()
            // KMK <--

            val isTabletUi = isTabletUi()
            val isSystemInDarkTheme = isSystemInDarkTheme()
            val statusBarBackgroundColor = when {
                // KMK -->
                updating -> UpdatingBannerBackgroundColor
                syncing -> SyncingBannerBackgroundColor
                restoring -> RestoringBannerBackgroundColor
                // KMK <--
                indexing -> IndexingBannerBackgroundColor
                // KMK --> Otherwise the banner strip renders under a differently-coloured status bar
                // and reads as detached from what it is reporting. Placed after the updating/syncing
                // states so those, which block the UI, keep priority over a solve in progress.
                // KMK <--
                // KMK -->
                showCloudflareBypassBanner && cloudflareBypass.isNotEmpty() -> CloudflareBypassBannerBackgroundColor
                // KMK <--
                downloadOnly -> DownloadedOnlyBannerBackgroundColor
                incognito -> IncognitoModeBannerBackgroundColor
                else -> MaterialTheme.colorScheme.surface
            }
            LaunchedEffect(isSystemInDarkTheme, statusBarBackgroundColor) {
                // Draw edge-to-edge and set system bars color to transparent
                val lightStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
                val darkStyle = SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(
                    statusBarStyle = if (statusBarBackgroundColor.luminance() > 0.5) lightStyle else darkStyle,
                    navigationBarStyle = if (isSystemInDarkTheme) darkStyle else lightStyle,
                )
            }

            val backStack = rememberNavBackStack(HomeRoute)
            val topLevelBackStack = rememberTopLevelBackStack(TopLevelRoute.Library)
            val twoPaneStrategy = rememberTwoPaneSettingsSceneStrategy<NavKey>()
            val overlaySheetSceneStrategy = rememberOverlaySheetSceneStrategy<NavKey>()
            val resultEventBus = rememberResultEventBus()
            val resultEventBusNavEntryDecorator = rememberResultEventBusNavEntryDecorator<NavKey>(
                resultEventBus = resultEventBus,
            )

            CompositionLocalProvider(
                LocalResultEventBus provides resultEventBus,
                LocalBackStack provides backStack,
                LocalTopLevelBackStack provides topLevelBackStack,
                LocalAssistContentManager provides assistContentManager,
            ) {
                LaunchedEffect(backStack) {
                    this@MainActivity.backStack = backStack

                    if (isLaunch) {
                        // Set start screen
                        handleIntentAction(intent, backStack, resultEventBus, isTabletUi)

                        // Reset Incognito Mode on relaunch
                        preferences.incognitoMode().set(false)

                        // SY -->
                        initWhenIdle {
                            // Upload settings
                            if (exhPreferences.enableExhentai().get() &&
                                exhPreferences.exhShowSettingsUploadWarning().get()
                            ) {
                                runExhConfigureDialog = true
                            }
                            // Scheduler uploader job if required

                            EHentaiUpdateWorker.scheduleBackground(this@MainActivity)
                        }
                        // SY <--
                    }
                }

                val currentRoute = backStack.lastOrNull()
                LaunchedEffect(currentRoute) {
                    (
                        (currentRoute as? BrowseSourceRoute)?.sourceId
                            // KMK -->
                            ?: (currentRoute as? SourceFeedRoute)?.sourceId
                        // KMK <--
                        )
                        .let(getIncognitoState::subscribe)
                        .collectLatest { incognito = it }
                }

                val scaffoldInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
                Scaffold(
                    topBar = {
                        AppStateBanners(
                            downloadedOnlyMode = downloadOnly,
                            incognitoMode = incognito,
                            indexing = indexing,
                            // KMK -->
                            restoring = restoring,
                            syncing = syncing,
                            updating = updating,
                            progress = updatingProgress.takeIf { updating }
                                ?: syncingProgress.takeIf { syncing }
                                ?: restoringProgress.takeIf { restoring },
                            // KMK <--
                            // KMK -->
                            cloudflareBypass = cloudflareBypass.firstOrNull()
                                ?.takeIf { showCloudflareBypassBanner },
                            // KMK <--
                            modifier = Modifier.windowInsetsPadding(scaffoldInsets),
                        )
                    },
                    contentWindowInsets = scaffoldInsets,
                ) { contentPadding ->
                    // Consume insets already used by app state banners
                    Box {
                        // KMK --> Upstream's materialSharedAxisX (soup motion library) replaces the
                        // Voyager-style slideInHorizontally + fadeIn pair: the shared-axis slide
                        // runs as a graphicsLayer translation, where the old pair re-drew both
                        // screens every frame and dropped frames on low-end devices.
                        val slideDistance = rememberSlideDistance()
                        // KMK --> The root is the floor of this stack, and the onBack guard below
                        // cannot see the ~100 in-app buttons that pop it directly. Any of them
                        // double-fired within a single frame (a double-tapped onboarding finish,
                        // settings back, or navigate-up) removes HomeRoute itself, and this
                        // NavDisplay recompose then crashed with "backstack cannot be empty".
                        // Repaired here rather than recovered later: NavDisplay has not read the
                        // stack yet at this point, so the frame renders the restored root instead
                        // of throwing, and the write is conditional and self-stabilising - it
                        // makes the very condition it guards on false again, so it cannot loop.
                        if (backStack.isEmpty()) {
                            backStack.add(HomeRoute)
                        }
                        // KMK <--
                        NavDisplay(
                            backStack = backStack,
                            // KMK --> nav3 dispatches onBack from a callback whose enabled state can
                            // be stale by one frame: a second press in the same frame as the pop
                            // that emptied previousEntries, or a press racing a programmatic
                            // popUntilRoot, still reaches this lambda (NavigationBackHandler's own
                            // onBackCompleted comment admits it and prefers that over an
                            // IndexOutOfBounds). Popping HomeRoute here was the last remaining way
                            // this stack emptied and crashed NavDisplay with "backstack cannot be
                            // empty". HomeRoute is the floor: at size 1 back belongs to the system.
                            onBack = {
                                if (backStack.size > 1) backStack.removeLastOrNull()
                            },
                            // KMK <--
                            sceneStrategies = listOf(twoPaneStrategy, overlaySheetSceneStrategy),
                            transitionSpec = {
                                materialSharedAxisXIn(
                                    forward = true,
                                    slideDistance = slideDistance,
                                ) togetherWith materialSharedAxisXOut(
                                    forward = true,
                                    slideDistance = slideDistance,
                                )
                            },
                            popTransitionSpec = {
                                materialSharedAxisXIn(
                                    forward = false,
                                    slideDistance = slideDistance,
                                ) togetherWith materialSharedAxisXOut(
                                    forward = false,
                                    slideDistance = slideDistance,
                                )
                            },
                            predictivePopTransitionSpec = {
                                materialSharedAxisXIn(
                                    forward = false,
                                    slideDistance = slideDistance,
                                ) togetherWith materialSharedAxisXOut(
                                    forward = false,
                                    slideDistance = slideDistance,
                                )
                            },
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(),
                                resultEventBusNavEntryDecorator,
                            ),
                            entryProvider = entryProvider {
                                appEntries()
                            },
                            modifier = Modifier
                                .padding(contentPadding)
                                .consumeWindowInsets(contentPadding),
                        )

                        // Draw navigation bar scrim when needed
                        if (remember { isNavigationBarNeedsScrim() }) {
                            Spacer(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                                    .alpha(0.8f)
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            )
                        }
                    }
                }

                // Pop source-related routes when incognito mode is turned off
                LaunchedEffect(Unit) {
                    preferences.incognitoMode().changes()
                        .drop(1)
                        .filter { !it }
                        .onEach {
                            val currentRoute = backStack.lastOrNull()
                            if (currentRoute is BrowseSourceRoute ||
                                (currentRoute is MangaRoute && currentRoute.fromSource)
                            ) {
                                backStack.popUntilRoot()
                            }
                        }
                        .launchIn(this)

                    // AM (DISCORD) -->
                    connectionsPreferences.enableDiscordRPC().changes()
                        .drop(1)
                        .onEach {
                            if (it) {
                                DiscordRPCService.start(this@MainActivity.applicationContext)
                            } else {
                                DiscordRPCService.stop(this@MainActivity.applicationContext, 0L)
                            }
                        }.launchIn(this)

                    connectionsPreferences.discordRPCStatus().changes()
                        .drop(1)
                        .onEach {
                            with(DiscordRPCService) {
                                discordScope.launchIO {
                                    restart(this@MainActivity.applicationContext)
                                }
                            }
                        }.launchIn(this)
                    // <-- AM (DISCORD)
                }

                HandleOnNewIntent(
                    context = context,
                    backStack = backStack,
                    resultEventBus = resultEventBus,
                    isTabletUi = isTabletUi,
                )

                // KMK -->
                RearmJobs()
                // KMK <--
                CheckForUpdates()
                ShowOnboarding()
                // KMK -->
                ShowExtensionErrorReport()
                // KMK <--
            }

            // SY -->
            if (hasDebugOverlay) {
                val isDebugOverlayEnabled by remember {
                    DebugToggles.ENABLE_DEBUG_OVERLAY.asPref(lifecycleScope)
                }
                if (isDebugOverlayEnabled) {
                    DebugModeOverlay()
                }
            }
            // SY <--

            // KMK -->
            val previewLastVersion = globalAppGraph.preferenceStore.getInt(
                Preference.appStateKey("preview_last_version_code"),
                0,
            )
            val previewCurrentVersion = BuildConfig.COMMIT_COUNT.toInt()
            // KMK <--

            var showChangelog by remember {
                mutableStateOf(
                    // KMK -->
                    (isReleaseBuildType && didMigration) ||
                        (isPreviewBuildType && previewCurrentVersion > previewLastVersion.get()),
                    // KMK <--
                )
            }
            if (showChangelog) {
                // KMK -->
                WhatsNewDialog(
                    onDismissRequest = { showChangelog = false },
                    onOpenWhatsNew = {
                        showChangelog = false
                        context.openInBrowser(RELEASE_URL)
                    },
                )
                // KMK <--
            }
            // KMK -->
            previewLastVersion.set(previewCurrentVersion)
            // KMK <--

            // SY -->
            ConfigureExhDialog(run = runExhConfigureDialog, onRunning = { runExhConfigureDialog = false })
            // SY <--
        }

        val startTime = System.currentTimeMillis()
        splashScreen?.setKeepOnScreenCondition {
            val elapsed = System.currentTimeMillis() - startTime
            elapsed <= SPLASH_MIN_DURATION || (!ready && elapsed <= SPLASH_MAX_DURATION)
        }
        setSplashScreenExitAnimation(splashScreen)

        if (isLaunch && libraryPreferences.autoClearChapterCache().get()) {
            lifecycleScope.launchIO {
                chapterCache.clear()
            }
        }

        // KMK --> Retention sweep: drop cached chapters unread for longer than the setting allows.
        if (isLaunch) {
            lifecycleScope.launchIO {
                chapterCache.sweepExpired(libraryPreferences.chapterCacheRetention().get() * 3_600_000L)
            }
        }
        // KMK <--
    }

    // KMK -->
    override fun onPause() {
        super.onPause()
        MangaCoverMetadata.savePrefs()
    }
    // KMK <--

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        when (backStack?.lastOrNull()) {
            is AssistContentRoute -> {
                assistContentManager.currentAssistUrl?.let {
                    outContent.webUri = it.toUri()
                }
            }
        }
    }

    @Composable
    private fun HandleOnNewIntent(
        context: Context,
        backStack: NavBackStack<NavKey>,
        resultEventBus: ResultEventBus,
        isTabletUi: Boolean,
    ) {
        LaunchedEffect(Unit) {
            callbackFlow {
                val componentActivity = context as ComponentActivity
                val consumer = Consumer<Intent> { trySend(it) }
                componentActivity.addOnNewIntentListener(consumer)
                awaitClose { componentActivity.removeOnNewIntentListener(consumer) }
            }
                .collectLatest {
                    handleIntentAction(it, backStack, resultEventBus, isTabletUi)
                }
        }
    }

    // KMK -->
    @Composable
    private fun RearmJobs() {
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            launchIO {
                try {
                    if (!LibraryUpdateJob.isPeriodicUpdateScheduled(context)) {
                        LibraryUpdateJob.setupTask(context)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            stringResource(KMR.strings.job_failed_schedule_update_check, stringResource(MR.strings.unknown_error)),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                try {
                    if (!BackupCreateJob.isPeriodicBackupScheduled(context)) {
                        BackupCreateJob.setupTask(context)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            stringResource(KMR.strings.job_failed_schedule_backup_check, stringResource(MR.strings.unknown_error)),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }
    // KMK <--

    @Composable
    private fun CheckForUpdates() {
        val context = LocalContext.current
        val backStack = LocalBackStack.current

        // App updates
        LaunchedEffect(Unit) {
            if (updaterEnabled) {
                try {
                    val result = AppUpdateChecker().checkForUpdate(context)
                    if (result is GetApplicationRelease.Result.NewUpdate) {
                        val updateRoute = NewUpdateRoute(
                            versionName = result.release.version,
                            changelogInfo = result.release.info,
                            releaseLink = result.release.releaseLink,
                            downloadLink = result.release.downloadLink,
                        )
                        backStack.add(updateRoute)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }
        }

        // Extensions updates
        LaunchedEffect(Unit) {
            try {
                ExtensionApi().checkForUpdates(context)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Watches for extension failures reported anywhere in the app and asks the user to report them
     * to the repository that ships the broken extension.
     */
    @Composable
    private fun ShowExtensionErrorReport() {
        // KMK -->
        val context = LocalContext.current
        val reporter = globalAppGraph.extensionErrorReporter
        val report by reporter.pendingPrompt.collectAsState()

        var confirm by remember { mutableStateOf<ExtensionErrorConfirm?>(null) }

        // One dialog at a time: a confirmation replaces the popup that raised it rather than
        // stacking on top, so dismissing the confirmation returns to the popup instead of leaving
        // two dialogs competing for the same back press.
        val current = if (confirm == null) report else null
        if (current != null) {
            ExtensionErrorReportDialog(
                report = current,
                extension = globalAppGraph.extensionManager.extensionFor(current.pkgName),
                onOk = reporter::dismissPrompt,
                onDontShowAgain = {
                    confirm = ExtensionErrorConfirm.Suppress(current)
                    reporter.dismissPrompt()
                },
                onDisableSystem = {
                    confirm = ExtensionErrorConfirm.DisableSystem
                    reporter.dismissPrompt()
                },
                onSearchGitHub = { url -> context.openExtensionRepoLink(url) },
                onCopyReport = { context.copyExtensionErrorToClipboard(current) },
            )
        }

        // Held in local state, not read back off the reporter: both actions below clear the report
        // flow, so keying the confirmation off it would tear the dialog down mid-press.
        when (val pending = confirm) {
            is ExtensionErrorConfirm.Suppress -> ExtensionErrorVersionSuppressDialog(
                report = pending.report,
                onDismiss = { confirm = null },
                onConfirm = { reporter.suppressCurrentVersion(pending.report) },
            )
            is ExtensionErrorConfirm.DisableSystem -> ExtensionErrorDisableSystemDialog(
                onDismiss = { confirm = null },
                onConfirm = { reporter.disableSystem() },
            )
            null -> Unit
        }
        // KMK <--
    }

    private fun Context.openExtensionRepoLink(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            logcat(LogPriority.WARN) { "Could not open extension issue search: $url" }
        }
    }

    @Composable
    private fun ShowOnboarding() {
        val backStack = LocalBackStack.current

        LaunchedEffect(Unit) {
            if (!preferences.shownOnboardingFlow().get() && backStack.lastOrNull() !is OnboardingRoute) {
                backStack.add(OnboardingRoute)
            }
        }
    }

    /**
     * Sets custom splash screen exit animation with pineapple spin burst.
     *
     * The pineapple (ic_houri) spins on a loop with accelerate/decelerate
     * burst — FastOutSlowIn per 360° — until the splash is dismissed.
     */
    @Suppress("Deprecation")
    private fun setSplashScreenExitAnimation(splashScreen: SplashScreen?) {
        val root = findViewById<View>(android.R.id.content)
        if (splashScreen == null) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        splashScreen.setOnExitAnimationListener { splashProvider ->
            splashProvider.iconView.translationY = 0F

            // Pineapple spin burst — infinite 360° loops with FastOutSlowIn
            // (linear accelerate → decelerate) until splash is removed.
            val iconView = splashProvider.iconView
            // Ensure pivot is centered even if view not yet laid out
            iconView.pivotX = (if (iconView.width > 0) iconView.width.toFloat() else 72.dpToPx.toFloat()) / 2f
            iconView.pivotY = (if (iconView.height > 0) iconView.height.toFloat() else 72.dpToPx.toFloat()) / 2f
            val spinBurst = ValueAnimator.ofFloat(0f, 360f).apply {
                interpolator = FastOutSlowInInterpolator()
                duration = 650L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { va ->
                    iconView.rotation = va.animatedValue as Float
                }
            }
            spinBurst.start()

            val activityAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                interpolator = LinearOutSlowInInterpolator()
                duration = SPLASH_EXIT_ANIM_DURATION
                addUpdateListener { va ->
                    val value = va.animatedValue as Float
                    root.translationY = value * 16.dpToPx
                }
            }

            val splashAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                interpolator = FastOutSlowInInterpolator()
                duration = SPLASH_EXIT_ANIM_DURATION
                addUpdateListener { va ->
                    val value = va.animatedValue as Float
                    splashProvider.view.alpha = value
                }
                doOnEnd {
                    spinBurst.cancel()
                    // Reset rotation so the icon doesn't stay tilted if reused
                    try {
                        iconView.rotation = 0f
                    } catch (_: Exception) {
                    }
                    splashProvider.remove()
                }
            }

            activityAnim.start()
            splashAnim.start()
        }
    }

    private fun handleIntentAction(
        intent: Intent,
        backStack: NavBackStack<NavKey>,
        resultEventBus: ResultEventBus,
        isTabletUi: Boolean,
    ): Boolean {
        // KMK --> Release the splash as soon as launch-intent routing runs. Every exit
        // from this function - a deep link, a shortcut, or the plain launcher MAIN
        // intent that matches no branch below - has decided the start destination, so
        // the splash has nothing left to wait for. Without this, the unmatched action
        // left `ready` false and the keepOnScreen condition pinned the splash for the
        // full SPLASH_MAX_DURATION (+ the 900ms exit animation) on every ordinary
        // cold start - ~6s of splash before the app became visible.
        ready = true
        // KMK <--
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(
                applicationContext,
                notificationId,
                intent.getIntExtra("groupId", 0),
            )
        }

        val tabToOpen = when (intent.action) {
            Constants.SHORTCUT_LIBRARY -> TabEvent.Library()
            Constants.SHORTCUT_MANGA -> {
                val idToOpen = intent.extras?.getLong(Constants.MANGA_EXTRA) ?: return false
                backStack.popUntilRoot()
                TabEvent.Library(idToOpen)
            }
            Constants.SHORTCUT_UPDATES -> TabEvent.Updates
            Constants.SHORTCUT_HISTORY -> TabEvent.History
            Constants.SHORTCUT_SOURCES -> TabEvent.Browse(false)
            Constants.SHORTCUT_EXTENSIONS -> TabEvent.Browse(true)
            Constants.SHORTCUT_DOWNLOADS -> {
                backStack.popUntilRoot()
                TabEvent.More(toDownloads = true)
            }
            // KMK -->
            Constants.SHORTCUT_LIBRARY_UPDATE_ERRORS -> {
                backStack.popUntilRoot()
                TabEvent.More(toDownloads = false, toLibraryUpdateErrors = true)
            }
            // KMK <--
            Intent.ACTION_APPLICATION_PREFERENCES -> {
                backStack.popUntilRoot()
                backStack.addSettingsRoute(SettingsRoute(), isTabletUi)
                null
            }
            Intent.ACTION_SEARCH, Intent.ACTION_SEND, "com.google.android.gms.actions.SEARCH_ACTION" -> {
                // If the intent match the "standard" Android search intent
                // or the Google-specific search intent (triggered by saying or typing "search *query* on *Tachiyomi*" in Google Search/Google Assistant)

                // Get the search query provided in extras, and if not null, perform a global search with it.
                val query = intent.getStringExtra(SearchManager.QUERY) ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!query.isNullOrEmpty()) {
                    backStack.popUntilRoot()
                    backStack.add(DeepLinkRoute(query))
                }
                null
            }
            INTENT_SEARCH -> {
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                if (!query.isNullOrEmpty()) {
                    val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                    backStack.popUntilRoot()
                    backStack.add(GlobalSearchRoute(query, filter))
                }
                null
            }
            Intent.ACTION_VIEW -> {
                // Handling opening of backup files
                if (intent.data.toString().endsWith(".tachibk")) {
                    backStack.popUntilRoot()
                    backStack.add(RestoreBackupRoute(intent.data.toString()))
                }
                // Deep link to add extension store
                else if (intent.isAddExtensionStoreIntent()) {
                    intent.data?.getQueryParameter("url")?.let { repoUrl ->
                        backStack.popUntilRoot()
                        backStack.add(ExtensionStoresRoute(repoUrl))
                    }
                }
                null
            }
            else -> return false
        }

        if (tabToOpen != null) {
            resultEventBus.sendResult(tabToOpen)
        }

        return true
    }

    private fun Intent.isAddExtensionStoreIntent(): Boolean {
        return (scheme == "tachiyomi" && data?.host == "add-repo") ||
            (scheme == "mihon" && data?.host == "extension-store")
    }

    companion object {
        const val INTENT_SEARCH = "eu.kanade.tachiyomi.SEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"
    }
}

// Splash screen — pineapple spin burst visible during exit, so duration
// allows at least one full 650ms burst loop.
private const val SPLASH_MIN_DURATION = 500 // ms
private const val SPLASH_MAX_DURATION = 5000 // ms
private const val SPLASH_EXIT_ANIM_DURATION = 900L // ms
