// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import eu.kanade.tachiyomi.source.model.Page
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

// The decode queue and its recovery ladder: which pages are owed a decode, how often a page that
// cannot finish is retried, and when its own state is thrown away instead.
//
// The invariant the whole file exists to protect is that a page is never sitting in a state that
// `queueForDecode` treats as "someone is already on it" while nothing actually is. `ensureDecoding`
// is the one producer (the renderer asked for it); `requeueStuckPage` is the one place allowed to
// break the tie.

/**
 * Queue a page for decoding if not already queued/loading/decoded.
 * If prioritize=true and page is already queued, moves it to front.
 * Thread-safe: acquires [lock] internally.
 */
internal fun WebGpuViewer.queueForDecode(page: ViewerReaderPage, prioritize: Boolean = false) {
    synchronized(lock) {
        // Already has a decoded image
        if (page.isDecoded) return

        when (page.state) {
            PageState.IDLE -> {
                page.state = PageState.QUEUED
                // The worker pops with removeLast(), so the tail is what decodes next and the head
                // is what waits. A prioritised page therefore goes to the tail.
                if (prioritize) {
                    decodeQueue.addLast(page)
                } else {
                    decodeQueue.addFirst(page)
                }
                lock.notify()
                logcat(LogPriority.DEBUG) {
                    "Queued ch=${page.page.chapter.chapter.id}/i=${page.page.index} " +
                        "priority=$prioritize depth=${decodeQueue.size}"
                }
            }

            PageState.QUEUED -> {
                // Already queued - move to the decoding end if prioritising
                if (prioritize && decodeQueue.remove(page)) {
                    decodeQueue.addLast(page)
                }
            }

            PageState.LOADING -> {
                // KMK --> A LOADING page whose bytes are already on disk only needs decoding, so
                // it can join the decode queue directly. Treating LOADING as "already being
                // processed" and returning is what wedged pages forever: [startPageLoad]'s cleanup
                // only resets LOADING -> IDLE while the page is still in the cache, so a shell
                // evicted mid-load (or whose load coroutine was cancelled) kept the state, and
                // re-entering it - getPage hands the same shell back - re-entered this branch,
                // which did nothing. Nothing else in the viewer writes LOADING, so the page spun
                // its ProgressPage indefinitely.
                //
                // The membership test closes the mirror-image hazard: a LOADING page is not always
                // un-queued, because [startPageLoad] can claim a page the worker already popped off
                // it while it was still QUEUED. Enqueueing then gave the worker two entries for one
                // page - the second decode uploaded a full image only for the `!isDecoded` guard to
                // discard it, leaking every texture that upload created.
                // KMK <--
                if (page.page.status == Page.State.Ready && !decodeQueue.contains(page)) {
                    page.state = PageState.QUEUED
                    decodeQueue.addLast(page)
                    lock.notify()
                }
            }

            PageState.DECODING -> {
                // Already being processed
            }
        }
    }
}

/**
 * Puts a page the renderer has asked for into the decode pipeline.
 *
 * Called from `fetchPage`, which is the only place that knows a page is genuinely wanted on screen
 * right now. Every other queueing site is a speculative preload walking outward from currentPage,
 * and its window is computed from the reach reported by the renderer on the *previous* pass - so a
 * page the renderer walks to can sit outside it. That left the shell in the cache showing its
 * placeholder with nothing scheduled: `getPage` had created it, `preloadPages` had not reached it,
 * and no later call would. The gap then persisted as a spinning indicator with the pages around it
 * decoded normally, which is exactly what scrolling back into an evicted zone produced.
 *
 * Queueing here makes the guarantee local - a page the renderer holds is a page being worked on -
 * rather than depending on a speculative walk having covered it.
 */
internal fun WebGpuViewer.ensureDecoding(page: ViewerPage) {
    when (page) {
        // A transition is drawn procedurally; there is nothing to decode.
        is ViewerTransitionPage -> Unit
        is ViewerReaderPage -> {
            // One-shot per page. fetchPage runs every frame for every page in the render window, so
            // queueing unconditionally turned any page that could not complete into a frame-rate
            // retry loop: each pass allocated decoder and network buffers, the worker forced a GC on
            // every failure, and the heap was exhausted while the whole app stalled behind the lock.
            // Queueing once at first demand still closes the gap this was written for, and the
            // signal-driven liveness sweep remains the recovery path for a genuine stall.
            if (page.wantedByRender) return
            page.wantedByRender = true
            synchronized(lock) {
                val record = stuckRecords[pageKey(page)] ?: return@synchronized
                // Only a real absence counts as a fresh chance. A shell rebuilt by the escalation is
                // a new shell moments after the old one, so resetting on that would restart the
                // ladder on every rebuild - which is the churn the escalation exists to stop.
                if (System.currentTimeMillis() - record.lastAttemptAt > STUCK_REDRIVE_FRESH_WINDOW_MS) {
                    logcat(LogPriority.DEBUG) {
                        "Re-demand ch=${page.page.chapter.chapter.id}/i=${page.page.index} " +
                            "after ${System.currentTimeMillis() - record.lastAttemptAt}ms away " +
                            "- stuck ladder reset from ${record.attempts} attempts"
                    }
                    record.attempts = 0
                }
            }
            queueForDecode(page, prioritize = page === currentPage)
        }
        else -> Unit
    }
}

/**
 * Releases a page the viewer believes is in flight but is not, so it can be worked on again.
 *
 * The recovery counterpart to [queueForDecode], which is deliberately conservative: it treats
 * `LOADING`/`DECODING` as "someone is on it" and does nothing. That is right while the owning
 * coroutine is alive and wrong the moment it is not, and because nothing else in the viewer can tell
 * the difference, the page kept its state with no work behind it and spun forever. This is the only
 * place that breaks the tie, and it does so only when the page is demonstrably not queued and not
 * being decoded.
 *
 * Unbounded, this is the loop rather than the cure. The worker's own cleanup hands a failed page
 * back as IDLE and signals the sweep that calls here, so every retry manufactures the condition that
 * schedules the next one, as fast as a decode can fail - a thousand attempts a second, each
 * allocating decoder memory, until the app is killed. What bounds it is the cooldown, and what makes
 * it useful rather than merely quiet is the escalation: repeated cheap retries, then the page's own
 * state is thrown away and rebuilt, which is the only thing that can help when the reason it is
 * stuck is state it is holding.
 *
 * Answers what it did, so the caller can log real recoveries and re-arm after a cooldown instead
 * of assuming every orphan was put back on the queue.
 */
internal fun WebGpuViewer.requeueStuckPage(page: ViewerReaderPage): StuckRecovery {
    val now = System.currentTimeMillis()
    var escalate = false
    synchronized(lock) {
        if (page.isDecoded || page.imagePage.destroyed) return StuckRecovery.GONE
        val record = stuckRecords.getOrPut(pageKey(page)) { StuckPageRecord() }

        // Rate limit rather than a cap. A cap would leave a page that needed one more attempt
        // permanently broken; this keeps trying, so the page does eventually load, while bounding
        // how fast a page that cannot load can spin.
        if (now - record.lastAttemptAt < STUCK_REDRIVE_COOLDOWN_MS) return StuckRecovery.DEFERRED
        record.lastAttemptAt = now
        record.attempts++

        when {
            record.attempts <= STUCK_REDRIVE_SOFT_LIMIT -> page.state = PageState.IDLE
            record.attempts <= STUCK_REDRIVE_HARD_LIMIT -> escalate = true
            else -> {
                // Out of budget, and simply returning here is what makes it permanent. The shell
                // stays resident with `wantedByRender` still set, and ensureDecoding - the one
                // place that resets the record - returns before it can reach the reset. Nothing
                // then queues this page again for the rest of the session, however long the reader
                // sits on it. Releasing the flag hands the page back to the render walk, which
                // re-arms the whole ladder once the reader has been away for the fresh window.
                page.wantedByRender = false
                logcat(LogPriority.WARN) {
                    "Giving up on ch=${page.page.chapter.chapter.id}/i=${page.page.index} " +
                        "after ${record.attempts} attempts last=${lastDecodeOutcome(page) ?: "none"} " +
                        "- handing it back to the render walk"
                }
                return StuckRecovery.GONE
            }
        }
    }

    if (escalate) {
        // Past the cheap retries, so stop reusing this page's state: drop the shell and its tiles
        // entirely and let the next demand build it again from nothing. Whatever wedged it - a dead
        // tile, a half-applied split, an image the decoder rejected - is state this page is holding,
        // so the only way out is to stop holding it.
        return tearDownStuckPage(page)
    }

    queueForDecode(page, prioritize = currentPage?.let { pageKey(it) == pageKey(page) } ?: false)
    return StuckRecovery.REDRIVEN
}

/** What one recovery attempt on a stuck page actually did. */
internal enum class StuckRecovery {
    REDRIVEN,
    REBUILT,
    DEFERRED,
    GONE,
}

internal class StuckPageRecord {
    var attempts: Int = 0
    var lastAttemptAt: Long = 0L
}

/**
 * Minimum gap between two recovery attempts on one page - the difference between a page that spins
 * and a page that is merely slow.
 */
internal const val STUCK_REDRIVE_COOLDOWN_MS = 1_500L

/**
 * How long a page must be out of demand before [ensureDecoding] treats coming back to it as a fresh
 * chance. Comfortably longer than the cooldown, so a rebuild is never mistaken for the user having
 * left and returned, and short enough that scrolling away and back still retries a hard failure.
 */
internal const val STUCK_REDRIVE_FRESH_WINDOW_MS = 5_000L

/** Attempts re-queued before the page's own state is thrown away instead. */
private const val STUCK_REDRIVE_SOFT_LIMIT = 3

/**
 * Attempts after which the page is left alone; recovery has clearly not fixed it. Nine at the
 * cooldown is roughly fourteen seconds of trying, which outlasts a slow load rather than writing a
 * page off, and a page that comes back on screen gets its budget again from [ensureDecoding].
 */
private const val STUCK_REDRIVE_HARD_LIMIT = 9

/**
 * Discards [page] and everything it owns, so the next [WebGpuViewer.getPage] builds it fresh.
 *
 * Cleanup runs outside the lock - it tears down textures, and holding the viewer lock across that is
 * what the eviction path deliberately avoids too.
 */
internal fun WebGpuViewer.tearDownStuckPage(page: ViewerReaderPage): StuckRecovery {
    synchronized(lock) {
        if (page.imagePage.destroyed) return StuckRecovery.GONE
        pageCache.remove(pageKey(page))
        decodeQueue.remove(page)
    }
    releasePageResources(page)
    return StuckRecovery.REBUILT
}
// Mihon <--
