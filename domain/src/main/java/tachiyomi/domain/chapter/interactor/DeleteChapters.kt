package tachiyomi.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterTombstones
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class DeleteChapters(
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
    // SY -->
    private val chapterTombstones: ChapterTombstones = ChapterTombstones { },
    // SY <--
) {

    suspend fun await(chapters: List<Long>) {
        // SY --> Remember SyncYomi v2 chapter keys before the rows disappear.
        if (chapters.isNotEmpty()) {
            val keys = chapters.mapNotNull { id ->
                val chapter = chapterRepository.getChapterById(id) ?: return@mapNotNull null
                val manga = runCatching { mangaRepository.getMangaById(chapter.mangaId) }.getOrNull()
                    ?: return@mapNotNull null
                ChapterTombstones.key(manga.source, manga.url, chapter.url)
            }
            if (keys.isNotEmpty()) {
                chapterTombstones.remember(keys)
            }
        }
        // SY <--
        chapterRepository.removeChaptersWithIds(chapters)
    }
}
