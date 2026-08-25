package eu.kanade.tachiyomi.ui.reader

/**
 * Accumulates active reading time for webhook chapter-completion events.
 *
 * Time only accrues while a segment is open: [resume] opens one (no-op when already
 * open, so repeated onResume calls never restart the clock), [pause] closes it and
 * banks the elapsed span. Backgrounded time therefore pauses instead of being lost
 * or counted. The injectable [now] lambda keeps this class unit-testable.
 */
class WebhookReadingTimeTracker(private val now: () -> Long = System::currentTimeMillis) {

    private var elapsedMs = 0L
    private var segmentStart: Long? = null

    fun resume() {
        if (segmentStart == null) {
            segmentStart = now()
        }
    }

    fun pause() {
        segmentStart?.let { start ->
            elapsedMs += (now() - start).coerceAtLeast(0)
        }
        segmentStart = null
    }

    fun reset() {
        elapsedMs = 0L
        segmentStart = null
    }

    fun totalSeconds(): Long {
        val openSegmentMs = segmentStart?.let { now() - it } ?: 0L
        return ((elapsedMs + openSegmentMs) / 1000).coerceAtLeast(0)
    }

    fun consumeTotalSeconds(): Long = totalSeconds().also { reset() }
}
