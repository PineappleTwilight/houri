package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class WebGpuSpreadMathTest {

    @Test
    fun `S1 - factor scales shorter up to taller`() {
        spreadHeightMatchFactor(shorterHeight = 1000, tallerHeight = 1500) shouldBe 1.5f
    }

    @Test
    fun `S2 - factor is noop for equal heights`() {
        spreadHeightMatchFactor(shorterHeight = 1200, tallerHeight = 1200).shouldBeNull()
    }

    @Test
    fun `S3 - factor never shrinks taller side and never divides by zero`() {
        // Inverted order: first arg is not the shorter side -> noop, never a <1 factor.
        spreadHeightMatchFactor(shorterHeight = 1500, tallerHeight = 1000).shouldBeNull()
        // Zero-noop: destroyed/placeholder dims must not divide by zero.
        spreadHeightMatchFactor(shorterHeight = 0, tallerHeight = 1500).shouldBeNull()
        spreadHeightMatchFactor(shorterHeight = 1000, tallerHeight = 0).shouldBeNull()
        spreadHeightMatchFactor(shorterHeight = 0, tallerHeight = 0).shouldBeNull()
        spreadHeightMatchFactor(shorterHeight = -4, tallerHeight = 1500).shouldBeNull()
    }

    @Test
    fun `S4 - target height clamps to 1 dot dot 8192`() {
        clampSpreadDim(9000) shouldBe 8192
        clampSpreadDim(8192) shouldBe 8192
        clampSpreadDim(4000) shouldBe 4000
        clampSpreadDim(1) shouldBe 1
        clampSpreadDim(0) shouldBe 1
        clampSpreadDim(-10) shouldBe 1
    }

    @Test
    fun `S5 - scaled width preserves aspect and clamps`() {
        scaledSpreadWidth(srcWidth = 800, srcHeight = 1000, targetHeight = 1500) shouldBe 1200
        scaledSpreadWidth(srcWidth = 800, srcHeight = 1000, targetHeight = 20000) shouldBe 8192
        // Zero-noop: no divide by zero, no 0-width upload (gralloc 0x3b).
        scaledSpreadWidth(srcWidth = 0, srcHeight = 1000, targetHeight = 1500).shouldBeNull()
        scaledSpreadWidth(srcWidth = 800, srcHeight = 0, targetHeight = 1500).shouldBeNull()
        scaledSpreadWidth(srcWidth = 800, srcHeight = 1000, targetHeight = 0).shouldBeNull()
    }

    @Test
    fun `S6 - first-spread fake resolves the shorter side with the taller target`() {
        val rightShort = resolveSpreadHeightMatch(
            leftWidth = 800,
            leftHeight = 1200,
            rightWidth = 800,
            rightHeight = 1000,
        )
        rightShort.shouldNotBeNull()
        rightShort.shorterIsLeft shouldBe false
        rightShort.targetHeight shouldBe 1200

        val leftShort = resolveSpreadHeightMatch(
            leftWidth = 800,
            leftHeight = 1000,
            rightWidth = 800,
            rightHeight = 1200,
        )
        leftShort.shouldNotBeNull()
        leftShort.shorterIsLeft shouldBe true
        leftShort.targetHeight shouldBe 1200
    }

    @Test
    fun `S7 - evicted-bytes fake never schedules`() {
        val plan = resolveSpreadHeightMatch(
            leftWidth = 800,
            leftHeight = 1000,
            rightWidth = 800,
            rightHeight = 1200,
        ).shouldNotBeNull()
        // Evicted (bytes freed): terminal noop, the next decode re-arms via fresh bytes.
        shouldAttemptSpreadRescale(hasBytes = false, rescaleInFlight = false, plan = plan) shouldBe false
        // Already scheduled: no duplicate rescale.
        shouldAttemptSpreadRescale(hasBytes = true, rescaleInFlight = true, plan = plan) shouldBe false
        // No plan at all: never schedule.
        shouldAttemptSpreadRescale(hasBytes = true, rescaleInFlight = false, plan = null) shouldBe false
        shouldAttemptSpreadRescale(hasBytes = false, rescaleInFlight = false, plan = null) shouldBe false
        // Live case: bytes present, nothing in flight.
        shouldAttemptSpreadRescale(hasBytes = true, rescaleInFlight = false, plan = plan) shouldBe true
    }

    @Test
    fun `S8 - e-ink-resume fake keeps the taller height and never shrinks`() {
        // Resume with both sides at the taller height: already matched, a hard noop.
        resolveSpreadHeightMatch(
            leftWidth = 800,
            leftHeight = 1600,
            rightWidth = 800,
            rightHeight = 1600,
        ).shouldBeNull()
        // Zero dims after resume (destroyed texture): noop, not a retry storm.
        resolveSpreadHeightMatch(
            leftWidth = 0,
            leftHeight = 0,
            rightWidth = 800,
            rightHeight = 1600,
        ).shouldBeNull()
        // Sub-gralloc sides are not viable rescale sources/targets.
        isSpreadSideViable(width = 800, height = 1600) shouldBe true
        isSpreadSideViable(width = 7, height = 1600) shouldBe false
        isSpreadSideViable(width = 800, height = 7) shouldBe false
        isSpreadSideViable(width = 0, height = 0) shouldBe false
    }
}
