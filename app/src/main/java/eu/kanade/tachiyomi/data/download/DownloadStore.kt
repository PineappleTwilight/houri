package eu.kanade.tachiyomi.data.download

import android.content.Context
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.app.di.globalAppGraph
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * This class is used to persist active downloads across application restarts.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadStore(
    context: Context,
    private val sourceManager: SourceManager = globalAppGraph.sourceManager,
    private val json: Json = globalAppGraph.json,
    private val getManga: GetManga = globalAppGraph.getManga,
    private val getChapter: GetChapter = globalAppGraph.getChapter,
) {

    /**
     * Preference file where active downloads are stored.
     */
    private val preferences = context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE)

    /**
     * Counter used to keep the queue order.
     */
    private var counter = 0

    /**
     * Adds a list of downloads to the store.
     *
     * @param downloads the list of downloads to add.
     */
    fun addAll(downloads: List<Download>) {
        preferences.edit {
            downloads.forEach { putString(getKey(it), serialize(it)) }
        }
    }

    /**
     * Removes a download from the store.
     *
     * @param download the download to remove.
     */
    fun remove(download: Download) {
        preferences.edit {
            remove(getKey(download))
        }
    }

    /**
     * Removes a list of downloads from the store.
     *
     * @param downloads the download to remove.
     */
    fun removeAll(downloads: List<Download>) {
        preferences.edit {
            downloads.forEach { remove(getKey(it)) }
        }
    }

    /**
     * Removes all the downloads from the store.
     */
    fun clear() {
        preferences.edit {
            clear()
        }
    }

    /**
     * Returns the preference's key for the given download.
     *
     * @param download the download.
     */
    private fun getKey(download: Download): String {
        return download.chapter.id.toString()
    }

    /**
     * Returns the list of downloads to restore. It should be called in a background thread.
     */
    suspend fun restore(): List<Download> {
        val objs = preferences.all
            .mapNotNull { it.value as? String }
            .mapNotNull { deserialize(it) }
            .sortedBy { it.order }

        val downloads = mutableListOf<Download>()
        if (objs.isNotEmpty()) {
            // ConcurrentHashMap rejects nulls, so misses stay uncached and refetch
            // (deleted manga are rare; duplicate fetches are harmless).
            val cachedManga = ConcurrentHashMap<Long, Manga>()
            val semaphore = Semaphore(4)
            val restored = coroutineScope {
                objs.map { (mangaId, chapterId) ->
                    async {
                        semaphore.withPermit {
                            val manga = cachedManga[mangaId]
                                ?: getManga.await(mangaId)?.also { cachedManga[mangaId] = it }
                                ?: return@async null
                            val source = sourceManager.get(manga.source) as? HttpSource ?: return@async null
                            val chapter = getChapter.await(chapterId) ?: return@async null
                            Download(source, manga, chapter)
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            // Preserve queue order (keyed by ids: elements are DownloadObject).
            val order = objs.mapIndexed { index, obj -> (obj.mangaId to obj.chapterId) to index }.toMap()
            downloads.addAll(
                restored.sortedBy { order[it.manga.id to it.chapter.id] ?: Int.MAX_VALUE },
            )
        }

        // Clear the store, downloads will be added again immediately.
        clear()
        return downloads
    }

    /**
     * Converts a download to a string.
     *
     * @param download the download to serialize.
     */
    private fun serialize(download: Download): String {
        val obj = DownloadObject(download.manga.id, download.chapter.id, counter++)
        return json.encodeToString(obj)
    }

    /**
     * Restore a download from a string.
     *
     * @param string the download as string.
     */
    private fun deserialize(string: String): DownloadObject? {
        return try {
            json.decodeFromString<DownloadObject>(string)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Class used for download serialization
 *
 * @param mangaId the id of the manga.
 * @param chapterId the id of the chapter.
 * @param order the order of the download in the queue.
 */
@Serializable
private data class DownloadObject(val mangaId: Long, val chapterId: Long, val order: Int)
