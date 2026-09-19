package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.metadata.sql.models.SearchTitle
import tachiyomi.domain.manga.model.SequelPrequelEntry
import tachiyomi.domain.manga.repository.MangaMetadataRepository

@Inject
class GetSearchTitles(
    private val mangaMetadataRepository: MangaMetadataRepository,
) {

    suspend fun await(mangaId: Long): List<SearchTitle> {
        return mangaMetadataRepository.getTitlesById(mangaId)
    }

    suspend fun awaitBulk(mangaIds: Collection<Long>): Map<Long, List<SearchTitle>> {
        return mangaMetadataRepository.getTitlesByIds(mangaIds)
    }
}

// KMK -->
fun interface SequelPrequelProvider {
    suspend fun fetch(mangaId: Long, preferredTrackerId: Long?): List<SequelPrequelEntry>
}

@SingleIn(AppScope::class)
@Inject
class RelatedMangaCache(
    private val ttlMillis: Long = 24 * 60 * 60 * 1000L,
    private val maxEntries: Int = 200,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(val value: List<SequelPrequelEntry>, val storedAt: Long)

    private val lock = Any()
    private val store = LinkedHashMap<Long, Entry>(maxEntries, 0.75f, true)

    fun get(mangaId: Long): List<SequelPrequelEntry>? = synchronized(lock) {
        val entry = store[mangaId] ?: return null
        if (clock() - entry.storedAt > ttlMillis) {
            store.remove(mangaId)
            return null
        }
        entry.value
    }

    fun put(mangaId: Long, value: List<SequelPrequelEntry>) = synchronized(lock) {
        store[mangaId] = Entry(value, clock())
        while (store.size > maxEntries) {
            store.remove(store.keys.first())
        }
    }

    fun invalidate(mangaId: Long) {
        synchronized(lock) {
            store.remove(mangaId)
        }
    }
}

@Inject
class GetSequelPrequel(
    private val cache: RelatedMangaCache,
    private val provider: SequelPrequelProvider,
) {
    suspend fun await(mangaId: Long, preferredTrackerId: Long?, enabled: Boolean): Result {
        if (!enabled) return Result.Disabled
        cache.get(mangaId)?.let { return Result.Success(it) }
        val entries = try {
            provider.fetch(mangaId, preferredTrackerId)
        } catch (_: Exception) {
            return Result.Hidden
        }
        // KMK --> never cache empty results: bindings change (a tracker is
        // bound after the first view, an API hiccups) and a cached empty
        // would hide later-available relations until the 24h TTL expires.
        if (entries.isEmpty()) return Result.Hidden
        cache.put(mangaId, entries)
        return Result.Success(entries)
    }

    /** Drops the cached entry so the next [await] refetches (e.g. after track bindings change). */
    fun invalidate(mangaId: Long) {
        cache.invalidate(mangaId)
    }

    sealed interface Result {
        data object Disabled : Result
        data object Hidden : Result
        data class Success(val entries: List<SequelPrequelEntry>) : Result
    }
}
// KMK <--
