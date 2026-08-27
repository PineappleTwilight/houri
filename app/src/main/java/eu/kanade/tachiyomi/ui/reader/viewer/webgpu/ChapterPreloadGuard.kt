// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/**
 * Per-chapter dedup guard for WebGPU chapter preload retries.
 *
 * The viewer's `prev`/`next` page getters are hot paths: they run from every render
 * snapshot (the pager library re-resolves page 0 and its neighbors on each invalidation),
 * from flick/have-next checks during gestures, and from every preload walk. When the
 * adjacent chapter is not loaded yet, each of those hits used to spawn its own
 * preload-and-retry loop, so a single approach toward a chapter boundary could start
 * many concurrent `ChapterLoader.loadChapter` runs (DB + download checks + network page
 * list) plus up to five seconds of polling each - visible as freezing/choppiness exactly
 * at chapter transitions.
 *
 * This guard elects exactly one retry loop per chapter key; [end] releases the key so a
 * later attempt (e.g. after a transient failure or the 5s give-up) can retry. All
 * operations are thread-safe: callers span the main thread and background dispatchers.
 */
class ChapterPreloadGuard {

    private val inFlight = HashSet<String>()

    /**
     * Marks [key] as being preloaded. Returns true for the caller that won the right to
     * run, false for every concurrent duplicate while the key is in flight.
     */
    @Synchronized
    fun tryBegin(key: String): Boolean = inFlight.add(key)

    @Synchronized
    fun isInFlight(key: String): Boolean = key in inFlight

    /** Releases [key]. Idempotent and safe for keys that never began. */
    @Synchronized
    fun end(key: String) {
        inFlight.remove(key)
    }

    @Synchronized
    fun tryBeginOrRequeueIfStale(key: String, isStale: () -> Boolean): Boolean {
        if (key in inFlight && isStale()) {
            inFlight.remove(key)
        }
        return inFlight.add(key)
    }
}
// KMK <--
