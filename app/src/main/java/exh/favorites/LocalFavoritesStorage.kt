package exh.favorites

import eu.kanade.tachiyomi.source.online.all.EHentai
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.source.EXH_SOURCE_ID
import exh.source.isEhBasedManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import mihon.app.di.globalAppGraph
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.DeleteFavoriteEntries
import tachiyomi.domain.manga.interactor.GetFavoriteEntries
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.InsertFavoriteEntries
import tachiyomi.domain.manga.model.FavoriteEntry
import tachiyomi.domain.manga.model.Manga

class LocalFavoritesStorage(
    private val getFavorites: GetFavorites = globalAppGraph.getFavorites,
    private val getCategories: GetCategories = globalAppGraph.getCategories,
    private val deleteFavoriteEntries: DeleteFavoriteEntries = globalAppGraph.deleteFavoriteEntries,
    private val getFavoriteEntries: GetFavoriteEntries = globalAppGraph.getFavoriteEntries,
    private val insertFavoriteEntries: InsertFavoriteEntries = globalAppGraph.insertFavoriteEntries,
) {

    suspend fun getChangedDbEntries() = getFavorites.await()
        .asFlow()
        .loadDbCategories()
        .parseToFavoriteEntries()
        .getChangedEntries()

    suspend fun getChangedRemoteEntries(entries: List<EHentai.ParsedManga>) = entries
        .asFlow()
        .map {
            it.fav to it.manga.toDomainManga(EXH_SOURCE_ID).copy(
                favorite = true,
                dateAdded = System.currentTimeMillis(),
            )
        }
        .parseToFavoriteEntries()
        .getChangedEntries()

    suspend fun snapshotEntries() {
        val dbMangas = getFavorites.await()
            .asFlow()
            .loadDbCategories()
            .parseToFavoriteEntries()

        // Delete old snapshot
        deleteFavoriteEntries.await()

        // Insert new snapshots
        insertFavoriteEntries.await(dbMangas.toList())
    }

    suspend fun clearSnapshots() {
        deleteFavoriteEntries.await()
    }

    private suspend fun Flow<FavoriteEntry>.getChangedEntries(): ChangeSet {
        val terminated = canonicalEntries(toList())
        val databaseEntries = canonicalEntries(getFavoriteEntries.await())

        val added = terminated.filter { current ->
            databaseEntries.none { sameFavoriteGallery(current, it) } ||
                databaseEntries.none { sameFavoriteGallery(current, it) && it.category == current.category }
        }
        val removed = databaseEntries.filter { stored ->
            terminated.none { sameFavoriteGallery(stored, it) }
        }

        return ChangeSet(added, removed)
    }

    private fun canonicalEntries(entries: List<FavoriteEntry>): List<FavoriteEntry> =
        entries.groupBy(::favoriteIdentityKey)
            .map { (_, values) -> values.minByOrNull { it.category.takeIf { category -> category >= 0 } ?: Int.MAX_VALUE }!! }

    private suspend fun Flow<Manga>.loadDbCategories(): Flow<Pair<Int, Manga>> {
        val dbCategories = getCategories.await()
            .filterNot(Category::isSystemCategory)

        return filter(::validateDbManga).mapNotNull {
            val category = getCategories.await(it.id)

            dbCategories.indexOf(
                category.firstOrNull()
                    ?: return@mapNotNull null,
            ) to it
        }
    }

    private fun Flow<Pair<Int, Manga>>.parseToFavoriteEntries() =
        filter { (_, manga) ->
            validateDbManga(manga)
        }.mapNotNull { (categoryId, manga) ->
            FavoriteEntry(
                title = manga.ogTitle,
                gid = EHentaiSearchMetadata.galleryId(manga.url),
                token = EHentaiSearchMetadata.galleryToken(manga.url),
                category = categoryId,
            ).also {
                if (it.category > MAX_CATEGORIES) {
                    return@mapNotNull null
                }
            }
        }

    private fun validateDbManga(manga: Manga) =
        manga.favorite && manga.isEhBasedManga()

    companion object {
        const val MAX_CATEGORIES = 9
    }
}

internal fun favoriteIdentityKey(entry: FavoriteEntry): String {
    val primary = "${entry.gid}\u0000${entry.token}"
    val alternate = entry.otherGid?.let { gid ->
        entry.otherToken?.let { token -> "$gid\u0000$token" }
    }
    return listOfNotNull(primary, alternate).min()
}

internal fun sameFavoriteGallery(first: FavoriteEntry, second: FavoriteEntry): Boolean =
    favoriteIdentityKey(first) == favoriteIdentityKey(second)

data class ChangeSet(
    val added: List<FavoriteEntry>,
    val removed: List<FavoriteEntry>,
)
