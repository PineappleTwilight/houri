// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlin.math.max

class WebGpuViewerContinuous(activity: ReaderActivity, val useGap: Boolean = false) :
    WebGpuViewer(activity, isReversed = false, isVertical = true, pager = ImageViewContinuous(activity)) {

    override val isContinuous: Boolean = true

    // How many pages the viewport shows depends on the zoom, and a page on screen has to be
    // decoded rather than merely reserved - so the window follows what the last frame reached.
    // KMK --> Pref acts as a raisable floor; live reach always wins so shrinking the
    // pref can never starve the visible viewport.
    override val preloadAhead get() = max(max(3, config.preloadAhead), state.pagesBelow)
    override val preloadBehind get() = max(max(1, config.preloadBehind), state.pagesAbove)
    // KMK <--

    // The state reaches MAX_VISIBLE_PAGES either side of the current page whatever the zoom - to
    // measure the document's end as well as to draw - and every page in that reach is created on
    // demand here. A chapter boundary holds both edge windows plus the transition page plus
    // swap residue at once (up to ~13 live shells), so the cache keeps that whole working
    // set: evicting a half-visible decoded page reverts it to a placeholder and its
    // re-decode shifts every slot below it, which read as constant flicker at chapter
    // edges. Low-RAM devices keep the old tighter budget instead of risking OOM.
    override val cacheSize get() =
        (if (isLowRamDevice) 3 else 7) + 2 * ImageViewerContinuousState.MAX_VISIBLE_PAGES

    private val state get() = (pager as ImageViewContinuous).state

    init {
        // KMK --> Library flag replaces the old DoubleTapZoomGateLayout proxy.
        // Resolved here (not in the base init) because isContinuous is only
        // assigned after super construction, and reseeding the diff baseline so
        // the first emission compares against the continuous-mode profile.
        state.doubleTapZoomEnabled = config.resolveDoubleTapZoom()
        config.reseedDiffBaseline()
        // KMK <--
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
        // Snap without walking: scrollTo(0f) walks the page chain with live
        // onPageChange callbacks, which re-target currentPage mid-scroll and
        // land somewhere random. jumpToTop fires no callbacks.
        state.jumpToTop()
    }
}
// Mihon <--
