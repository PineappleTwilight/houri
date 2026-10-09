// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration.Companion.milliseconds

// The viewer's three long-lived coroutines, started from its `init`.
//
// They live here rather than inline in the class so the class body reads as wiring: each of these
// is a self-contained loop whose failure mode is worth reasoning about on its own.

/**
 * The single decode worker. Processes [WebGpuViewer.decodeQueue] one page at a time on the
 * viewer's dedicated thread.
 *
 * Hardened: respects scope cancellation, handles spurious wakeups, avoids tight-loop on evicted
 * pages, and surfaces OOM as a retryable error page instead of killing the worker.
 */
internal fun WebGpuViewer.startDecodeWorker() {
    val viewer = this
    scope.launch(decodeDispatcher) {
        try {
            while (!isDestroyed) {
                val page: ViewerReaderPage? = synchronized(lock) {
                    while (decodeQueue.isEmpty() && !isDestroyed) {
                        try {
                            lock.wait(1000)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@launch
                        }
                    }
                    if (decodeQueue.isEmpty()) return@synchronized null
                    decodeQueue.removeLast().apply { state = PageState.DECODING }
                }
                if (page == null) continue

                val shouldProcess = synchronized(lock) {
                    pageInCache(page) && page.state == PageState.DECODING && !page.isDecoded
                }

                if (!shouldProcess) {
                    synchronized(lock) {
                        if (pageInCache(page) && page.state == PageState.DECODING) {
                            page.state = PageState.IDLE
                        }
                    }
                    continue
                }

                // Published before the decode so the watchdog can see a page that never returns.
                // decodeReaderPage reads this back as its trace.
                val trace = DecodeTrace(
                    chapterId = page.page.chapter.chapter.id ?: -1L,
                    pageIndex = page.page.index,
                    startedAtMs = System.currentTimeMillis(),
                )
                activeDecode = trace
                logQueueDepth("dequeue")

                try {
                    decodeReaderPage(page)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    logcat(LogPriority.ERROR) { "decodeReaderPage OOM: ${e.message}" }
                    System.gc()
                    synchronized(lock) {
                        if (pageInCache(page) && !page.isDecoded && !page.imagePage.destroyed) {
                            val oldImagePage = page.imagePage
                            page.imagePage = ErrorPage(viewer, "Out of memory", page.spreadPosition)
                            page.state = PageState.IDLE
                            oldImagePage.cleanup()
                            page.imagePage.invalidate()
                        } else if (pageInCache(page)) {
                            page.state = PageState.IDLE
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    val isLinkage = e is LinkageError || e is NoClassDefFoundError || e is UnsatisfiedLinkError
                    logcat(LogPriority.ERROR, e) { "decodeReaderPage${if (isLinkage) " linkage" else ""}: ${e.message}" }
                    synchronized(lock) {
                        if (pageInCache(page) && !page.isDecoded && !page.imagePage.destroyed) {
                            val oldImagePage = page.imagePage
                            val errorMessage = when {
                                isLinkage -> "Decoder not available on this device"
                                e.message?.isNotBlank() == true -> e.message!!
                                else -> "Failed to decode image"
                            }
                            page.imagePage = ErrorPage(viewer, errorMessage, page.spreadPosition)
                            page.state = PageState.IDLE
                            oldImagePage.cleanup()
                            page.imagePage.invalidate()
                        } else if (pageInCache(page)) {
                            page.state = PageState.IDLE
                        }
                    }
                } finally {
                    // KMK --> DECODING must never outlive the attempt. The catch arms above all
                    // end in an ErrorPage or an IDLE reset, but decodeReaderPage also returns
                    // normally on several paths (destroyed viewer, stream unavailable, already
                    // decoded, evicted mid-flight) - and a plain return skips every catch, so
                    // the page kept DECODING with nothing left to move it. queueForDecode treats
                    // DECODING as in-flight and ignores it, so the shell then spun forever.
                    // Resetting here makes "the worker is not on this page anymore" the single
                    // invariant every exit path agrees on.
                    // KMK <--
                    if (!trace.finished) trace.complete("threw")
                    activeDecode = null
                    synchronized(lock) {
                        if (pageInCache(page) && page.state == PageState.DECODING) {
                            page.state = PageState.IDLE
                        }
                    }
                    // The worker leaving a page is what makes any leftover in-flight state
                    // detectable, so this is the natural point to look.
                    stuckSignal.trySend(Unit)
                }
            }
        } catch (e: CancellationException) {
            // Scope cancelled — normal shutdown
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Decode worker died" }
        }
    }
}

/**
 * Liveness net. Every state a page can sit in is owned by one of a small number of
 * writers, and a missed reset on any path (a cancelled load, an early return, a future
 * branch) leaves the shell in a state queueForDecode treats as in-flight - so it is never
 * re-queued and spins forever.
 *
 * Driven by signal rather than a timer because the orphan condition - QUEUED but absent from
 * the queue, or LOADING whose bytes have since arrived - cannot arise on its own: it needs a
 * structural change to the queue or cache, and only a few sites perform one. A timer would
 * pay a lock acquisition and a scan of every live shell for the whole session to find a
 * condition those sites create anyway.
 *
 * Conflated so a burst of evictions costs one scan rather than one per eviction.
 */
internal fun WebGpuViewer.startStuckPageSweep() {
    scope.launch {
        for (signal in stuckSignal) {
            if (isDestroyed) break
            try {
                val orphans = synchronized(lock) {
                    pageCache.values.filterIsInstance<ViewerReaderPage>().filter { page ->
                        if (page.isDecoded || page.imagePage.destroyed) return@filter false
                        when (page.state) {
                            // Queued but absent from the queue: nothing will ever pop it.
                            PageState.QUEUED -> !decodeQueue.contains(page)
                            // LOADING is only released when the bytes are already there: a page
                            // genuinely fetching must keep its state, and queueForDecode will
                            // promote it the moment it reports Ready.
                            PageState.LOADING -> page.page.status == Page.State.Ready
                            // The terminal case: not being worked on at all. The renderer
                            // reached this page (fetchPage called ensureDecoding), so it is on
                            // screen or prewarmed, and nothing else will schedule it - a decode
                            // that bailed mid-flight, or a state left IDLE by an exit path,
                            // strands it behind its placeholder indefinitely. Checking
                            // wantedByRender is what keeps this from queueing speculative shells
                            // that only a preload walk ever touched.
                            PageState.IDLE -> page.wantedByRender && page.imagePage is ProgressPage
                            else -> false
                        }
                    }
                }
                if (orphans.isEmpty()) continue
                // A page inside its cooldown is not an event: logging it would be the spew this
                // sweep is meant to stop, and re-driving it is what could not terminate.
                var reArm = false
                val acted = ArrayList<ViewerReaderPage>(orphans.size)
                orphans.forEach { page ->
                    when (requeueStuckPage(page)) {
                        StuckRecovery.REDRIVEN, StuckRecovery.REBUILT -> acted += page
                        StuckRecovery.DEFERRED -> reArm = true
                        StuckRecovery.GONE -> Unit
                    }
                }
                if (acted.isEmpty()) {
                    // Every orphan was cooling down. The signal that woke this pass is spent, so
                    // nothing would come back to retry them - re-arm once the cooldown is up,
                    // which is what keeps a slow page still being retried rather than dropped.
                    if (reArm) {
                        delay(STUCK_REDRIVE_COOLDOWN_MS)
                        stuckSignal.trySend(Unit)
                    }
                    continue
                }
                logcat(LogPriority.WARN) {
                    "Re-driving ${acted.size} stuck page(s): " +
                        acted.joinToString { "${it.page.chapter.chapter.id}/${it.page.index}=${it.state}" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Drives the live spin of the [ProgressPage] pineapple while a page is loading.
 * ProgressPage is time-based; without periodic invalidate the viewer would render
 * it once and the animation would freeze.
 */
internal fun WebGpuViewer.startProgressSpinner() {
    scope.launch {
        while (!isDestroyed) {
            try {
                val progress = currentPage?.imagePage as? ProgressPage
                if (progress == null) {
                    // Nothing to animate: suspend until a ProgressPage becomes current, rather
                    // than waking every 250ms for the rest of the session - 4 CPU wakeups a
                    // second the reader cannot use, for most of a long reading session. The
                    // flow emission wakes this on its own.
                    if (config.perfHud) syncPerfHud()
                    // Suspends without consuming CPU until a page whose image is a ProgressPage
                    // becomes current. first{} also passes straight through if the current page
                    // already qualifies, so the spin starts on the same iteration.
                    currentPageFlow.first { (it as? ViewerReaderPage)?.imagePage is ProgressPage }
                    continue
                }
                progress.invalidate()
                delay(33.milliseconds)
                if (config.perfHud) syncPerfHud()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed spin frame must not kill the loop, or the indicator freezes forever.
                delay(250.milliseconds)
            }
        }
    }
}

/**
 * Tears down everything a GPU device loss invalidated and rebuilds the shells around the page the
 * reader is on.
 *
 * Every texture the old device owned is gone, so nothing cached can be reused - not the decoded
 * images, not the tiles the library holds for them, not the shared spinner icon.
 */
internal fun WebGpuViewer.resetDecodedPagesAfterDeviceLoss() {
    try {
        ProgressPage.destroyPineappleTexture()
    } catch (_: Exception) {
    }
    synchronized(lock) {
        if (isDestroyed) return
        decodeQueue.clear()
        stuckSignal.trySend(Unit)
        val snapshot = pageCache.values.toList()
        snapshot.forEach { page ->
            // Releasing wantedByRender is what lets the renderer ask for these again once the new
            // device is drawing.
            if (page is ViewerReaderPage) {
                releasePageResources(page)
            } else {
                page.state = PageState.IDLE
                runCatching { page.imagePage.cleanup() }
            }
        }
        pageCache.clear()
        stuckRecords.clear()
        requeryRecords.clear()
        loneIndices.clear()
        continuousPageWindow.reset(null)
        val previous = currentPage
        currentPage = (previous as? ViewerReaderPage)?.page?.let { getPage(it, previous) }
            ?: (previous as? ViewerTransitionPage)?.let {
                getPage(it.prevChapter, it.nextChapter, previous)
            }
        currentPage?.let { preloadPages(it) }
    }
}
