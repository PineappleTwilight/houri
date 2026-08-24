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
import tachiyomi.domain.category.repository.CategoryRepository

@Execution(ExecutionMode.CONCURRENT)
class DeleteOrphanedSubcategoriesTest {

    private val categoryRepository: CategoryRepository = mockk()
    private val deleteOrphanedSubcategories = DeleteOrphanedSubcategories(categoryRepository)

    @Test
    fun `delegates orphan deletion to repository`() = runTest {
        coEvery { categoryRepository.deleteOrphanedSubcategories() } just runs

        val result = deleteOrphanedSubcategories.await()

        result shouldBe DeleteOrphanedSubcategories.Result.Success
        coVerify(exactly = 1) { categoryRepository.deleteOrphanedSubcategories() }
    }

    @Test
    fun `returns internal error when repository fails`() = runTest {
        coEvery { categoryRepository.deleteOrphanedSubcategories() } throws RuntimeException("db locked")

        val result = deleteOrphanedSubcategories.await()

        (result as DeleteOrphanedSubcategories.Result.InternalError).error.message shouldBe "db locked"
    }
}
