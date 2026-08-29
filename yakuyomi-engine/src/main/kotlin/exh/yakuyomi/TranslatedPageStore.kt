package exh.yakuyomi

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

@SingleIn(AppScope::class)
@Inject
class TranslatedPageStore(
    private val context: Context,
) {
    companion object {
        // Keep disk usage bounded: these are per-chapter saved WEBPs, separate from the hash cache.
        private const val MAX_SAVED_BYTES = 256L * 1024 * 1024
        private const val MAX_SAVED_CHAPTERS = 40
    }

    private fun baseDir(): File = File(context.filesDir, "yakuyomi_saved").apply { mkdirs() }

    private fun chapterDir(mangaId: Long, chapterId: Long): File =
        File(baseDir(), "$mangaId/$chapterId").apply { mkdirs() }

    fun pageFile(mangaId: Long, chapterId: Long, pageIndex: Int): File =
        File(chapterDir(mangaId, chapterId), "page_$pageIndex.webp")

    fun loadIfExists(mangaId: Long, chapterId: Long, pageIndex: Int): ByteArray? {
        val f = pageFile(mangaId, chapterId, pageIndex)
        return if (f.exists() && f.length() > 0) {
            try {
                f.readBytes()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    fun save(mangaId: Long, chapterId: Long, pageIndex: Int, webpBytes: ByteArray) {
        val f = pageFile(mangaId, chapterId, pageIndex)
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(webpBytes)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {}
        pruneIfNeeded()
    }

    fun clearForChapter(mangaId: Long, chapterId: Long) {
        try {
            chapterDir(mangaId, chapterId).deleteRecursively()
        } catch (_: Exception) {}
    }

    fun clearAll() {
        try {
            baseDir().listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {}
    }

    /** Evicts the oldest chapters (by last-modified) until under the byte/chapter caps. */
    @Synchronized
    fun pruneIfNeeded() {
        try {
            val base = baseDir()
            // Stored as <base>/<mangaId>/<chapterId>.
            val chapters = base.listFiles()?.filter { it.isDirectory }?.flatMap { manga ->
                manga.listFiles()?.filter { it.isDirectory } ?: emptyList()
            } ?: return
            if (chapters.size <= MAX_SAVED_CHAPTERS) {
                val total = chapters.sumOf { dir -> dir.listFiles()?.sumOf { it.length() } ?: 0L }
                if (total <= MAX_SAVED_BYTES) return
            }
            val ordered = chapters.sortedBy { it.lastModified() }
            for (dir in ordered) {
                if (ordered.size - ordered.indexOf(dir) <= MAX_SAVED_CHAPTERS / 2) break
                dir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }
}
