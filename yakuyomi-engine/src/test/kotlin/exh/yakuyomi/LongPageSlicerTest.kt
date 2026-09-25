package exh.yakuyomi

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class LongPageSlicerTest {

    @Test
    fun `short pages never slice`() {
        LongPageSlicer.shouldSlice(800, 3599) shouldBe false
        LongPageSlicer.shouldSlice(800, 100) shouldBe false
    }

    @Test
    fun `wide pages never slice even when tall`() {
        LongPageSlicer.shouldSlice(4001, 12000) shouldBe false
        LongPageSlicer.shouldSlice(2000, 4000) shouldBe false
    }

    @Test
    fun `gate boundary slices`() {
        LongPageSlicer.shouldSlice(800, 3600) shouldBe true
        LongPageSlicer.shouldSlice(1000, 2300) shouldBe false
        LongPageSlicer.shouldSlice(1565, 3600) shouldBe true
        LongPageSlicer.shouldSlice(1566, 3600) shouldBe false
        LongPageSlicer.shouldSlice(4000, 12000) shouldBe true
    }

    @Test
    fun `zero dimensions never slice`() {
        LongPageSlicer.shouldSlice(0, 12000) shouldBe false
        LongPageSlicer.shouldSlice(800, 0) shouldBe false
    }

    @Test
    fun `wider gutter band wins at target distance`() {
        val near = LongPageSlicer.scoreBand(60, LongPageSlicer.TARGET_SLICE_HEIGHT)
        val wide = LongPageSlicer.scoreBand(200, LongPageSlicer.TARGET_SLICE_HEIGHT)
        (wide > near) shouldBe true
    }

    @Test
    fun `nearer band wins despite narrower width`() {
        val narrowNear = LongPageSlicer.scoreBand(100, LongPageSlicer.TARGET_SLICE_HEIGHT)
        val wideFar = LongPageSlicer.scoreBand(200, LongPageSlicer.TARGET_SLICE_HEIGHT + 1000)
        (narrowNear > wideFar) shouldBe true
    }

    @Test
    fun `best cut is middle of winning band`() {
        val cut = LongPageSlicer.selectBestCut(listOf(IntRange(4700, 4799), IntRange(3600, 3654)), 0)
        cut shouldBe 4750
    }

    @Test
    fun `bands under minimum height are ignored`() {
        LongPageSlicer.selectBestCut(listOf(IntRange(4700, 4753)), 0) shouldBe -1
        LongPageSlicer.selectBestCut(emptyList(), 0) shouldBe -1
    }

    @Test
    fun `5000px page requires a cut and builds two slices`() {
        val cuts = LongPageSlicer.planCutsForHeight(5000) { 4800 }
        cuts shouldBe listOf(3600)
        val slices = LongPageSlicer.buildSlices(5000, cuts)
        slices.size shouldBe 2
        slices.forEach { (it.height > 0) shouldBe true }
        slices.first().y shouldBe 0
        slices.last().y + slices.last().height shouldBe 5000
        LongPageSlicer.ownedStart(1, slices) shouldBe LongPageSlicer.ownedEndExclusive(0, slices)
    }

    @Test
    fun `3600px page plans no cuts and stays a single slice`() {
        LongPageSlicer.planCutsForHeight(3600) { _ -> error("finder must not run") } shouldBe emptyList<Int>()
        LongPageSlicer.buildSlices(3600, emptyList()) shouldBe listOf(LongPageSlicer.Slice(0, 3600))
    }

    @Test
    fun `healthy tail keeps the target cut without rebalancing`() {
        LongPageSlicer.planCutsForHeight(7000) { pos -> pos + 4800 } shouldBe listOf(4800)
    }

    @Test
    fun `slices overlap by 128 below each cut`() {
        val slices = LongPageSlicer.buildSlices(10000, listOf(4800))
        slices.size shouldBe 2
        slices[0] shouldBe LongPageSlicer.Slice(0, 4928)
        slices[1] shouldBe LongPageSlicer.Slice(4800, 5200)
    }

    @Test
    fun `no cuts yields single full slice`() {
        LongPageSlicer.buildSlices(5000, emptyList()) shouldBe listOf(LongPageSlicer.Slice(0, 5000))
    }

    @Test
    fun `tiny tail merges into previous slice`() {
        LongPageSlicer.mergeTailCuts(listOf(4800, 9700), 10000) shouldBe listOf(4800)
        LongPageSlicer.buildSlices(10000, LongPageSlicer.mergeTailCuts(listOf(4800, 9700), 10000)) shouldBe
            LongPageSlicer.buildSlices(10000, listOf(4800))
    }

    @Test
    fun `healthy tail keeps its cut`() {
        LongPageSlicer.mergeTailCuts(listOf(4800, 8000), 10000) shouldBe listOf(4800, 8000)
    }

    @Test
    fun `upper slice owns the overlap band`() {
        val slices = LongPageSlicer.buildSlices(10000, listOf(4800))
        LongPageSlicer.ownsCenter(0, slices, 4900f) shouldBe true
        LongPageSlicer.ownsCenter(1, slices, 4900f) shouldBe false
        LongPageSlicer.ownsCenter(1, slices, 4950f) shouldBe true
        LongPageSlicer.ownsCenter(0, slices, 4950f) shouldBe false
    }

    @Test
    fun `owned cores partition the page without gaps`() {
        val slices = LongPageSlicer.buildSlices(10000, listOf(4800))
        LongPageSlicer.ownedStart(0, slices) shouldBe 0
        LongPageSlicer.ownedEndExclusive(0, slices) shouldBe 4928
        LongPageSlicer.ownedStart(1, slices) shouldBe 4928
        LongPageSlicer.ownedEndExclusive(1, slices) shouldBe 10000
    }
}
