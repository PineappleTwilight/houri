package eu.kanade.tachiyomi.ui.common

import eu.kanade.domain.manga.interactor.UpdateManga
import kotlinx.coroutines.CoroutineScope
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.model.Manga

/**
 * Shared "move to categories and mark favorite" flow used by the history and manga screens.
 *
 * Category assignment and favoriting run as independent fire-and-forget writes, matching
 * the historical behavior at both call sites.
 */
class AddToLibrary(
    private val scope: CoroutineScope,
    private val setMangaCategories: SetMangaCategories = globalAppGraph.setMangaCategories,
    private val updateManga: UpdateManga = globalAppGraph.updateManga,
) {

    fun moveToCategoriesAndFavorite(manga: Manga, categoryIds: List<Long>) {
        scope.launchIO {
            setMangaCategories.await(manga.id, categoryIds)
        }
        if (manga.favorite) return

        scope.launchIO {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }
}
