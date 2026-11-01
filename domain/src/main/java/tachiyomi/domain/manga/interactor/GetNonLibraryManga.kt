package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

// KMK -->
@Inject
class GetNonLibraryManga(
    private val repository: MangaRepository,
) {

    suspend fun await(): List<Manga> {
        return repository.getNonLibraryManga()
    }
}
// KMK <--
