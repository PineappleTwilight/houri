package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Configuration used by WebGPU pager viewers.
 */
class WebGpuConfig(
    private val viewer: WebGpuViewer,
    scope: CoroutineScope,
    readerPreferences: ReaderPreferences,
) : ViewerConfig(readerPreferences, scope) {

    var theme = readerPreferences.readerTheme().get()
        private set

    var automaticBackground = false
        private set

    var dualPageSplitChangedListener: ((Boolean) -> Unit)? = null

    // KMK --> State-only changes apply live without rebuilding decoded pages.
    var imageStateChangedListener: (() -> Unit)? = null
    // KMK <--

    var imageScaleType = 1
        private set

    var imageZoomType = ReaderPageImageView.ZoomStartPosition.LEFT
        private set

    var imageCropBorders = false
        private set

    var navigateToPan = false
        private set

    var landscapeZoom = false
        private set

    var transitionAnimation = ReaderPreferences.TransitionAnimation.BASIC
        private set

    var transitionAnimationDual = ReaderPreferences.TransitionAnimation.BASIC
        private set

    var cutoutMode = ReaderPreferences.CutoutMode.AVOID
        private set

    var cutoutModeDual = ReaderPreferences.CutoutMode.AVOID
        private set

    // KMK -->
    /**
     * Single/double/automatic page layout, same switch as the legacy pager
     * ([PagerConfig.PageLayout]). Previously ignored here, so the layout chips
     * and the bottom-bar toggle did nothing in the WebGPU reader.
     */
    var doublePages = readerPreferences.pageLayout().get() == PagerConfig.PageLayout.DOUBLE_PAGES &&
        !readerPreferences.dualPageSplitPaged().get()
        private set

    var autoDoublePages = readerPreferences.pageLayout().get() == PagerConfig.PageLayout.AUTOMATIC
        private set

    /**
     * Pairing direction for spreads, same switch as the legacy pager
     * ([PagerConfig.invertDoublePages]). Unlike the pager it is not gated on
     * the split toggle: both split and double layout modes pair pages here.
     */
    var invertDoublePages = false
        private set
    // KMK <--

    // KMK -->
    /**
     * Transient spread-shift toggle, ported from the legacy pager's shift button
     * ([eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig.shiftDoublePage]):
     * when true, dual-page pairing starts at page 0 instead of leaving the
     * cover solo, fixing mis-paired spreads. Toggled from the reader bottom
     * bar; not persisted, reset on viewer rebuild.
     */
    var shiftDoublePage = false

    var continuousMinWidth = 100
        private set

    var zoomOutDisabled = false
        private set

    // KMK --> paged "disable zoom in" was wired only into the legacy pager;
    // the WebGPU reader ignored it. Registered here so the max-scale clamp
    // applies to WebGPU paged pages too.
    var disableZoomIn = false
        private set
    // KMK <--

    // KMK -->
    var continuousGap = 10
        private set
    // KMK <--

    // KMK -->
    var pageOffset = readerPreferences.webgpuPageOffset().get()
        private set
    // KMK <--

    // KMK -->
    var matchDoublePageHeights = readerPreferences.dualPageMatchHeights().get()
        private set
    // KMK <--

    // KMK -->
    var webgpuDarkMode = readerPreferences.webgpuDarkMode().get()
        private set

    var webgpuDarkModeAmoled = readerPreferences.webgpuDarkModeAmoled().get()
        private set

    var darkModeTolerance = readerPreferences.webgpuDarkModeTolerance().get() / 100f
        private set

    var darkModeChunkRange = readerPreferences.webgpuDarkModeChunkRange().get() / 100f
        private set
    // KMK <--

    // KMK -->
    var brightness = readerPreferences.webgpuBrightness().get() / 100f
        private set

    var contrast = readerPreferences.webgpuContrast().get() / 100f
        private set

    var hlgEnabled = readerPreferences.webgpuHlg().get()
        private set

    var hlgExposure = readerPreferences.webgpuHlgExposure().get() / 100f
        private set

    var lutPreset = readerPreferences.webgpuLutPreset().get()
        private set

    var lutCustomPath = readerPreferences.webgpuLutCustomPath().get()
        private set

    var lutIntensity = readerPreferences.webgpuLutIntensity().get() / 100f
        private set

    var compareTranslation = readerPreferences.webgpuCompareTranslation().get()
        private set

    var perfHud = readerPreferences.webgpuPerfHud().get()
        private set

    var einkPreset = readerPreferences.webgpuEinkPreset().get()
        private set
    // KMK <--

    // KMK -->
    var artCnnUpscaler = readerPreferences.webgpuArtCnnUpscaler().get()
        private set

    var fastRender = readerPreferences.webgpuFastRender().get()
        private set

    var preloadAhead = readerPreferences.webgpuPreloadAhead().get()
        private set

    var preloadBehind = readerPreferences.webgpuPreloadBehind().get()
        private set
    // KMK <--

    // KMK -->
    private val pagedDoubleTapZoomPref = readerPreferences.pagedDoubleTapZoomEnabled()
    private val webtoonDoubleTapZoomPref = readerPreferences.webtoonDoubleTapZoomEnabled()

    /**
     * Resolved at access time instead of init: [WebGpuViewer.isContinuous] is
     * overridden by subclasses whose properties are not assigned while this config's
     * init runs during super construction.
     */
    val doubleTapZoom: Boolean
        get() = if (viewer.isContinuous) {
            webtoonDoubleTapZoomPref.get()
        } else {
            pagedDoubleTapZoomPref.get()
        }

    // KMK -->
    /**
     * Single consumption point for the double-tap-zoom preference. Paged honors it
     * via applyDoubleTapZoomPolicy (WebGpuDecode.kt), continuous via
     * DoubleTapZoomGateLayout.shouldSwallowDoubleTap (WebGpuViewerContinuous.kt).
     * Both stay behind this proxy until the viewer library exposes its own flag
     * (Unit 1) — then that flag replaces the proxy body here, call sites unchanged.
     */
    fun resolveDoubleTapZoom(): Boolean = doubleTapZoom
    // KMK <--

    var doubleTapZoomChangedListener: ((Boolean) -> Unit)? = null

    init {
        merge(
            pagedDoubleTapZoomPref.changes(),
            webtoonDoubleTapZoomPref.changes(),
        )
            .onEach { doubleTapZoomChangedListener?.invoke(it) }
            .launchIn(scope)
    }
    // KMK <--

    init {
        // Harden: wrap each registration in try-catch to survive corrupted prefs or destroyed viewer.
        try {
            readerPreferences.readerTheme()
                .register(
                    {
                        theme = it
                        automaticBackground = it == 3
                    },
                    {
                        try {
                            imagePropertyChangedListener?.invoke()
                        } catch (_: Exception) {}
                    },
                )
        } catch (_: Exception) {}

        readerPreferences.imageScaleType()
            .register({ imageScaleType = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.zoomStart()
            .register({ zoomTypeFromPreference(it) }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.cropBorders()
            .register({ imageCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.navigateToPan()
            .register({ navigateToPan = it })

        readerPreferences.landscapeZoom()
            .register({ landscapeZoom = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.navigationModePager()
            .register({ navigationMode = it }, { updateNavigation(navigationMode) })

        readerPreferences.pagerNavInverted()
            .register({ tappingInverted = it }, { navigator.invertMode = it })
        readerPreferences.pagerNavInverted().changes()
            .drop(1)
            .onEach { navigationModeChangedListener?.invoke() }
            .launchIn(scope)

        readerPreferences.dualPageSplitPaged()
            .register(
                { dualPageSplit = it },
                {
                    imagePropertyChangedListener?.invoke()
                    dualPageSplitChangedListener?.invoke(it)
                },
            )

        readerPreferences.transitionAnimation()
            .register(
                { transitionAnimation = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.transitionAnimationDual()
            .register(
                { transitionAnimationDual = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.cutoutMode()
            .register(
                { cutoutMode = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.cutoutModeDual()
            .register(
                { cutoutModeDual = it },
                { imageStateChangedListener?.invoke() },
            )

        // KMK -->
        readerPreferences.pageLayout()
            .register(
                {
                    autoDoublePages = it == PagerConfig.PageLayout.AUTOMATIC
                    if (!autoDoublePages) {
                        doublePages = it == PagerConfig.PageLayout.DOUBLE_PAGES && dualPageSplit == false
                    }
                },
                {
                    autoDoublePages = it == PagerConfig.PageLayout.AUTOMATIC
                    if (!autoDoublePages) {
                        doublePages = it == PagerConfig.PageLayout.DOUBLE_PAGES && dualPageSplit == false
                    }
                    imagePropertyChangedListener?.invoke()
                },
            )

        readerPreferences.invertDoublePages()
            .register({ invertDoublePages = it }, { imagePropertyChangedListener?.invoke() })
        // KMK <--

        readerPreferences.continuousMinWidth()
            .register(
                { continuousMinWidth = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webtoonDisableZoomOut()
            .register(
                { zoomOutDisabled = it },
                { imageStateChangedListener?.invoke() },
            )

        // KMK -->
        readerPreferences.pagedDisableZoomIn()
            .register(
                { disableZoomIn = it },
                { doubleTapZoomChangedListener?.invoke(it) },
            )
        // KMK <--

        // KMK -->
        readerPreferences.continuousGap()
            .register(
                { continuousGap = it },
                { imageStateChangedListener?.invoke() },
            )
        // KMK <--

        readerPreferences.webgpuPageOffset()
            .register(
                { pageOffset = it },
                { imageStateChangedListener?.invoke() },
            )

        // KMK -->
        readerPreferences.dualPageMatchHeights()
            .register(
                { matchDoublePageHeights = it },
                { imagePropertyChangedListener?.invoke() },
            )
        // KMK <--

        // KMK -->
        readerPreferences.webgpuDarkMode()
            .register(
                { webgpuDarkMode = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuDarkModeAmoled()
            .register(
                { webgpuDarkModeAmoled = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuDarkModeTolerance()
            .register(
                { darkModeTolerance = it / 100f },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuDarkModeChunkRange()
            .register(
                { darkModeChunkRange = it / 100f },
                { imageStateChangedListener?.invoke() },
            )
        // KMK <--

        // KMK -->
        readerPreferences.webgpuArtCnnUpscaler()
            .register(
                { artCnnUpscaler = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuFastRender()
            .register(
                { fastRender = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuPreloadAhead()
            .register(
                { preloadAhead = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuPreloadBehind()
            .register(
                { preloadBehind = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuBrightness()
            .register(
                { brightness = it / 100f },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuContrast()
            .register(
                { contrast = it / 100f },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuHlg()
            .register(
                { hlgEnabled = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuHlgExposure()
            .register(
                { hlgExposure = it / 100f },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuLutPreset()
            .register(
                { lutPreset = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuLutCustomPath()
            .register(
                { lutCustomPath = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuLutIntensity()
            .register(
                { lutIntensity = it / 100f },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuCompareTranslation()
            .register(
                { compareTranslation = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuPerfHud()
            .register(
                { perfHud = it },
                { imageStateChangedListener?.invoke() },
            )

        readerPreferences.webgpuEinkPreset()
            .register(
                { einkPreset = it },
                { imageStateChangedListener?.invoke() },
            )
        // KMK <--
    }

    private fun zoomTypeFromPreference(value: Int) {
        imageZoomType = when (value) {
            // Auto
            1 -> if (viewer.isReversed) {
                ReaderPageImageView.ZoomStartPosition.RIGHT
            } else {
                ReaderPageImageView.ZoomStartPosition.LEFT
            }
            // Left
            2 -> ReaderPageImageView.ZoomStartPosition.LEFT
            // Right
            3 -> ReaderPageImageView.ZoomStartPosition.RIGHT
            // Center
            else -> ReaderPageImageView.ZoomStartPosition.CENTER
        }
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = this.tappingInverted }
        }

    override fun defaultNavigation(): ViewerNavigation {
        return if (viewer.isVertical) {
            LNavigation()
        } else {
            RightAndLeftNavigation()
        }
    }

    override fun updateNavigation(navigationMode: Int) {
        navigator = when (navigationMode) {
            0 -> defaultNavigation()
            1 -> LNavigation()
            2 -> KindlishNavigation()
            3 -> EdgeNavigation()
            4 -> RightAndLeftNavigation()
            5 -> DisabledNavigation()
            else -> defaultNavigation()
        }
        navigationModeChangedListener?.invoke()
    }
}
