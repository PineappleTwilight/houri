package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.webhook.WebhookEvent
import eu.kanade.tachiyomi.data.webhook.WebhookNotifier
import mihon.app.di.globalAppGraph
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import java.time.Instant
import java.time.ZonedDateTime

@Inject
class UpdateManga(
    private val mangaRepository: MangaRepository,
    private val fetchInterval: FetchInterval,
) {
    // KMK -->
    private val webhookNotifier: WebhookNotifier get() = globalAppGraph.webhookNotifier
    // KMK <--

    suspend fun await(mangaUpdate: MangaUpdate): Boolean {
        return mangaRepository.update(mangaUpdate)
    }

    suspend fun awaitAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return mangaRepository.updateAll(mangaUpdates)
    }

    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        dateTime: ZonedDateTime = ZonedDateTime.now(),
        window: Pair<Long, Long> = fetchInterval.getWindow(dateTime),
    ): Boolean {
        return mangaRepository.update(
            fetchInterval.toMangaUpdate(manga, dateTime, window),
        )
    }

    suspend fun awaitUpdateLastUpdate(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, lastUpdate = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateCoverLastModified(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, coverLastModified = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        val dateAdded = when (favorite) {
            true -> Instant.now().toEpochMilli()
            false -> 0
        }
        val result = mangaRepository.update(
            MangaUpdate(id = mangaId, favorite = favorite, dateAdded = dateAdded),
        )
        // KMK -->
        if (result) {
            val manga = mangaRepository.getMangaById(mangaId)
            if (manga != null) {
                webhookNotifier.notify(
                    if (favorite) WebhookEvent.MANGA_ADDED else WebhookEvent.MANGA_REMOVED,
                    mapOf("manga" to manga.title),
                    sourceId = manga.source,
                    mangaId = manga.id,
                )
            }
        }
        // KMK <--
        return result
    }
}
