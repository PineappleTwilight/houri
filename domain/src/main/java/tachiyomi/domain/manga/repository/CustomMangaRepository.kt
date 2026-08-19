package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import tachiyomi.domain.manga.model.CustomMangaInfo

interface CustomMangaRepository {

    fun get(mangaId: Long): CustomMangaInfo?

    fun set(mangaInfo: CustomMangaInfo)

    /**
     * Emits after any custom manga info is written, so dependent Flows can
     * re-emit with updated data even though the SQLDelight database hasn't
     * changed.
     */
    val changes: SharedFlow<Unit>
}
