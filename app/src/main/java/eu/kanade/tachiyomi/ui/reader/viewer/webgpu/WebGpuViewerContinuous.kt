// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import ca.mpreg.webgpuviewer.ImageViewContinuous
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

class WebGpuViewerContinuous(activity: ReaderActivity) :
    WebGpuViewer(activity, isReversed = false, isVertical = true, pager = ImageViewContinuous(activity)) {

    override val isContinuous: Boolean = true

    override val preloadAhead = 1
    override val preloadBehind = 1

    private fun scrollByHalfPage(direction: Int) {
        val state = (pager as ImageViewContinuous).state
        val totalDistance = direction * state.height / 2f
        state.animateScroll(totalDistance)
    }

    override fun moveRight() = scrollByHalfPage(1)

    override fun moveLeft() = scrollByHalfPage(-1)

    override fun moveToPage(page: ReaderPage) {
        super.moveToPage(page)
        (pager as ImageViewContinuous).state.scrollY = 0f
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
