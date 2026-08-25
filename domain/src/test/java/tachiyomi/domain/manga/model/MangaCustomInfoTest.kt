package tachiyomi.domain.manga.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Mutates the process-wide [CustomMangaInfoLookup]; must not run concurrently with
 * other tests touching it.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MangaCustomInfoTest {

    @BeforeEach
    fun reset() {
        CustomMangaInfoLookup.resolve = null
    }

    @AfterEach
    fun tearDown() {
        CustomMangaInfoLookup.resolve = null
    }

    private fun manga(id: Long, favorite: Boolean, title: String) = Manga.create().copy(
        id = id,
        favorite = favorite,
        ogTitle = title,
    )

    @Test
    fun `S1 - constructing a favorite manga without an installed resolver falls back to og values`() {
        val m = manga(id = 42L, favorite = true, title = "Source Title")

        // Must not throw (legacy Injekt lookup crashed here outside the DI container).
        m.title shouldBe "Source Title"
        m.description shouldBe null
    }

    @Test
    fun `S2 - installed resolver supplies edited metadata for favorite mangas`() {
        CustomMangaInfoLookup.resolve = { id ->
            if (id == 42L) {
                CustomMangaInfo(id = id, title = "User Edit")
            } else {
                null
            }
        }

        val edited = manga(id = 42L, favorite = true, title = "Source Title")
        val other = manga(id = 7L, favorite = true, title = "Source Title")

        edited.title shouldBe "User Edit"
        // Per-manga lookups only affect their own entry.
        other.title shouldBe "Source Title"
        // Fields absent from the custom entry fall back to source values.
        edited.description shouldBe null
    }

    @Test
    fun `S3 - non-favorite mangas never consult custom info`() {
        var consulted = false
        CustomMangaInfoLookup.resolve = { _ ->
            consulted = true
            CustomMangaInfo(id = 42L, title = "User Edit")
        }

        val m = manga(id = 42L, favorite = false, title = "Source Title")

        m.title shouldBe "Source Title"
        consulted shouldBe false
    }
}
