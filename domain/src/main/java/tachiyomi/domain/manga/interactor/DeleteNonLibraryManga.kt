package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.repository.MangaRepository

class DeleteNonLibraryManga(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(sourceIds: List<Long>, keepReadManga: Boolean) {
        mangaRepository.deleteNonLibraryManga(sourceIds, keepReadManga)
    }
}
