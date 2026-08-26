package eu.kanade.tachiyomi.ui.manga

import eu.kanade.core.util.addOrRemove

/**
 * Owns chapter-selection state (the selected-id set plus long-press range anchors) and
 * applies selection toggles to the processed chapter list through [onUpdateState].
 */
internal class ChapterSelectionController(
    private val onUpdateState: ((MangaScreenModel.State.Success) -> MangaScreenModel.State.Success) -> Unit,
) {

    private val selectedIds = HashSet<Long>()

    // First and last selected index in the processed list; -1 when nothing is selected.
    private var rangeStart = -1
    private var rangeEnd = -1

    fun isSelected(id: Long): Boolean = id in selectedIds

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        onUpdateState { successState ->
            // KMK -->
            val selectedIndex = successState.processedChapters.indexOfFirst { it.id == item.chapter.id }
            if (selectedIndex < 0) return@onUpdateState successState
            val selectedItem = successState.processedChapters[selectedIndex]
            if (selectedItem.selected == selected) return@onUpdateState successState
            // KMK <--

            val newChapters = successState.processedChapters.toMutableList().apply {
                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedIds.addOrRemove(item.id, selected)

                if (selected && fromLongPress) {
                    if (firstSelection) {
                        rangeStart = selectedIndex
                        rangeEnd = selectedIndex
                    } else {
                        // Try to select the items in-between when possible
                        val range: IntRange
                        if (selectedIndex < rangeStart) {
                            range = selectedIndex + 1..<rangeStart
                            rangeStart = selectedIndex
                        } else if (selectedIndex > rangeEnd) {
                            range = rangeEnd + 1..<selectedIndex
                            rangeEnd = selectedIndex
                        } else {
                            // Just select itself
                            range = IntRange.EMPTY
                        }

                        range.forEach {
                            val inBetweenItem = get(it)
                            if (!inBetweenItem.selected) {
                                selectedIds.add(inBetweenItem.id)
                                set(it, inBetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (!fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == rangeStart) {
                            rangeStart = indexOfFirst { it.selected }
                        } else if (selectedIndex == rangeEnd) {
                            rangeEnd = indexOfLast { it.selected }
                        }
                    } else {
                        if (selectedIndex < rangeStart) {
                            rangeStart = selectedIndex
                        } else if (selectedIndex > rangeEnd) {
                            rangeEnd = selectedIndex
                        }
                    }
                }
            }
            successState.copy(chapters = newChapters)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        onUpdateState { successState ->
            val newChapters = successState.chapters.map {
                selectedIds.addOrRemove(it.id, selected)
                it.copy(selected = selected)
            }
            rangeStart = -1
            rangeEnd = -1
            successState.copy(chapters = newChapters)
        }
    }

    fun invertSelection() {
        onUpdateState { successState ->
            val newChapters = successState.chapters.map {
                selectedIds.addOrRemove(it.id, !it.selected)
                it.copy(selected = !it.selected)
            }
            rangeStart = -1
            rangeEnd = -1
            successState.copy(chapters = newChapters)
        }
    }
}
