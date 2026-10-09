// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

// Stage-by-stage tracing for the decode worker, plus the watchdog that reports a stall.
//
// The decode worker is one thread with every other page queued behind it, so a page that never
// returns stops the whole chapter. The comment on that thread says which stage wedged; this is the
// thing that actually finds out, and it is built for the failure rather than for the happy path.
//
// The distinction that shapes it: "decode started" and "decode finished" brackets are useless here.
// A stall inside the bracket localises the page and not the culprit, and the culprit is the entire
// question. So every step that could plausibly be the one that never returns gets its own stage
// transition, and the watchdog reports the stage it is sitting in along with what it knows about the
// page so far. The last transition before a stall is the answer, which is why the stages are logged
// as they are entered rather than summarised at the end.
//
// Everything here is observational. No stage hook can alter the decode, fail it, or slow it
// materially, because a tracer that can change the thing it measures is worse than no tracer.

/** How long a page may sit in one decode stage before it is reported as stalled. */
internal const val DECODE_STALL_WARN_MS = 10_000L

/** How often the watchdog re-reports a stall that is still ongoing. */
private const val DECODE_STALL_REPEAT_MS = 10_000L

/** How often the watchdog looks. */
private const val DECODE_WATCHDOG_TICK_MS = 1_000L

/**
 * One page's decode in progress, with the stage it is currently in.
 *
 * Every field is volatile because the decode worker writes them from the decode thread while the
 * watchdog reads them from its own. The object is handed over as a whole reference by the viewer, so
 * a page in flight is always fully published before the watchdog can see it.
 */
internal class DecodeTrace(
    val chapterId: Long,
    val pageIndex: Int,
    val startedAtMs: Long,
) {
    @Volatile
    var stage: String = "dequeued"

    @Volatile
    var stageSinceMs: Long = startedAtMs

    @Volatile
    var finished: Boolean = false

    /** Bytes read, once known; -1 before that. Reported with a stall because size predicts hang. */
    @Volatile
    var bytes: Int = -1

    /** Format the decoder reported, once known. */
    @Volatile
    var format: String? = null

    /** Dimensions of the first frame, once known. */
    @Volatile
    var dimensions: String? = null

    /** Whether an extension replaced the page bytes before decode - the one native step before the decoder. */
    @Volatile
    var transformed: Boolean? = null

    private var lastReportedStage: String? = null
    private var lastReportedAtMs: Long = 0L

    /** Moves to [next], logging the transition and how long the previous stage took. */
    fun enter(next: String, detail: String? = null, nowMs: Long = System.currentTimeMillis()) {
        val previous = stage
        val heldFor = nowMs - stageSinceMs
        stage = next
        stageSinceMs = nowMs
        logcat(LogPriority.DEBUG) {
            "DecodeTrace ch=$chapterId/i=$pageIndex ${describe()} prev=$previous held=${heldFor}ms" +
                (detail?.let { " $it" } ?: "")
        }
    }

    /**
     * Reports a stall if this page has been in its current stage for too long.
     *
     * Re-reports on a fixed interval rather than every tick, so a page stuck for an hour is a
     * repeating line rather than 3600 of them, while a page that recovers between two ticks says
     * nothing at all.
     *
     * [queueSummary] is passed in rather than read here: what is behind this page in the queue lives
     * on the viewer, and a decode worker that is wedged in native code cannot be asked for it.
     *
     * @return true when a stall line was emitted.
     */
    fun reportStallIfDue(nowMs: Long, queueSummary: String): Boolean {
        if (finished) return false
        val heldFor = nowMs - stageSinceMs
        if (heldFor < DECODE_STALL_WARN_MS) return false
        if (stage == lastReportedStage && nowMs - lastReportedAtMs < DECODE_STALL_REPEAT_MS) return false
        lastReportedStage = stage
        lastReportedAtMs = nowMs
        logcat(LogPriority.WARN) {
            "DecodeTrace STALL ch=$chapterId/i=$pageIndex stage=$stage stuck=${heldFor}ms total=" +
                "${nowMs - startedAtMs}ms ${describe()} queued=$queueSummary"
        }
        return true
    }

    /** Marks the decode finished and logs how long the whole thing took. */
    fun complete(outcome: String, nowMs: Long = System.currentTimeMillis()) {
        finished = true
        logcat(LogPriority.DEBUG) {
            "DecodeTrace ch=$chapterId/i=$pageIndex $outcome total=${nowMs - startedAtMs}ms ${describe()}"
        }
    }

    /** A compact description of what is known about the page, for reuse across the log lines. */
    private fun describe(): String =
        "bytes=$bytes fmt=${format ?: "?"} dims=${dimensions ?: "?"} transformed=${transformed ?: "?"}"
}

/**
 * Records a stage transition on the page the decode worker is currently holding.
 *
 * No-op once the page has been handed back, so the late stages of a decode whose page was already
 * torn down do not resurrect a trace the watchdog is no longer watching.
 */
internal fun WebGpuViewer.traceStage(
    trace: DecodeTrace?,
    stage: String,
    detail: String? = null,
) {
    trace?.enter(stage, detail)
}

/**
 * Watches for the decode worker sitting in one stage for too long.
 *
 * The worker cannot report its own stall: it is the thing that is stuck, and its own logging cannot
 * run while it is stuck. So the watch has to come from outside, which is all this is - it reads the
 * published trace fields and reports what it finds. It holds no lock while doing so, because the
 * thing it is watching is usually holding that lock.
 */
internal fun WebGpuViewer.startDecodeStallWatchdog() {
    scope.launch {
        try {
            while (!isDestroyed) {
                delay(DECODE_WATCHDOG_TICK_MS)
                val summary = decodeQueueSummary()
                activeDecode?.reportStallIfDue(System.currentTimeMillis(), summary)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A watchdog that dies takes the only diagnosis with it, so a failed tick restarts the
            // loop rather than ending it. Cheap, and it only runs once a second.
        }
    }
}

/** What is queued behind the page being decoded, so a stall reports how much of the chapter is stuck. */
private fun WebGpuViewer.decodeQueueSummary(): String {
    val queued = synchronized(lock) { decodeQueue.map { "${it.page.chapter.chapter.id}/${it.page.index}" } }
    return if (queued.isEmpty()) "none" else "${queued.size} [${queued.take(6).joinToString(",")}]"
}

/**
 * Logs a one-line summary of how the decode queue is doing.
 *
 * Worth having independently of the watchdog: a queue that is deep but draining is fine, and a queue
 * that is not draining at all is the failure being chased, and the depth alone distinguishes them.
 */
internal fun WebGpuViewer.logQueueDepth(reason: String) {
    val depth = synchronized(lock) { decodeQueue.size }
    logcat(LogPriority.DEBUG) { "DecodeQueue $reason depth=$depth active=${activeDecode?.label() ?: "idle"}" }
}

private fun DecodeTrace.label(): String = "ch=$chapterId/i=$pageIndex@$stage"

// KMK <--
