// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import logcat.LogPriority
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.logcat

// Asking the source for a page's bytes again, when the ones on disk are the problem.
//
// "Retry" already exists in the reader, and deliberately does not do this: PageLoader.retryPage puts
// a page that failed to *load* back on the queue, and HttpPageLoader.internalLoadPage then skips the
// download entirely because chapterCache.isImageInCache is true. That is correct for a network
// failure - the bytes are fine, the connection was not - and useless here, where the cached bytes are
// themselves the defect. Refetching means evicting that cache entry first, which is the whole reason
// this is separate code rather than a call to retryPage.
//
// What it deliberately does NOT do is retry without end. A source serving a corrupt body will serve
// it again, so each page gets a bounded number of these and no more; the caller is expected to have
// already decided the bytes are bad, and this only covers the case where a second copy differs.
// Without that bound this is a request amplifier pointed at a source already failing to answer.
//
// It is also not the fix for a stalled decode. A page wedged in native code never reaches any of
// this, because the thread it would run on is the one that is stuck - which is what the decode
// watchdog in WebGpuDecodeTrace is for.

/**
 * Suffix HttpPageLoader appends to a split segment's cache key.
 *
 * Duplicated rather than imported because the original is private to the loader, and a wrong guess
 * here would evict the wrong cache entry. Segment pages name their parent by this suffix, so the
 * parent has to be stripped before asking the cache to drop anything.
 */
private const val SEGMENT_KEY_SUFFIX = "#segment-"

/** How many times one page may be re-queried from its source before it is left to fail visibly. */
private const val MAX_SOURCE_REQUERIES = 1

/**
 * Drops [page]'s cached bytes and asks the source for them again.
 *
 * The eviction is the point; see the file comment. The state reset matters just as much: a page that
 * has already burned its way up the stuck-page ladder is in cooldown and holding an ErrorPage, so
 * without clearing both the refetch would complete into a page nothing is watching, and the good
 * bytes would sit there next to an error.
 *
 * Called from the decode worker, so everything that can take a lock or touch disk happens before the
 * lock is taken, and the loader call - which goes back out to the network - happens after it is
 * released. The load itself is launched by the loader on its own dispatcher.
 *
 * @return whether a re-query was actually issued.
 */
internal fun WebGpuViewer.requerySuspectPage(page: ViewerReaderPage, reason: String): Boolean {
    val loader = page.page.chapter.pageLoader
    if (loader == null || loader.isRecycled) {
        logcat(LogPriority.DEBUG) {
            "ReQuery skipped ch=${page.page.chapter.chapter.id}/i=${page.page.index}: no loader"
        }
        return false
    }

    val attempts = synchronized(lock) {
        val record = requeryRecords.getOrPut(pageKey(page)) { RequeryRecord() }
        if (record.attempts >= MAX_SOURCE_REQUERIES) return false
        record.attempts++
        record.attempts
    }

    // A split segment is cut from the parent image, so the parent is what has to be evicted - the
    // segment files are derived from it and will be re-cut on the way back through.
    val cacheKey = page.page.imageUrl?.substringBefore(SEGMENT_KEY_SUFFIX)
    var evicted = false
    if (cacheKey != null) {
        evicted = runCatching { globalAppGraph.chapterCache.removeImageFromCache(cacheKey) }
            .onFailure { logcat(LogPriority.WARN, it) { "ReQuery cache eviction failed for $cacheKey" } }
            .getOrDefault(false)
    }

    // Re-arm the page rather than trusting the caller's error state: the ladder's cooldown would
    // otherwise hold it in cooldown, and wantedByRender would keep ensureDecoding from queueing the
    // refetched bytes even once they arrived.
    //
    // The page's own imagePage is left alone. The caller throws immediately after this returns, and
    // the worker's catch is what installs the ErrorPage - so putting a ProgressPage in between would
    // only be overwritten a moment later, and the double teardown risks freeing the same texture.
    synchronized(lock) {
        if (!pageInCache(page)) return false
        page.wantedByRender = false
        stuckRecords.remove(pageKey(page))
        page.state = PageState.IDLE
    }

    logcat(LogPriority.WARN) {
        "ReQuery ch=${page.page.chapter.chapter.id}/i=${page.page.index} attempt=$attempts evicted=$evicted " +
            "reason=$reason url=${cacheKey?.let { it.take(96) } ?: "none"}"
    }

    // Outside the lock, and deliberately not awaited: retryPage re-queues the page and the loader
    // launches the load on its own dispatcher. Holding the viewer lock across a source call would
    // stall every other page behind this one - which is the failure being fixed.
    runCatching { loader.retryPage(page.page) }
        .onFailure { logcat(LogPriority.WARN, it) { "ReQuery retryPage threw for i=${page.page.index}" } }

    return true
}

/**
 * Makes [page] eligible for a fresh decode without touching the source.
 *
 * The cheaper half of the recovery ladder, and the right answer whenever the bytes are fine and the
 * page's own state is not - a half-applied split, a dead tile, a decoder that rejected a page the
 * device can actually draw. Distinguished from [requerySuspectPage] deliberately: this one costs
 * nothing and cannot hammer a source, so it is always tried first.
 */
internal fun WebGpuViewer.requeueFromScratch(page: ViewerReaderPage, reason: String) {
    val requeued = tearDownStuckPage(page)
    synchronized(lock) {
        requeryRecords.remove(pageKey(page))
        if (pageInCache(page)) page.wantedByRender = false
    }
    logcat(LogPriority.DEBUG) {
        "ReQueue ch=${page.page.chapter.chapter.id}/i=${page.page.index} outcome=$requeued reason=$reason"
    }
}

/**
 * How many times each page has been re-queried at its source.
 *
 * Viewer-level and keyed like [WebGpuViewer.pageCache], for the same reason
 * [WebGpuViewer.stuckRecords] is: a re-query tears the page down and rebuilds it, so a counter
 * living on the shell would restart at zero on the rebuilt one and the bound would never be reached.
 */
internal class RequeryRecord {
    var attempts: Int = 0
}

// KMK <--
