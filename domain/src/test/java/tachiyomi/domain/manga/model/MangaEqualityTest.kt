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
class MangaEqualityTest {

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
    fun `S1 - mangas built before and after a custom-title edit are not equal`() {
        val beforeEdit = manga(id = 42L, favorite = true, title = "Source Title")
        CustomMangaInfoLookup.resolve = { id ->
            if (id == 42L) CustomMangaInfo(id = id, title = "Cleaned") else null
        }
        val afterEdit = manga(id = 42L, favorite = true, title = "Source Title")

        // Library hot-reload relies on structural inequality here: identical rows
        // re-queried after a custom-info change must still compare unequal.
        (beforeEdit == afterEdit) shouldBe false
    }

    @Test
    fun `S2 - hash code distinguishes mangas that differ only in resolved title`() {
        val beforeEdit = manga(id = 42L, favorite = true, title = "Source Title")
        CustomMangaInfoLookup.resolve = { id ->
            if (id == 42L) CustomMangaInfo(id = id, title = "Cleaned") else null
        }
        val afterEdit = manga(id = 42L, favorite = true, title = "Source Title")

        (beforeEdit.hashCode() != afterEdit.hashCode()) shouldBe true
    }

    @Test
    fun `S3 - mangas built under identical resolution remain structurally equal`() {
        CustomMangaInfoLookup.resolve = { id ->
            if (id == 42L) CustomMangaInfo(id = id, title = "Cleaned") else null
        }
        val a = manga(id = 42L, favorite = true, title = "Source Title")
        val b = manga(id = 42L, favorite = true, title = "Source Title")

        (a == b) shouldBe true
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun `S4 - non-favorite mangas ignore custom info in equality as in resolution`() {
        var consulted = false
        CustomMangaInfoLookup.resolve = { _ ->
            consulted = true
            CustomMangaInfo(id = 42L, title = "Cleaned")
        }
        val a = manga(id = 42L, favorite = false, title = "Source Title")
        val b = manga(id = 42L, favorite = false, title = "Source Title")

        (a == b) shouldBe true
        consulted shouldBe false
    }
}
