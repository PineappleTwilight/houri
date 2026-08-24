package tachiyomi.domain.category.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerifySequence
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
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

@Execution(ExecutionMode.CONCURRENT)
class DeleteCategoryTest {

    private val categoryRepository: CategoryRepository = mockk()
    private val libraryPreferences: LibraryPreferences = mockk(relaxed = true)
    private val downloadPreferences: DownloadPreferences = mockk(relaxed = true)

    private val deleteCategory = DeleteCategory(
        categoryRepository = categoryRepository,
        libraryPreferences = libraryPreferences,
        downloadPreferences = downloadPreferences,
    )

    private fun category(id: Long, parentId: Long = 0L) = Category(
        id = id,
        name = "Category $id",
        order = id,
        flags = 0,
        hidden = false,
        parentId = parentId,
    )

    private fun stubDefaultCategory(id: Int) {
        coEvery { libraryPreferences.defaultCategory().get() } returns id
        coEvery { categoryRepository.updatePartial(any<List<CategoryUpdate>>()) } just runs
        listOf(
            libraryPreferences.updateCategories(),
            libraryPreferences.updateCategoriesExclude(),
            downloadPreferences.removeExcludeCategories(),
            downloadPreferences.downloadNewChapterCategories(),
            downloadPreferences.downloadNewChapterCategoriesExclude(),
            libraryPreferences.filterCategoriesInclude(),
            libraryPreferences.filterCategoriesExclude(),
        ).forEach { preference ->
            coEvery { preference.get() } returns emptySet()
        }
    }

    @Test
    fun `deletes child subcategories along with the parent`() = runTest {
        val parent = category(5)
        val sub1 = category(6, parentId = 5)
        val sub2 = category(7, parentId = 5)

        stubDefaultCategory(-1)
        coEvery { categoryRepository.getSubcategories(5) } returns listOf(sub1, sub2)
        coEvery { categoryRepository.delete(any()) } just runs
        coEvery { categoryRepository.getAll() } returns emptyList()

        deleteCategory.await(5) shouldBe DeleteCategory.Result.Success

        coVerifySequence {
            categoryRepository.getSubcategories(5)
            categoryRepository.delete(6)
            categoryRepository.delete(7)
            categoryRepository.delete(5)
            categoryRepository.getAll()
            categoryRepository.updatePartial(emptyList())
        }
    }

    @Test
    fun `deletes only the parent when it has no subcategories`() = runTest {
        stubDefaultCategory(-1)
        coEvery { categoryRepository.getSubcategories(3) } returns emptyList()
        coEvery { categoryRepository.delete(any()) } just runs
        coEvery { categoryRepository.getAll() } returns listOf(category(0), category(4))

        deleteCategory.await(3) shouldBe DeleteCategory.Result.Success

        coVerifySequence {
            categoryRepository.getSubcategories(3)
            categoryRepository.delete(3)
            categoryRepository.getAll()
            categoryRepository.updatePartial(
                listOf(
                    CategoryUpdate(id = 0, order = 0),
                    CategoryUpdate(id = 4, order = 1),
                ),
            )
        }
    }

    @Test
    fun `returns internal error when repository delete fails`() = runTest {
        val parent = category(2)
        val sub = category(8, parentId = 2)

        coEvery { categoryRepository.getSubcategories(2) } returns listOf(sub)
        coEvery { categoryRepository.delete(sub.id) } just runs
        coEvery { categoryRepository.delete(parent.id) } throws RuntimeException("db locked")

        val result = deleteCategory.await(2)
        (result as DeleteCategory.Result.InternalError).error.message shouldBe "db locked"
    }
}
