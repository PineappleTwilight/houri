package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * Interface for implementing a viewer.
 */
interface Viewer {

    /**
     * Returns the view this viewer uses.
     */
    fun getView(): View

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    fun destroy() {}

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    fun setChapters(chapters: ViewerChapters)

    /**
     * Tells this viewer to move to the given [page].
     */
    fun moveToPage(page: ReaderPage)

    /**
     * Whether this viewer lays pages out vertically. Used for orientation and
     * progress-bar direction instead of `is` checks in the activity.
     */
    val isVertical: Boolean
        get() = false

    /**
     * The currently displayed [ReaderPage], if this viewer shows chapter pages.
     * Viewers with their own page model (WebGPU) return null.
     */
    val currentReaderPage: ReaderPage?
        get() = null

    /**
     * Advances one step for auto-scroll. No-op by default; pagers step pages,
     * webtoon scrolls, WebGPU viewers opt out.
     */
    fun moveToNext() {}

    /**
     * Retries translation for the current page. No-op by default.
     */
    fun retryTranslation() {}

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean
}
