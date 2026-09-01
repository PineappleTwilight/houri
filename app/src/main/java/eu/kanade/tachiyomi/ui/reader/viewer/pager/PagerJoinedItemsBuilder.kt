package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * Builds the list of joined (paired) pager items from the flat sub-item list, implementing
 * the SY double-page machinery: full-page/solo-page handling, double-page shifting, and
 * inserting blank slots so every "page" occupies a fixed chunk of two slots.
 *
 * Pure logic — no adapter/viewer state is touched, only the [ReaderPage] flags that the
 * double-page machinery tracks ([ReaderPage.fullPage], [ReaderPage.isolatedPage],
 * [ReaderPage.shiftedPage]) are mutated, matching the legacy behavior.
 */
object PagerJoinedItemsBuilder {

    /**
     * @param subItems flat list of pages and transitions (reading order, RTL not yet reversed)
     * @param pageToShift the page the user chose to shift, or null
     * @param isR2L whether this viewer is right-to-left (reverses the final pairing)
     */
    fun build(
        subItems: List<ReaderItem>,
        pageToShift: ReaderPage?,
        isR2L: Boolean,
        doublePages: Boolean,
        shiftDoublePage: Boolean,
    ): MutableList<Pair<ReaderItem, ReaderItem?>> {
        if (!doublePages) {
            // If not in double mode, set up items like before
            subItems.forEach { readerItem ->
                if (readerItem is ReaderPage) {
                    readerItem.shiftedPage = false
                }
            }
            val joinedItems = subItems.map<ReaderItem, Pair<ReaderItem, ReaderItem?>> { Pair(it, null) }.toMutableList()
            if (isR2L) {
                joinedItems.reverse()
            }
            return joinedItems
        }

        val pagedItems = mutableListOf<MutableList<ReaderPage?>>()
        // Transition (or null) that must follow each page segment, aligned by index.
        // A transition is bound to the segment it directly precedes in [subItems], so a
        // skipped chapter-boundary transition (e.g. prev chapter already loaded) cannot
        // shift later transitions (like "no new chapters") into the middle of a chapter.
        val trailingItems = mutableListOf<ReaderItem?>()
        pagedItems.add(mutableListOf())
        trailingItems.add(null)

        // Step 1: segment the pages and transition pages
        subItems.forEach { readerItem ->
            when (readerItem) {
                is ReaderPage -> {
                    if (pagedItems.last().isNotEmpty() &&
                        pagedItems.last().last()?.chapter?.chapter?.id != readerItem.chapter.chapter.id
                    ) {
                        pagedItems.add(mutableListOf())
                        trailingItems.add(null)
                    }
                    pagedItems.last().add(readerItem)
                }
                is ChapterTransition -> {
                    // This transition follows the segment accumulated so far.
                    trailingItems[trailingItems.lastIndex] = readerItem
                    pagedItems.add(mutableListOf())
                    trailingItems.add(null)
                }
            }
        }

        val subJoinedItems = mutableListOf<Pair<ReaderItem, ReaderItem?>>()

        // Step 2: run through each set of pages
        pagedItems.forEachIndexed { segmentIndex, items ->
            items.forEach { it?.shiftedPage = false }

            // Step 3: If pages have been shifted,
            if (shiftDoublePage) {
                val index = items.indexOf(pageToShift)
                // Go from the current page and work your way back to the first page,
                // or the first page that's a full page.
                // This is done in case user tries to shift a page after a full page
                val fullPageBeforeIndex = if (index > -1) {
                    items.take(index).indexOfLast { it?.fullPage == true }
                } else {
                    -1
                }.coerceAtLeast(0)

                // Add a shifted page to the first place there isnt a full page
                run loop@{
                    (fullPageBeforeIndex until items.size).forEach {
                        if (items[it]?.fullPage == false) {
                            items[it]?.shiftedPage = true
                            return@loop
                        }
                    }
                }
            }

            // Step 4: Add blanks for chunking
            var itemIndex = 0
            while (itemIndex < items.size) {
                val currentItem = items[itemIndex]
                currentItem?.isolatedPage = false
                if (currentItem?.fullPage == true || currentItem?.shiftedPage == true) {
                    // Add a 'blank' page after each full page. It will be used when chunked to solo a page
                    items.add(itemIndex + 1, null)
                    if (
                        currentItem.fullPage &&
                        itemIndex > 0 &&
                        items[itemIndex - 1] != null &&
                        (itemIndex - 1) % 2 == 0
                    ) {
                        // If a page is a full page, check if the previous page needs to be isolated
                        // we should check if it's an even or odd page, since even pages need shifting
                        // For example if Page 1 is full, Page 0 needs to be isolated
                        // No need to take account shifted pages, because null additions should
                        // always have an odd index in the list
                        items[itemIndex - 1]?.isolatedPage = true
                        items.add(itemIndex, null)
                        itemIndex++
                    }
                    itemIndex++
                }
                itemIndex++
            }

            // Step 5: chunk em
            if (items.isNotEmpty()) {
                subJoinedItems.addAll(items.chunked(2).map { Pair(it.first()!!, it.getOrNull(1)) })
            }

            trailingItems.getOrNull(segmentIndex)?.let {
                subJoinedItems.add(Pair(it, null))
            }
        }

        if (isR2L) {
            subJoinedItems.reverse()
        }

        return subJoinedItems
    }
}
