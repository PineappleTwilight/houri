package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Execution(ExecutionMode.CONCURRENT)
class ChapterPreloadGuardTest {

    @Test
    fun `S1 - only the first begin for a chapter wins`() {
        val guard = ChapterPreloadGuard()

        guard.tryBegin("chapter-1") shouldBe true
        // Render frames, gestures and preload walks all hit the prev/next getters while a
        // chapter is loading; every duplicate must be rejected so only one retry loop runs.
        guard.tryBegin("chapter-1") shouldBe false
        guard.tryBegin("chapter-1") shouldBe false
    }

    @Test
    fun `S2 - end releases the chapter so a later retry can begin again`() {
        val guard = ChapterPreloadGuard()

        guard.tryBegin("chapter-1") shouldBe true
        guard.end("chapter-1")

        guard.tryBegin("chapter-1") shouldBe true
    }

    @Test
    fun `S3 - different chapters never block each other`() {
        val guard = ChapterPreloadGuard()

        guard.tryBegin("chapter-1") shouldBe true
        guard.tryBegin("chapter-2") shouldBe true
        guard.tryBegin("chapter-3") shouldBe true
    }

    @Test
    fun `S4 - end is idempotent and unknown keys are harmless`() {
        val guard = ChapterPreloadGuard()

        guard.end("never-begun")
        guard.tryBegin("chapter-1") shouldBe true
        guard.end("chapter-1")
        guard.end("chapter-1")

        guard.tryBegin("chapter-1") shouldBe true
    }

    @Test
    fun `S5 - concurrent begins across threads elect exactly one winner`() {
        val guard = ChapterPreloadGuard()
        val threads = 8
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val winners = (0 until threads).map {
                pool.submit<Boolean> {
                    latch.await()
                    guard.tryBegin("chapter-race")
                }
            }

            latch.countDown()
            val results = winners.map { it.get() }
            results.count { it } shouldBe 1

            guard.end("chapter-race")
            guard.tryBegin("chapter-race") shouldBe true
        } finally {
            pool.shutdownNow()
        }
    }
}
