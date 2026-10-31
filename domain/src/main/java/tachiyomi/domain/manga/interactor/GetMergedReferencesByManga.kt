package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.manga.repository.MangaMergeRepository

@Inject
class GetMergedReferencesByManga(
    private val mangaMergeRepository: MangaMergeRepository,
) {

    /**
     * All merge references pointing at the given manga entry, matched by its id
     * or by its url + source (the id can be missing on stale references).
     */
    suspend fun await(
        mangaId: Long,
        mangaUrl: String,
        mangaSourceId: Long,
    ): List<MergedMangaReference> {
        return try {
            mangaMergeRepository.getReferencesByManga(mangaId, mangaUrl, mangaSourceId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }
}
