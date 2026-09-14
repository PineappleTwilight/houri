package tachiyomi.domain.source.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

// KMK -->
@Execution(ExecutionMode.CONCURRENT)
class AnizenMultiFeedTabsTest {

    private fun feed(id: Long, order: Long, source: Long = 1L, savedSearch: Long? = null): FeedSavedSearch {
        return FeedSavedSearch(
            id = id,
            source = source,
            savedSearch = savedSearch,
            global = true,
            feedOrder = order,
        )
    }

    @Test
    fun `N feeds map to N tab models`() {
        val tabs = AnizenMultiFeedTabs.buildTabs(
            listOf(feed(1, 2), feed(2, 0), feed(3, 1)),
        )

        tabs.size shouldBe 3
        tabs.map { it.feedId } shouldBe listOf(2, 3, 1)
    }

    @Test
    fun `tabs follow feed order`() {
        val tabs = AnizenMultiFeedTabs.buildTabs(
            listOf(feed(10, 5), feed(20, 3)),
        )

        tabs.map { it.feedId } shouldBe listOf(20, 10)
    }

    @Test
    fun `per-tab state is preserved across updates`() {
        val before = AnizenMultiFeedTabs.buildTabs(listOf(feed(1, 0), feed(2, 1)))
        val previous = mapOf(1L to "a", 2L to "b", 99L to "stale")

        val after = AnizenMultiFeedTabs.buildTabs(listOf(feed(2, 1), feed(3, 2)))
        val retained = AnizenMultiFeedTabs.retainPerTabState(after, previous)

        // Feed 2 survives, removed feed 1 and unknown 99 are dropped, new feed 3 starts unloaded.
        retained shouldBe mapOf(2L to "b")
        before.size shouldBe 2
    }

    @Test
    fun `selected index is clamped when feeds change`() {
        val tabs = AnizenMultiFeedTabs.buildTabs(listOf(feed(1, 0), feed(2, 1)))

        AnizenMultiFeedTabs.selectedIndex(tabs, 2L) shouldBe 1
        AnizenMultiFeedTabs.selectedIndex(tabs, 99L) shouldBe 0
        AnizenMultiFeedTabs.selectedIndex(tabs, null) shouldBe 0
    }

    @Test
    fun `empty feeds map to empty state`() {
        val tabs = AnizenMultiFeedTabs.buildTabs(emptyList())

        AnizenMultiFeedTabs.isEmpty(tabs) shouldBe true
        AnizenMultiFeedTabs.selectedIndex(tabs, null) shouldBe 0
        AnizenMultiFeedTabs.retainPerTabState(tabs, mapOf(1L to "a")) shouldBe emptyMap()
    }

    @Test
    fun `library ordering sorts alphabetically when requested`() {
        val tabs = AnizenMultiFeedTabs.buildTabs(listOf(feed(1, 0), feed(2, 1)))
        val titles = mapOf(1L to "Zeta", 2L to "Alpha")

        val ordered = AnizenMultiFeedTabs.orderTabs(tabs, titles, alphabetical = true)
        ordered.map { it.feedId } shouldBe listOf(2, 1)

        val feedOrdered = AnizenMultiFeedTabs.orderTabs(tabs, titles, alphabetical = false)
        feedOrdered.map { it.feedId } shouldBe listOf(1, 2)
    }
}
// KMK <--
