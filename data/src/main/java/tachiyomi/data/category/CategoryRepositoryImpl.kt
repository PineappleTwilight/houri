package tachiyomi.data.category

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CategoryRepositoryImpl(
    private val handler: DatabaseHandler,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return handler.awaitOneOrNull { categoriesQueries.getCategory(id, CategoryMapper::mapCategory) }
    }

    override suspend fun getAll(): List<Category> {
        return handler.awaitList { categoriesQueries.getCategories(CategoryMapper::mapCategory) }
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return handler.subscribeToList { categoriesQueries.getCategories(CategoryMapper::mapCategory) }
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return handler.awaitList {
            categoriesQueries.getCategoriesByMangaId(mangaId, CategoryMapper::mapCategory)
        }
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return handler.subscribeToList {
            categoriesQueries.getCategoriesByMangaId(mangaId, CategoryMapper::mapCategory)
        }
    }

    // SY -->
    override suspend fun insert(category: Category): Long {
        return handler.awaitOneExecutable(true) {
            categoriesQueries.insert(
                name = category.name,
                order = category.order,
                flags = category.flags,
                // KMK -->
                hidden = if (category.hidden) 1L else 0L,
                parentId = category.parentId,
                // KMK <--
            )
            categoriesQueries.selectLastInsertedRowId()
        }
    }
    // SY <--

    override suspend fun updatePartial(update: CategoryUpdate) {
        handler.await {
            updatePartialBlocking(update)
        }
    }

    override suspend fun updatePartial(updates: List<CategoryUpdate>) {
        handler.await(inTransaction = true) {
            for (update in updates) {
                updatePartialBlocking(update)
            }
        }
    }

    private fun Database.updatePartialBlocking(update: CategoryUpdate) {
        categoriesQueries.update(
            name = update.name,
            order = update.order,
            flags = update.flags,
            // KMK -->
            hidden = update.hidden?.let { if (it) 1L else 0L },
            parentId = update.parentId,
            // KMK <--
            categoryId = update.id,
        )
    }

    override suspend fun updateAllFlags(flags: Long?) {
        handler.await {
            categoriesQueries.updateAllFlags(flags)
        }
    }

    override suspend fun delete(categoryId: Long) {
        handler.await {
            categoriesQueries.delete(
                categoryId = categoryId,
            )
        }
    }

    // KMK -->
    override suspend fun getSubcategories(parentId: Long): List<Category> {
        return handler.awaitList { categoriesQueries.getSubcategories(parentId, CategoryMapper::mapCategory) }
    }

    override fun getSubcategoriesAsFlow(parentId: Long): Flow<List<Category>> {
        return handler.subscribeToList { categoriesQueries.getSubcategories(parentId, CategoryMapper::mapCategory) }
    }

    override suspend fun getTopLevelCategories(): List<Category> {
        return handler.awaitList { categoriesQueries.getTopLevelCategories(CategoryMapper::mapCategory) }
    }

    override suspend fun getMangaIdsInCategoryTree(categoryId: Long): List<Long> {
        return handler.awaitList { categoriesQueries.getMangaIdsInCategoryTree(categoryId) }
    }

    override suspend fun deleteOrphanedSubcategories() {
        handler.await {
            categoriesQueries.deleteOrphanedSubcategories()
        }
    }
    // KMK <--
}
