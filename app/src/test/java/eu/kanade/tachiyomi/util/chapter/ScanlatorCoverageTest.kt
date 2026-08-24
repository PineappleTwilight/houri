package eu.kanade.tachiyomi.util.chapter

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class ScanlatorCoverageTest {

    @Test
    fun `returns null for empty or all-invalid numbers`() {
        scanlatorCoverage(emptyList()).shouldBeNull()
        scanlatorCoverage(listOf(0.0, -1.0)).shouldBeNull()
    }

    @Test
    fun `merges consecutive whole numbers into ranges`() {
        scanlatorCoverage(listOf(1.0, 2.0, 3.0, 4.0, 5.0)) shouldBe ScanlatorCoverage(
            groups = listOf("1–5"),
            hiddenCount = 0,
        )
    }

    @Test
    fun `keeps standalone entries across gaps`() {
        scanlatorCoverage(listOf(1.0, 2.0, 5.0, 8.0, 9.0, 10.0)) shouldBe ScanlatorCoverage(
            groups = listOf("1–2", "5", "8–10"),
            hiddenCount = 0,
        )
    }

    @Test
    fun `formats decimal numbers without merging into integer runs`() {
        scanlatorCoverage(listOf(4.5, 5.0)) shouldBe ScanlatorCoverage(
            groups = listOf("4.5", "5"),
            hiddenCount = 0,
        )
    }

    @Test
    fun `deduplicates and ignores unsorted input`() {
        scanlatorCoverage(listOf(3.0, 1.0, 2.0, 2.0)) shouldBe ScanlatorCoverage(
            groups = listOf("1–3"),
            hiddenCount = 0,
        )
    }

    @Test
    fun `caps displayed groups and reports hidden count`() {
        val alternating = (1..29 step 2).map { it.toDouble() }
        scanlatorCoverage(alternating) shouldBe ScanlatorCoverage(
            groups = (1..19 step 2).map { it.toString() },
            hiddenCount = 5,
        )
    }
}
