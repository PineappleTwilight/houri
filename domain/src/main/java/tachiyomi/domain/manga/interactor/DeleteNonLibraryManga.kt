package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class DeleteNonLibraryManga(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(sourceIds: List<Long>, keepReadManga: Boolean) {
        mangaRepository.deleteNonLibraryManga(sourceIds, keepReadManga)
    }
}
