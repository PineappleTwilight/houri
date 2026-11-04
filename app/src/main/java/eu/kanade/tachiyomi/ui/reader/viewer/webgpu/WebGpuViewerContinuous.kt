// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.JoystickScrollHelper
import kotlin.math.max

/** Ceiling on how far past the baseline window [cacheSize] grows to follow the drawn reach. */
private const val MAX_CACHED_REACH = 6

class WebGpuViewerContinuous(activity: ReaderActivity, val useGap: Boolean = false) :
    WebGpuViewer(activity, isReversed = false, isVertical = true, pager = ImageViewContinuous(activity)) {

    override val isContinuous: Boolean = true

    // How many pages the viewport shows depends on the zoom, and a page on screen has to be
    // decoded rather than merely reserved - so the window follows what the last frame reached.
    // KMK --> Pref acts as a raisable floor; live reach always wins so shrinking the
    // pref can never starve the visible viewport.
    //
    // Clamped to MAX_CACHED_REACH, which is what cacheSize is budgeted against: the render walk is
    // allowed to reach further than the cache holds (so the screen is fully painted when zoomed
    // out), but a speculative preload that wide created more shells than the cache keeps and
    // evicted pages it had just queued. Pages past the clamp are still decoded - the renderer asks
    // for them itself through fetchPage - so the cap costs reach-ahead, not correctness.
    override val preloadAhead get() =
        max(max(3, config.preloadAhead), state.pagesBelow.coerceAtMost(MAX_CACHED_REACH))
    override val preloadBehind get() =
        max(max(1, config.preloadBehind), state.pagesAbove.coerceAtMost(MAX_CACHED_REACH))
    // KMK <--

    // The state walks at least MAX_VISIBLE_PAGES either side of the current page - to measure
    // the document's end as well as to draw - and further when the zoom puts more of them on
    // screen. Every page in that reach is created on demand here. A chapter boundary holds both
    // edge windows plus the transition page plus swap residue at once (up to ~13 live shells), so
    // the cache keeps that whole working set: evicting a half-visible decoded page reverts it to a
    // placeholder and its re-decode shifts every slot below it, which read as constant flicker at
    // chapter edges. Small devices keep a tighter budget above that floor instead of risking OOM.
    //
    // KMK --> The reach therefore follows what the last frame drew rather than sitting at the
    // baseline count: a cache that cannot hold its own drawn window evicts pages that are on screen.
    // Capped because a decoded webtoon segment is ~47MB of texture, so the working set has to stay
    // bounded no matter how far out the reader has zoomed.
    //
    // Only the *slack* above the visible floor is pressure-sensitive. The floor itself is not
    // negotiable here for the reason in the class comment - evicting a half-visible decoded page
    // reverts it to a placeholder, and its re-decode shifts every slot below it, which read as
    // constant flicker at chapter edges.
    override val cacheSize: Int
        get() {
            val drawn = max(state.pagesBelow, state.pagesAbove).coerceAtMost(MAX_CACHED_REACH)
            val slack = when {
                memoryBudget.deviceTier <= WebGpuMemoryBudget.DeviceTier.SMALL -> 3
                memoryBudget.budget().pressure == WebGpuMemoryBudget.Pressure.CRITICAL -> 2
                memoryBudget.budget().pressure == WebGpuMemoryBudget.Pressure.TIGHT -> 4
                else -> 7
            }
            return slack + 2 * max(ImageViewerContinuousState.MAX_VISIBLE_PAGES, drawn)
        }

    private val state get() = (pager as ImageViewContinuous).state

    // KMK -->
    private val joystickScroll = JoystickScrollHelper(
        scrollByPx = { dy -> state.scrollBy(dy) },
        maxSpeedPxPerSec = ::controllerMaxSpeedPxPerSec,
        isEnabled = { !activity.viewModel.state.value.menuVisible },
    )
    // KMK <--

    init {
        // KMK --> Library flag replaces the old DoubleTapZoomGateLayout proxy.
        // Resolved here (not in the base init) because isContinuous is only
        // assigned after super construction, and reseeding the diff baseline so
        // the first emission compares against the continuous-mode profile.
        state.doubleTapZoomEnabled = config.resolveDoubleTapZoom()
        // KMK --> The webtoon pinch preference, which the legacy webtoon viewer already honors.
        state.pinchZoomEnabled = readerPreferences.webtoonPinchToZoomEnabled().get()
        // The border-crop preference, which the paged viewer applies by trimming at decode.
        // Continuous draws the trim the decoder already measured, cropped to the viewport width.
        state.cropBorders = config.imageCropBorders
        // KMK <--
        config.reseedDiffBaseline()
        // KMK <--
        // Scrolling clear of a transition page is the only point this mode can call the chapter
        // before it finished - reaching a page's top comes a screen too early. Reported on every
        // change, so scrolling back up over it and down again selects that last page again.
        state.onPageScrolledThrough = onScrolledThrough@{ imagePage ->
            val transition = imagePage as? TransitionPage
            val chapter = transition?.prevChapter ?: return@onScrolledThrough
            // KMK --> The paged viewer pops the menu when the reader lands on the terminal
            // "no more chapters" transition (they are probably going to close it); the strip
            // never selects that page, so show it when the transition is scrolled through
            // instead. Idempotent - showMenu is a no-op while the menu is visible.
            if (transition.nextChapter == null) {
                activity.showMenu()
            }
            // KMK <--
            // Only while the chapter is still the active one. Once the reader has entered the next
            // chapter the same transition page keeps reporting on every pass back over it, and each
            // report ran loadNewChapter again - a fresh ChapterLoader, a read-timer restart and a
            // whole viewerChapters swap - which invalidated every neighbour-link memo and re-walked
            // the page window on the way. The chapter advances on its own when the reader's own
            // page crossing lands in it, which is the report that matters.
            if (chapter !== viewerChapters?.currChapter) return@onScrolledThrough
            val lastPage = chapter.pages?.lastOrNull() ?: return@onScrolledThrough
            activity.onPageSelected(lastPage)
        }
    }

    private fun scrollByHalfPage(direction: Int) {
        val cur = currentPage
        val canAdvance = if (direction > 0) {
            val nxt = (cur as? ViewerReaderPage)?.next ?: cur?.next
            nxt != null
        } else {
            val prv = (cur as? ViewerReaderPage)?.prev ?: cur?.prev
            prv != null
        }
        if (!canAdvance) {
            (cur as? ViewerReaderPage)?.let { rp ->
                val targetChapter = if (direction > 0) rp.nextChapter else rp.prevChapter
                targetChapter?.let { ch ->
                    if (ch.state !is eu.kanade.tachiyomi.ui.reader.model.ReaderChapter.State.Loaded) {
                        preloadChapterThenRetry(ch)
                    }
                }
            }
            return
        }
        val totalDistance = direction * state.height / 2f
        state.animateScroll(totalDistance)
    }

    override fun moveRight() = scrollByHalfPage(1)

    override fun moveLeft() = scrollByHalfPage(-1)

    // KMK -->
    override fun moveUp() = scrollByHalfPage(-1)

    override fun moveDown() = scrollByHalfPage(1)

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                return joystickScroll.onDpadKeyEvent(event)
            }
        }
        return super.handleKeyEvent(event)
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (joystickScroll.onGenericMotionEvent(event)) return true
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    // Negative VSCROLL = wheel down = read forward.
                    state.scrollBy(-vScroll * state.height / 8f)
                    return true
                }
            }
        }
        return false
    }

    override fun destroy() {
        joystickScroll.stop()
        super.destroy()
    }

    private fun controllerMaxSpeedPxPerSec(): Float {
        val speed = readerPreferences.controllerScrollSpeed().get()
            .coerceIn(
                ReaderPreferences.CONTROLLER_SCROLL_SPEED_MIN,
                ReaderPreferences.CONTROLLER_SCROLL_SPEED_MAX,
            )
        val viewport = activity.resources.displayMetrics.heightPixels.toFloat()
        return speed * viewport / ReaderPreferences.CONTROLLER_SCROLL_SPEED_DEFAULT
    }
    // KMK <--

    override fun moveToPage(page: ReaderPage) {
        super.moveToPage(page)
        // Snap without walking: scrollTo(0f) walks the page chain with live
        // onPageChange callbacks, which re-target currentPage mid-scroll and
        // land somewhere random. jumpToTop fires no callbacks.
        state.jumpToTop()
    }
}
// Mihon <--
