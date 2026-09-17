package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.HideCategory
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.UpdateCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.i18n.MR

class CategoryScreenModel(
    private val getCategories: GetCategories = globalAppGraph.getCategories,
    private val createCategoryWithName: CreateCategoryWithName = globalAppGraph.createCategoryWithName,
    private val deleteCategory: DeleteCategory = globalAppGraph.deleteCategory,
    private val reorderCategory: ReorderCategory = globalAppGraph.reorderCategory,
    private val renameCategory: RenameCategory = globalAppGraph.renameCategory,
    // KMK -->
    private val hideCategory: HideCategory = globalAppGraph.hideCategory,
    private val updateCategory: UpdateCategory = globalAppGraph.updateCategory,
    private val getLibraryManga: GetLibraryManga = globalAppGraph.getLibraryManga,
    private val libraryPreferences: LibraryPreferences = globalAppGraph.libraryPreferences,
    // KMK <--
) : StateScreenModel<CategoryScreenState>(CategoryScreenState.Loading) {

    private val _events: Channel<CategoryEvent> = Channel()
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launch {
            combine(
                getCategories.subscribe(),
                getLibraryManga.subscribe(),
            ) { categories, libraryManga ->
                val counts = mutableMapOf<Long, Int>()
                for (item in libraryManga) {
                    for (categoryId in item.categories) {
                        counts[categoryId] = (counts[categoryId] ?: 0) + 1
                    }
                }
                categories.filterNot(Category::isSystemCategory) to counts.toPersistentMap()
            }
                .collectLatest { (categories, counts) ->
                    mutableState.update {
                        when (it) {
                            CategoryScreenState.Loading -> CategoryScreenState.Success(
                                categories = categories.toImmutableList(),
                                mangaCounts = counts,
                                collapsedIds = libraryPreferences.categoryManagerCollapsedIds().get()
                                    .mapNotNull(String::toLongOrNull)
                                    .toSet(),
                                sortMode = libraryPreferences.categoryManagerSortMode().get(),
                            )
                            is CategoryScreenState.Success -> it.copy(
                                categories = categories.toImmutableList(),
                                mangaCounts = counts,
                            )
                        }
                    }
                }
        }
    }

    fun createCategory(name: String) {
        screenModelScope.launch {
            when (createCategoryWithName.await(name)) {
                is CreateCategoryWithName.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    // KMK -->
    fun createSubcategory(name: String, parentId: Long) {
        screenModelScope.launch {
            when (createCategoryWithName.await(name, parentId)) {
                is CreateCategoryWithName.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }
    // KMK <--

    // KMK -->
    fun hideCategory(category: Category) {
        screenModelScope.launch {
            when (hideCategory.await(category)) {
                is HideCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }
    // KMK <--

    fun deleteCategory(categoryId: Long) {
        screenModelScope.launch {
            when (deleteCategory.await(categoryId = categoryId)) {
                is DeleteCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    fun changeOrder(category: Category, newIndex: Int) {
        screenModelScope.launch {
            when (reorderCategory.await(category, newIndex)) {
                is ReorderCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    // KMK -->
    fun reparentSubcategory(category: Category, newParentId: Long, newIndex: Int) {
        screenModelScope.launch {
            val updateResult = updateCategory.await(CategoryUpdate(id = category.id, parentId = newParentId))
            if (updateResult is UpdateCategory.Result.Error) {
                _events.send(CategoryEvent.InternalError)
                return@launch
            }
            val updated = category.copy(parentId = newParentId)
            when (reorderCategory.await(updated, newIndex)) {
                is ReorderCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }
    // KMK <--

    fun renameCategory(category: Category, name: String) {
        screenModelScope.launch {
            when (renameCategory.await(category, name)) {
                is RenameCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    // KMK -->
    fun setSearchQuery(query: String) {
        mutableState.update {
            if (it is CategoryScreenState.Success) it.copy(searchQuery = query) else it
        }
    }

    fun toggleCollapsed(categoryId: Long) {
        mutableState.update {
            if (it !is CategoryScreenState.Success) return@update it
            val collapsed = if (categoryId in it.collapsedIds) {
                it.collapsedIds - categoryId
            } else {
                it.collapsedIds + categoryId
            }
            libraryPreferences.categoryManagerCollapsedIds().set(collapsed.map(Long::toString).toSet())
            it.copy(collapsedIds = collapsed)
        }
    }

    fun expandAll() {
        libraryPreferences.categoryManagerCollapsedIds().set(emptySet())
        mutableState.update {
            if (it is CategoryScreenState.Success) it.copy(collapsedIds = emptySet()) else it
        }
    }

    fun collapseAll() {
        mutableState.update {
            if (it !is CategoryScreenState.Success) return@update it
            val collapsed = it.categories.filter { category -> category.parentId == 0L }.map { it.id }.toSet()
            libraryPreferences.categoryManagerCollapsedIds().set(collapsed.map(Long::toString).toSet())
            it.copy(collapsedIds = collapsed)
        }
    }

    fun setSortMode(mode: Int) {
        libraryPreferences.categoryManagerSortMode().set(mode)
        mutableState.update {
            if (it is CategoryScreenState.Success) it.copy(sortMode = mode) else it
        }
    }
    // KMK <--

    fun showDialog(dialog: CategoryDialog) {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

sealed interface CategoryDialog {
    data object Create : CategoryDialog
    // KMK -->
    data class CreateSubcategory(val parent: Category) : CategoryDialog
    // KMK <--
    data class Rename(val category: Category) : CategoryDialog
    data class Delete(val category: Category) : CategoryDialog
}

sealed interface CategoryEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : CategoryEvent
    data object InternalError : LocalizedMessage(MR.strings.internal_error)
}

sealed interface CategoryScreenState {

    @Immutable
    data object Loading : CategoryScreenState

    @Immutable
    data class Success(
        val categories: ImmutableList<Category>,
        val dialog: CategoryDialog? = null,
        // KMK -->
        val mangaCounts: ImmutableMap<Long, Int> = kotlinx.collections.immutable.persistentMapOf(),
        val searchQuery: String = "",
        val collapsedIds: Set<Long> = emptySet(),
        val sortMode: Int = CategoryManagerSort.MANUAL,
        // KMK <--
    ) : CategoryScreenState {

        val isEmpty: Boolean
            get() = categories.isEmpty()
    }
}

// KMK -->
object CategoryManagerSort {
    const val MANUAL = 0
    const val AZ = 1
    const val ZA = 2
    const val MOST_ITEMS = 3
    const val LEAST_ITEMS = 4
}
// KMK <--
