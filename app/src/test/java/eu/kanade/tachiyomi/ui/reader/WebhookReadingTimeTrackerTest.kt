package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class WebhookReadingTimeTrackerTest {

    private fun tracker() = WebhookReadingTimeTracker(now = { clockMs })

    private var clockMs = 0L

    @Test
    fun `S1 - backgrounded span is paused instead of counted`() {
        val tracker = tracker()
        tracker.resume()

        clockMs += 600_000 // read 10 min
        tracker.pause() // tab out

        clockMs += 300_000 // 5 min in background - must not count

        tracker.resume()
        clockMs += 300_000 // read 5 more min

        tracker.totalSeconds() shouldBe 900L
    }

    @Test
    fun `S2 - resume while already running keeps the original segment start`() {
        val tracker = tracker()
        tracker.resume()

        clockMs += 100_000
        tracker.resume() // duplicate resume must not restart the segment

        clockMs += 100_000
        tracker.pause()

        tracker.totalSeconds() shouldBe 200L
    }

    @Test
    fun `S3 - pause without an open segment is a no-op`() {
        val tracker = tracker()

        clockMs += 60_000
        tracker.pause()
        tracker.pause()

        tracker.totalSeconds() shouldBe 0L
    }

    @Test
    fun `S4 - reset discards accumulated time`() {
        val tracker = tracker()
        tracker.resume()
        clockMs += 120_000
        tracker.pause()

        tracker.reset()

        tracker.totalSeconds() shouldBe 0L
    }

    @Test
    fun `S5 - consume returns the total and starts fresh for the next chapter`() {
        val tracker = tracker()
        tracker.resume()
        clockMs += 90_000

        val consumed = tracker.consumeTotalSeconds()

        consumed shouldBe 90L
        clockMs += 45_000
        tracker.totalSeconds() shouldBe 0L
    }

    @Test
    fun `S6 - total includes an open running segment`() {
        val tracker = tracker()
        tracker.resume()
        clockMs += 30_000

        tracker.totalSeconds() shouldBe 30L
    }

    @Test
    fun `S7 - wall clock going backwards cannot produce negative accumulation`() {
        val tracker = tracker()
        tracker.resume()

        clockMs -= 500_000 // pathological clock adjustment while reading
        tracker.pause()

        clockMs += 10_000
        tracker.resume()
        clockMs += 20_000

        tracker.totalSeconds() shouldBe 20L
    }
}
