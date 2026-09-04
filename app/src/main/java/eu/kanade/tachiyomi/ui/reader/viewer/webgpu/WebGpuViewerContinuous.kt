// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlin.math.max

class WebGpuViewerContinuous(activity: ReaderActivity) :
    WebGpuViewer(activity, isReversed = false, isVertical = true, pager = ImageViewContinuous(activity)) {

    override val isContinuous: Boolean = true

    // How many pages the viewport shows depends on the zoom, and a page on screen has to be
    // decoded rather than merely reserved - so the window follows what the last frame reached.
    override val preloadAhead get() = max(3, state.pagesBelow)
    override val preloadBehind get() = max(1, state.pagesAbove)

    // The state reaches MAX_VISIBLE_PAGES either side of the current page whatever the zoom - to
    // measure the document's end as well as to draw - and every page in that reach is created on
    // demand here. Sized under it, each frame evicts exactly what the next one asks for.
    override val cacheSize get() = 2 + 2 * ImageViewerContinuousState.MAX_VISIBLE_PAGES

    private val state get() = (pager as ImageViewContinuous).state

    init {
        // Scrolling clear of a transition page is the only point this mode can call the chapter
        // before it finished - reaching a page's top comes a screen too early. Reported on every
        // change, so scrolling back up over it and down again selects that last page again.
        state.onPageScrolledThrough = onScrolledThrough@{ imagePage ->
            val chapter = (imagePage as? TransitionPage)?.prevChapter ?: return@onScrolledThrough
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

    override fun moveToPage(page: ReaderPage) {
        super.moveToPage(page)
        state.scrollTo(0f)
    }

    // KMK -->
    private val touchProxy = DoubleTapZoomGateLayout(activity).apply {
        addView(
            pager,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        shouldSwallowDoubleTap = { !config.doubleTapZoom }
    }

    override fun getView(): View = touchProxy
    // KMK <--
}

// KMK -->
/**
 * The continuous viewer library implements double-tap zoom internally with no
 * opt-out. When the preference is disabled this proxy consumes the whole second
 * gesture of a detected double-tap; the library then resolves the first tap as a
 * single tap and no zoom happens.
 */
private class DoubleTapZoomGateLayout(context: Context) : FrameLayout(context) {

    var shouldSwallowDoubleTap: () -> Boolean = { false }

    private var swallowing = false

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (shouldSwallowDoubleTap()) {
                    swallowing = true
                    return true
                }
                return false
            }
        },
    )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        if (swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowing = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
// KMK <--
// Mihon <--
