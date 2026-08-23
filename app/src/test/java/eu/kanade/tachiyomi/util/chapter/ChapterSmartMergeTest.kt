package eu.kanade.tachiyomi.util.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter

@Execution(ExecutionMode.CONCURRENT)
class ChapterSmartMergeTest {

    private fun chapter(id: Long, number: Double, scanlator: String?) = Chapter.create().copy(
        id = id,
        chapterNumber = number,
        scanlator = scanlator,
    )

    @Test
    fun `applies global priority keeping one chapter per number`() {
        val chapters = listOf(
            chapter(1, 5.0, "GroupB"),
            chapter(2, 5.0, "GroupA"),
            chapter(3, 6.0, "GroupB"),
        )

        val result = chapters.applyScanlatorPriority(listOf("GroupA", "GroupB"), emptySet())

        result.map { it.id } shouldBe listOf(2L, 3L)
    }

    @Test
    fun `blacklisted candidate falls back to next priority`() {
        val chapters = listOf(
            chapter(1, 5.0, "GroupA"),
            chapter(2, 5.0, "GroupB"),
        )
        val blacklist = setOf(scanlatorBlacklistKey(5.0, "GroupA"))

        val result = chapters.applyScanlatorPriority(listOf("GroupA", "GroupB"), blacklist)

        result.map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `number with all candidates blacklisted is hidden`() {
        val chapters = listOf(chapter(1, 5.0, "GroupA"))

        val result = chapters.applyScanlatorPriority(
            listOf("GroupA"),
            setOf(scanlatorBlacklistKey(5.0, "GroupA")),
        )

        result shouldBe emptyList()
    }

    @Test
    fun `range rule pins its scanlator over global order`() {
        val chapters = listOf(
            chapter(1, 5.0, "GroupA"),
            chapter(2, 5.0, "GroupB"),
            chapter(3, 10.0, "GroupA"),
            chapter(4, 10.0, "GroupB"),
        )

        val result = chapters.applyScanlatorPriority(
            priority = listOf("GroupA", "GroupB"),
            blacklistedChapters = emptySet(),
            rangeRules = listOf("5.0:7.5:GroupB"),
        )

        // 5.0 covered by range -> GroupB wins despite global order; 10.0 stays GroupA
        result.map { it.id } shouldBe listOf(2L, 3L)
    }

    @Test
    fun `falls back to global priority when preferred scanlator missing for a ranged number`() {
        val chapters = listOf(chapter(1, 5.0, "GroupA"))

        val result = chapters.applyScanlatorPriority(
            priority = listOf("GroupA", "GroupB"),
            blacklistedChapters = emptySet(),
            rangeRules = listOf("1:10:GroupC"),
        )

        result.map { it.id } shouldBe listOf(1L)
    }

    @Test
    fun `last matching rule wins on overlap`() {
        val chapters = listOf(
            chapter(1, 5.0, "GroupA"),
            chapter(2, 5.0, "GroupB"),
        )

        val result = chapters.applyScanlatorPriority(
            priority = emptyList(),
            blacklistedChapters = emptySet(),
            rangeRules = listOf("1:10:GroupA", "4:6:GroupB"),
        )

        result.map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `unnumbered chapters are never deduplicated`() {
        val chapters = listOf(
            chapter(1, -1.0, "GroupA"),
            chapter(2, -1.0, "GroupB"),
        )

        val result = chapters.applyScanlatorPriority(listOf("GroupB"), emptySet())

        result.map { it.id } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `rule encoding round trips through parsing`() {
        val rule = ScanlatorRangeRule(from = 1.5, to = 12.0, scanlator = "Weird:Name")

        val parsed = parseScanlatorRangeRule(encodeScanlatorRangeRule(rule))

        parsed shouldBe rule
    }

    @Test
    fun `invalid rules fail to parse`() {
        parseScanlatorRangeRule("nonsense") shouldBe null
        parseScanlatorRangeRule("a:b:c") shouldBe null
        parseScanlatorRangeRule("10:5:GroupA") shouldBe null
    }
}
