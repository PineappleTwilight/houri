package eu.kanade.tachiyomi.data.track.mangabaka

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class MangaBakaMetadataTest {

    @Test
    fun `missing tags map to empty list`() {
        val search = TrackSearch.create(0L).apply {
            title = "Test Manga"
            tags = emptyList()
            publishing_status = "completed"
        }

        val metadata = search.toMangaMetadata(remoteId = 42L)

        metadata.tags shouldBe emptyList()
    }

    @Test
    fun `tags and status are mapped`() {
        val search = TrackSearch.create(0L).apply {
            title = "Test Manga"
            tags = listOf("Action", "Drama")
            publishing_status = "releasing"
        }

        val metadata = search.toMangaMetadata(remoteId = 42L)

        metadata.tags shouldBe listOf("Action", "Drama")
        metadata.status shouldBe SManga.ONGOING.toLong()
    }
}
