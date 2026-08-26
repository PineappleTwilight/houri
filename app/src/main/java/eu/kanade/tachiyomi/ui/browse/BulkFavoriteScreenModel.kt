package eu.kanade.tachiyomi.ui.browse

import androidx.compose.runtime.Immutable
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.service.SourceManager
import java.time.Instant

class BulkFavoriteScreenModel(
    initialState: State = State(),
    private val sourceManager: SourceManager = globalAppGraph.sourceManager,
    private val libraryPreferences: LibraryPreferences = globalAppGraph.libraryPreferences,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = globalAppGraph.getDuplicateLibraryManga,
    private val getCategories: GetCategories = globalAppGraph.getCategories,
    private val setMangaCategories: SetMangaCategories = globalAppGraph.setMangaCategories,
    private val updateManga: UpdateManga = globalAppGraph.updateManga,
    private val coverCache: CoverCache = globalAppGraph.coverCache,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = globalAppGraph.setMangaDefaultChapterFlags,
    private val addTracks: AddTracks = globalAppGraph.addTracks,
    private val updateMangaFromRemote: UpdateMangaFromRemote = globalAppGraph.updateMangaFromRemote,
) : StateScreenModel<BulkFavoriteScreenModel.State>(initialState) {

    fun backHandler() {
        toggleSelectionMode(false)
    }

    fun toggleSelectionMode(newMode: Boolean? = null) {
        mutableState.update { state ->
            val mode = newMode ?: !state.selectionMode
            state.copy(
                selectionMode = mode,
                selection = if (mode) state.selection else persistentListOf(),
            )
        }
    }

    fun select(manga: Manga) {
        toggleSelection(manga, toSelectedState = true)
    }

    /**
     * @param toSelectedState set to `true` to only Select, set to `false` to only Unselect
     */
    fun toggleSelection(manga: Manga, toSelectedState: Boolean? = null) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val isSelected = list.fastAny { it.id == manga.id }
                val shouldSelect = toSelectedState ?: !isSelected
                // Both condition to avoid adding duplicate entries
                if (shouldSelect && !isSelected) {
                    list.add(manga)
                } else if (!shouldSelect && isSelected) {
                    list.removeAll { it.id == manga.id }
                }
            }
            state.copy(
                selection = newSelection,
                selectionMode = newSelection.isNotEmpty(),
            )
        }
    }

    fun reverseSelection(mangas: List<Manga>) {
        mutableState.update { state ->
            val newSelection = mangas.filterNot { manga ->
                state.selection.contains(manga)
            }
                .fastDistinctBy { it.id }
                .toPersistentList()
            state.copy(
                selection = newSelection,
                selectionMode = newSelection.isNotEmpty(),
            )
        }
    }

    /**
     * Called when user click on [BulkSelectionToolbar]'s `Favorite` button.
     * It will then look for any duplicated mangas.
     * - If there is any, it will show the [DuplicateMangaDialog].
     * - If not then it will call the [addFavoriteDuplicate].
     */
    fun addFavorite(startIdx: Int = 0) {
        screenModelScope.launch {
            startRunning()
            val entryWithDuplicates = getDuplicateLibraryManga(startIdx)
            if (entryWithDuplicates != null) {
                val (index, manga, duplicates) = entryWithDuplicates
                if (state.value.selection.size == 1) {
                    // If only one manga is selected, show the multiple-duplicates dialog.
                    setDialog(Dialog.AddDuplicateManga(manga, duplicates))
                } else {
                    setDialog(Dialog.BulkAllowDuplicate(manga, duplicates, index))
                }
            } else {
                addFavoriteDuplicate()
            }
        }
    }

    /**
     * Add mangas to library if there is default category or no category exists.
     * If not, it shows the categories list.
     *
     * @param skipAllDuplicates if true, skip all duplicates and add all selected mangas to library.
     * if false, allow all duplicates and add all selected mangas to library
     */
    internal fun addFavoriteDuplicate(skipAllDuplicates: Boolean = false) {
        screenModelScope.launch {
            val mangaList = if (skipAllDuplicates) getNotDuplicateLibraryMangas() else state.value.selection
            if (mangaList.isEmpty()) {
                stopRunning()
                toggleSelectionMode(false)
                return@launch
            }
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    stopRunning()
                    setMangasCategories(mangaList, listOf(defaultCategory.id), emptyList())
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    stopRunning()
                    // Automatic 'Default' or no categories
                    setMangasCategories(mangaList, emptyList(), emptyList())
                }

                else -> {
                    val mangaCategorySets = getCategorySets(mangaList)
                    val common = mangaCategorySets.reduce { set1, set2 -> set1.intersect(set2) }
                    val mix = mangaCategorySets.flatten().distinct().subtract(common)
                    val preselected = categories
                        .map {
                            when (it) {
                                in common -> CheckboxState.State.Checked(it)
                                in mix -> CheckboxState.TriState.Exclude(it)
                                else -> CheckboxState.State.None(it)
                            }
                        }
                        .toImmutableList()
                    stopRunning()
                    setDialog(Dialog.ChangeMangasCategory(mangaList, preselected))
                }
            }
        }
    }

    private suspend fun getNotDuplicateLibraryMangas(): List<Manga> {
        return state.value.selection.filterNot { manga ->
            getDuplicateLibraryManga(manga).isNotEmpty()
        }
    }

    private suspend fun getDuplicateLibraryManga(startIdx: Int = 0): Triple<Int, Manga, List<MangaWithChapterCount>>? {
        val mangas = state.value.selection
        mangas.fastForEachIndexed { index, manga ->
            if (index < startIdx) return@fastForEachIndexed
            val duplicates = getDuplicateLibraryManga(manga)
            if (duplicates.isEmpty()) return@fastForEachIndexed
            return Triple(index, manga, duplicates)
        }
        return null
    }

    internal fun removeDuplicateSelectedManga(index: Int) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                list.removeAt(index)
            }
            state.copy(selection = newSelection)
        }
    }

    /**
     * Bulk update categories of manga using old and new common categories.
     *
     * @param mangaList the list of manga to move.
     * @param addCategories the categories to add for all mangas.
     * @param removeCategories the categories to remove in all mangas.
     */
    internal fun setMangasCategories(mangaList: List<Manga>, addCategories: List<Long>, removeCategories: List<Long>) {
        screenModelScope.launchNonCancellable {
            startRunning()
            val mangasToRefresh = mutableListOf<Pair<Manga, Source>>()
            mangaList.fastForEach { manga ->
                val categoryIds = getCategories.await(manga.id)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                moveMangaToCategory(manga.id, categoryIds)
                addToLibrary(manga)?.let { mangasToRefresh += manga to it }
            }
            stopRunning()
            refreshMangasFromRemote(mangasToRefresh)
        }
        toggleSelectionMode(false)
    }

    private suspend fun addToLibrary(manga: Manga): Source? {
        if (manga.favorite) return null

        return try {
            val source = sourceManager.getOrStub(manga.source)
            setMangaDefaultChapterFlags.await(manga)
            addTracks.bindEnhancedTrackers(manga, source)
            updateManga.awaitUpdateFavorite(manga.id, true)
            source
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Refreshes the given mangas from their sources sequentially, pacing each request
     * to avoid hammering the remote API during bulk operations.
     */
    private suspend fun refreshMangasFromRemote(mangas: List<Pair<Manga, Source>>) {
        val fetchMetadataOnAdd = libraryPreferences.fetchMetadataOnAdd().get()
        val fetchChaptersOnAdd = libraryPreferences.fetchChaptersOnAdd().get()
        if (!fetchMetadataOnAdd && !fetchChaptersOnAdd) return

        mangas.forEach { (manga, source) ->
            delay(1000)
            try {
                updateMangaFromRemote(
                    source = source,
                    manga = manga,
                    fetchDetails = fetchMetadataOnAdd,
                    fetchChapters = fetchChaptersOnAdd,
                )
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun moveMangaToCategory(mangaId: Long, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    private suspend fun getCategorySets(mangas: List<Manga>): List<Set<Category>> {
        return mangas.map { getCategories.await(it.id).toSet() }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    private suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    internal fun changeMangaFavorite(manga: Manga) {
        val source = sourceManager.getOrStub(manga.source)

        screenModelScope.launch {
            var new = manga.copy(
                favorite = !manga.favorite,
                dateAdded = when (manga.favorite) {
                    true -> 0
                    false -> Instant.now().toEpochMilli()
                },
            )
            // TODO: also allow deleting chapters when remove favorite (just like in [MangaScreenModel])
            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                setMangaDefaultChapterFlags.await(manga)
                addTracks.bindEnhancedTrackers(manga, source)
            }

            updateManga.await(new.toMangaUpdate())
            val fetchMetadataOnAdd = libraryPreferences.fetchMetadataOnAdd().get()
            val fetchChaptersOnAdd = libraryPreferences.fetchChaptersOnAdd().get()
            if (new.favorite && (fetchMetadataOnAdd || fetchChaptersOnAdd)) {
                try {
                    delay(1000)
                    updateMangaFromRemote(
                        source = source,
                        manga = manga,
                        fetchDetails = fetchMetadataOnAdd,
                        fetchChapters = fetchChaptersOnAdd,
                    )
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }
        }
    }

    internal fun addFavorite(manga: Manga) {
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveMangaToCategory(
                        manga.id,
                        listOfNotNull(defaultCategory).filter { it.id != 0L }.map { it.id },
                    )
                    changeMangaFavorite(manga)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveMangaToCategory(manga.id, emptyList())
                    changeMangaFavorite(manga)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                    setDialog(
                        Dialog.ChangeMangasCategory(
                            listOf(manga),
                            categories.mapAsCheckboxState { it.id in preselectedIds }.toImmutableList(),
                        ),
                    )
                }
            }
        }
    }

    fun addRemoveManga(manga: Manga, haptic: HapticFeedback? = null) {
        screenModelScope.launchIO {
            val duplicates = getDuplicateLibraryManga(manga)
            when {
                manga.favorite -> setDialog(Dialog.RemoveManga(manga))
                duplicates.isNotEmpty() -> setDialog(
                    Dialog.AddDuplicateManga(manga, duplicates),
                )
                else -> addFavorite(manga)
            }
            haptic?.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    internal fun showMigrateDialog(manga: Manga, duplicate: Manga) {
        setDialog(Dialog.Migrate(target = manga, current = duplicate))
    }

    private fun setDialog(dialog: Dialog?) {
        mutableState.update {
            it.copy(dialog = dialog)
        }
    }

    internal fun dismissDialog() {
        mutableState.update {
            it.copy(dialog = null)
        }
    }

    private fun startRunning() {
        mutableState.update {
            it.copy(isRunning = true)
        }
    }

    internal fun stopRunning() {
        mutableState.update {
            it.copy(isRunning = false)
        }
    }

    sealed interface Dialog {
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class AddDuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class BulkAllowDuplicate(val manga: Manga, val duplicates: List<MangaWithChapterCount>, val currentIdx: Int) : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class ChangeMangasCategory(
            val mangas: List<Manga>,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
    }

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val selection: PersistentList<Manga> = persistentListOf(),
        val selectionMode: Boolean = false,
        val isRunning: Boolean = false,
    )
}
