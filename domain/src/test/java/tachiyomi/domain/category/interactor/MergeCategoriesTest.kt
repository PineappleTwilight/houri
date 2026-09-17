package tachiyomi.domain.category.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@Execution(ExecutionMode.CONCURRENT)
class MergeCategoriesTest {

    private val categoryRepository: CategoryRepository = mockk()
    private val mangaRepository: MangaRepository = mockk()
    private val mergeCategories = MergeCategories(categoryRepository, mangaRepository)

    private fun category(id: Long, name: String, parentId: Long = 1L, order: Long = 0L): Category {
        return Category(id = id, name = name, order = order, flags = 0L, hidden = false, parentId = parentId)
    }

    private fun libraryManga(id: Long, categories: List<Long>): LibraryManga {
        val manga = Manga.create().copy(id = id)
        return LibraryManga(
            manga = manga,
            categories = categories,
            totalChapters = 0L,
            readCount = 0L,
            bookmarkCount = 0L,
            bookmarkReadCount = 0L,
            chapterFlags = 0L,
            latestUpload = 0L,
            chapterFetchedAt = 0L,
            lastRead = 0L,
        )
    }

    @Test
    fun `reassigns manga reparents children deletes sources and compacts order`() = runTest {
        val parent = category(1L, "Hentai", parentId = 0L)
        val target = category(2L, "Kawakami Masaki")
        val source = category(3L, "Kawakami_Masaki")
        val child = category(4L, "Child", parentId = 3L)
        coEvery { categoryRepository.getAll() } returnsMany listOf(
            listOf(parent, target, source, child),
            listOf(parent, target, child),
        )
        coEvery { mangaRepository.getLibraryManga() } returns listOf(
            libraryManga(10L, listOf(3L)),
            libraryManga(11L, listOf(2L, 3L)),
        )
        coEvery { mangaRepository.setMangaCategories(any(), any()) } just runs
        coEvery { categoryRepository.updatePartial(any<CategoryUpdate>()) } just runs
        coEvery { categoryRepository.updatePartial(any<List<CategoryUpdate>>()) } just runs
        coEvery { categoryRepository.delete(any()) } just runs

        val result = mergeCategories.await(targetId = 2L, sourceIds = listOf(3L))

        result shouldBe MergeCategories.Result.Success
        coVerify { mangaRepository.setMangaCategories(10L, listOf(2L)) }
        coVerify { mangaRepository.setMangaCategories(11L, listOf(2L)) }
        coVerify { categoryRepository.delete(3L) }
    }

    @Test
    fun `rejects invalid target and sources`() = runTest {
        val result = mergeCategories.await(targetId = 0L, sourceIds = listOf(3L))

        (result as MergeCategories.Result.InternalError).error.message shouldBe "Invalid merge target/sources"
    }
}
