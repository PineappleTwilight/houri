package eu.kanade.tachiyomi.data.cache

import android.content.Context
import android.text.format.Formatter
import com.jakewharton.disklrucache.DiskLruCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.saveTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.concurrency.AppDispatchersHolder
import okhttp3.Response
import okio.buffer
import okio.sink
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.io.IOException

/**
 * Class used to create chapter cache
 * For each image in a chapter a file is created
 * For each chapter a Json list is created and converted to a file.
 * The files are in format *md5key*.0
 *
 * @param context the application context.
 */
@Inject
@SingleIn(AppScope::class)
class ChapterCache(
    private val context: Context,
    private val json: Json,
    // SY -->
    readerPreferences: ReaderPreferences,
    // SY <--
) {

    // --> EH
    private val scope = CoroutineScope(Job() + AppDispatchersHolder.get().io)

    /** Cache class used for cache management.  */
    private var diskCache = setupDiskCache(readerPreferences.cacheSize().get().toLong())

    init {
        readerPreferences.cacheSize().changes()
            .drop(1)
            .onEach {
                // Save old cache for destruction later
                val oldCache = diskCache
                diskCache = setupDiskCache(it.toLong())
                oldCache.close()
            }
            .launchIn(scope)
    }
    // <-- EH

    /**
     * Returns directory of cache.
     */
    private val cacheDir: File = diskCache.directory

    /**
     * Returns real size of directory.
     */
    private val realSize: Long
        get() = DiskUtil.getDirectorySize(cacheDir)

    /**
     * Returns real size of directory in human readable format.
     */
    val readableSize: String
        get() = Formatter.formatFileSize(context, realSize)

    // --> EH
    // Cache size is in MB
    private fun setupDiskCache(cacheSize: Long): DiskLruCache {
        return DiskLruCache.open(
            File(context.cacheDir, "chapter_disk_cache"),
            PARAMETER_APP_VERSION,
            PARAMETER_VALUE_COUNT,
            cacheSize * 1024 * 1024,
        )
    }
    // <-- EH

    /**
     * Get page list from cache.
     *
     * @param chapter the chapter.
     * @return the list of pages.
     */
    fun getPageListFromCache(chapter: Chapter): List<Page> {
        val pages = readPageList(chapter)

        // KMK --> A read is an access: refresh the entry's retention clock.
        touch(cacheFileFor(getKey(chapter)))
        // KMK <--

        return pages
    }

    private fun readPageList(chapter: Chapter): List<Page> {
        // Get the key for the chapter.
        val key = DiskUtil.hashKeyForDisk(getKey(chapter))

        // Convert JSON string to list of objects. Throws an exception if snapshot is null
        return diskCache.get(key).use {
            json.decodeFromString(it.getString(0))
        }
    }

    /**
     * Add page list to disk cache.
     *
     * @param chapter the chapter.
     * @param pages list of pages.
     */
    fun putPageListToCache(chapter: Chapter, pages: List<Page>) {
        // Convert list of pages to json string.
        val cachedValue = json.encodeToString(pages)

        // Initialize the editor (edits the values for an entry).
        var editor: DiskLruCache.Editor? = null

        try {
            // Get editor from md5 key.
            val key = DiskUtil.hashKeyForDisk(getKey(chapter))
            editor = diskCache.edit(key) ?: return

            // Write chapter urls to cache.
            editor.newOutputStream(0).sink().buffer().use {
                it.write(cachedValue.toByteArray())
                it.flush()
            }

            diskCache.flush()
            editor.commit()
            editor.abortUnlessCommitted()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to put page list to cache" }
            // Ignore.
        } finally {
            editor?.abortUnlessCommitted()
        }
    }

    /**
     * Returns true if image is in cache.
     *
     * @param imageUrl url of image.
     * @return true if in cache otherwise false.
     */
    fun isImageInCache(imageUrl: String): Boolean {
        return try {
            diskCache.get(DiskUtil.hashKeyForDisk(imageUrl)).use { it != null }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Get image file from url.
     *
     * @param imageUrl url of image.
     * @return path of image.
     */
    fun getImageFile(imageUrl: String): File {
        // Get file from md5 key.
        val imageName = DiskUtil.hashKeyForDisk(imageUrl) + ".0"
        val file = File(diskCache.directory, imageName)

        // KMK --> Serving an image is an access: refresh its retention clock.
        touch(file)
        // KMK <--

        return file
    }

    /**
     * Add image to cache.
     *
     * @param imageUrl url of image.
     * @param response http response from page.
     * @throws IOException image error.
     */
    @Throws(IOException::class)
    fun putImageToCache(imageUrl: String, response: Response) {
        // Initialize editor (edits the values for an entry).
        var editor: DiskLruCache.Editor? = null

        try {
            // Get editor from md5 key.
            val key = DiskUtil.hashKeyForDisk(imageUrl)
            editor = diskCache.edit(key) ?: return

            // Get OutputStream and write image with Okio.
            response.body.source().saveTo(editor.newOutputStream(0))

            diskCache.flush()
            editor.commit()
        } finally {
            response.body.close()
            editor?.abortUnlessCommitted()
        }
    }

    /**
     * Add a locally produced image to cache.
     *
     * [imageUrl] is only a cache key here, not something that can be fetched - callers pass
     * synthetic keys for images the app made itself.
     */
    @Throws(IOException::class)
    fun putImageToCache(imageUrl: String, bytes: ByteArray) {
        var editor: DiskLruCache.Editor? = null

        try {
            val key = DiskUtil.hashKeyForDisk(imageUrl)
            editor = diskCache.edit(key) ?: return

            editor.newOutputStream(0).use { it.write(bytes) }

            diskCache.flush()
            editor.commit()
        } finally {
            editor?.abortUnlessCommitted()
        }
    }

    // KMK -->

    /** Total bytes the cache directory currently holds (journal included, approximate). */
    val sizeBytes: Long
        get() = realSize

    /**
     * Removes every cache entry whose file has aged past [retentionMs] since its last access.
     * Returns the number of entries removed. Retention of 0 (or less) disables the sweep.
     */
    fun sweepExpired(retentionMs: Long): Int {
        if (retentionMs <= 0L) return 0
        val now = System.currentTimeMillis()
        var removed = 0
        cacheDir.listFiles()?.forEach {
            // Journal files are guarded inside removeFileFromCache.
            if (ChapterCacheRetention.isExpired(it.lastModified(), now, retentionMs) &&
                removeFileFromCache(it.name)
            ) {
                removed++
            }
        }
        return removed
    }

    /**
     * Drops a chapter's cached page list along with every page image still cached for it
     * (segment keys included).
     */
    fun removePageListFromCache(chapter: Chapter) {
        val pages = try {
            readPageList(chapter)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to read cached page list for removal" }
            null
        }
        pages?.forEach { page ->
            val imageUrl = page.imageUrl ?: return@forEach
            removeImageFromCache(imageUrl)
            var index = 0
            while (removeImageFromCache(imageUrl + CHAPTER_CACHE_SEGMENT_KEY_SUFFIX + index)) {
                index++
            }
        }
        removeFileFromCache(DiskUtil.hashKeyForDisk(getKey(chapter)) + ".0")
    }

    /**
     * Size/age summary of everything cached for one chapter: its page list entry plus the image
     * files the stored page list still points at (segment keys included). This is a query - it
     * does not refresh any retention clock. Images whose page list was evicted cannot be
     * attributed to a chapter and are invisible here.
     */
    fun getChapterCacheInfo(chapter: Chapter): ChapterCacheInfo? {
        val pageListFile = cacheFileFor(getKey(chapter))
        if (!pageListFile.exists()) return null

        val pages = try {
            readPageList(chapter)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to read cached page list" }
            return null
        }

        var totalBytes = pageListFile.length()
        var oldestModified = pageListFile.lastModified()
        var imageCount = 0

        pages.asSequence()
            .mapNotNull { it.imageUrl }
            .forEach { imageUrl ->
                var misses = 0
                var index = -1 // -1 probes the unsegmented image itself
                while (misses < SEGMENT_PROBE_MISS_LIMIT && index <= SEGMENT_PROBE_MAX_INDEX) {
                    val key = if (index < 0) imageUrl else imageUrl + CHAPTER_CACHE_SEGMENT_KEY_SUFFIX + index
                    val file = cacheFileFor(key)
                    if (file.exists()) {
                        misses = 0
                        imageCount++
                        totalBytes += file.length()
                        val modified = file.lastModified()
                        if (modified < oldestModified) oldestModified = modified
                    } else {
                        misses++
                    }
                    index++
                }
            }

        return ChapterCacheInfo(totalBytes, oldestModified, imageCount)
    }

    private fun cacheFileFor(key: String): File {
        return File(diskCache.directory, DiskUtil.hashKeyForDisk(key) + ".0")
    }

    /** Refreshes a file's mtime - the sliding part of the retention clock. */
    private fun touch(file: File) {
        if (file.exists()) {
            runCatching { file.setLastModified(System.currentTimeMillis()) }
                .onFailure { logcat(LogPriority.DEBUG) { "Failed to touch cache file: ${file.name}" } }
        }
    }
    // KMK <--

    fun clear(): Int {
        var deletedFiles = 0
        cacheDir.listFiles()?.forEach {
            if (removeFileFromCache(it.name)) {
                deletedFiles++
            }
        }
        return deletedFiles
    }

    /**
     * Removes a locally produced image from the cache.
     *
     * Only reachable for app-generated images: real pages are dropped by [clear] or by the LRU
     * eviction the size limit causes. Segment keys are derived rather than downloaded, and a
     * re-split can produce fewer segments than the last one, so the tail has to be droppable
     * entry by entry - there was no way to do that before.
     */
    fun removeImageFromCache(imageUrl: String): Boolean =
        removeFileFromCache(DiskUtil.hashKeyForDisk(imageUrl) + ".0")

    /**
     * Remove file from cache.
     *
     * @param file name of file "md5.0".
     * @return status of deletion for the file.
     */
    private fun removeFileFromCache(file: String): Boolean {
        // Make sure we don't delete the journal file (keeps track of cache)
        if (file == "journal" || file.startsWith("journal.")) {
            return false
        }

        return try {
            // Remove the extension from the file to get the key of the cache
            val key = file.substringBeforeLast(".")
            // Remove file from cache
            diskCache.remove(key)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to remove file from cache" }
            false
        }
    }

    private fun getKey(chapter: Chapter): String {
        return "${chapter.mangaId}${chapter.url}"
    }
}

/** Application cache version.  */
private const val PARAMETER_APP_VERSION = 1

/** The number of values per cache entry. Must be positive.  */
private const val PARAMETER_VALUE_COUNT = 1

/** The maximum number of bytes this cache should use to store.  */
private const val PARAMETER_CACHE_SIZE = 100L * 1024 * 1024

// KMK -->
/** Cached-data summary for one chapter, as reported by [ChapterCache.getChapterCacheInfo]. */
data class ChapterCacheInfo(
    val totalBytes: Long,
    val oldestLastModifiedMs: Long,
    val imageCount: Int,
)

/** How many consecutive absent segment keys a cache-info probe tolerates before giving up. */
private const val SEGMENT_PROBE_MISS_LIMIT = 4

/** Hard upper bound on the segment indexes a cache-info probe walks. */
private const val SEGMENT_PROBE_MAX_INDEX = 64
// KMK <--
