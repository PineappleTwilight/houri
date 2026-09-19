package exh.md.related

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.all.MangaDex
import exh.md.service.MangaDexService
import exh.md.utils.MdUtil
import exh.md.utils.MdUtil.Companion.baseUrl
import exh.source.getMainSource
import exh.source.mangaDexSourceIds
import okhttp3.Headers
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.SequelPrequelProvider
import tachiyomi.domain.manga.model.SequelPrequelEntry
import tachiyomi.domain.manga.model.SequelPrequelRelation
import tachiyomi.domain.source.service.SourceManager

// KMK -->
@Inject
@SingleIn(AppScope::class)
class MangaDexSequelPrequelProvider(
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
    private val networkHelper: NetworkHelper,
) : SequelPrequelProvider {
    override suspend fun fetch(mangaId: Long, preferredTrackerId: Long?): List<SequelPrequelEntry> {
        val manga = getManga.await(mangaId) ?: return emptyList()
        if (manga.source !in mangaDexSourceIds) return emptyList()

        val source = sourceManager.getOrStub(manga.source)
        val lang = (source.getMainSource() as? MangaDex)?.lang ?: "en"
        val dexId = MdUtil.getMangaId(manga.url)
        if (dexId.isBlank()) return emptyList()

        val service = MangaDexService(networkHelper.client, headers)
        val related = service.relatedManga(dexId)

        val pairs = related.data.mapNotNull { dto ->
            val relation = SequelPrequelRelation.fromDex(dto.attributes.relation)
                ?.takeIf { it == SequelPrequelRelation.PREQUEL || it == SequelPrequelRelation.SEQUEL }
                ?: return@mapNotNull null
            val relatedId = dto.relationships.firstOrNull()?.id ?: return@mapNotNull null
            relatedId to relation
        }
        if (pairs.isEmpty()) return emptyList()

        val titlesById = service.viewMangas(pairs.map { it.first }.distinct())
            .data.associateBy({ it.id }, { it.attributes })
        return pairs.mapNotNull { (relatedId, relation) ->
            val attributes = titlesById[relatedId] ?: return@mapNotNull null
            SequelPrequelEntry(
                title = MdUtil.getTitleFromManga(attributes, lang, true).ifBlank { return@mapNotNull null },
                url = MdUtil.buildMangaUrl(relatedId),
                relation = relation,
                trackerId = preferredTrackerId,
            )
        }
    }

    private val headers: Headers
        get() = Headers.Builder().apply {
            set("Referer", "$baseUrl/")
            set("Origin", baseUrl)
            set("sec-fetch-dest", "document")
            set("sec-fetch-mode", "navigate")
        }.build()
}
// KMK <--
