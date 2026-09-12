package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** No-op stub of [YakuyomiEngine] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class YakuyomiEngine {

    val notEnoughMemoryReason: String
        get() = "AI translation is not available in this build"

    fun isHardwareSupported(): Boolean = false

    fun prewarm(): Boolean = false

    fun bitmapToWebP(bitmap: android.graphics.Bitmap, quality: Int = 85): ByteArray = ByteArray(0)
}

/** Functional store for the no-MTL variant: per-manga translate toggle still works for off-device services. */
@SingleIn(AppScope::class)
@Inject
class TranslateMangaStore(
    private val preferenceStore: PreferenceStore,
) {
    private fun pref(mangaId: Long): Preference<Boolean> =
        preferenceStore.getBoolean("yakuyomi_translate_manga_$mangaId", false)

    fun isEnabled(mangaId: Long): Boolean = pref(mangaId).get()

    fun setEnabled(mangaId: Long, enabled: Boolean) {
        pref(mangaId).set(enabled)
    }

    fun toggle(mangaId: Long): Boolean {
        val next = !isEnabled(mangaId)
        setEnabled(mangaId, next)
        return next
    }

    fun asFlow(mangaId: Long): Flow<Boolean> = pref(mangaId).changes()

    fun getPreference(mangaId: Long): Preference<Boolean> = pref(mangaId)

    fun clear(mangaId: Long) {
        runCatching { pref(mangaId).delete() }
    }
}

@SingleIn(AppScope::class)
@Inject
class TranslationCache(
    private val context: android.content.Context,
) {
    companion object {
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        private const val MAX_FILE_AGE_DAYS = 14L
        private const val MAX_FILE_SIZE = 5L * 1024 * 1024
    }

    private fun cacheDir(): java.io.File = java.io.File(context.cacheDir, "yakuyomi").apply { mkdirs() }

    fun key(pageHash: String, targetLang: String, model: String): String {
        val raw = "$pageHash|$targetLang|$model"
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) } + ".webp"
    }

    fun pageHash(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun getFile(pageHash: String, targetLang: String, model: String): java.io.File =
        java.io.File(cacheDir(), key(pageHash, targetLang, model))

    fun getIfExists(pageHash: String, targetLang: String, model: String): java.io.File? {
        if (pageHash.length != 64 || !pageHash.matches(Regex("[0-9a-f]{64}"))) return null
        val f = getFile(pageHash, targetLang, model)
        return f.takeIf { it.exists() && it.length() in 1..MAX_FILE_SIZE }
    }

    @Synchronized
    fun put(pageHash: String, targetLang: String, model: String, webpBytes: ByteArray): java.io.File {
        require(pageHash.matches(Regex("[0-9a-f]{64}"))) { "invalid pageHash" }
        require(webpBytes.size in 1..MAX_FILE_SIZE.toInt()) { "invalid webpBytes size ${webpBytes.size}" }
        require(targetLang.isNotBlank() && targetLang.length <= 10) { "invalid targetLang" }
        val f = getFile(pageHash, targetLang, model)
        f.parentFile?.mkdirs()
        val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        try {
            tmp.writeBytes(webpBytes)
            if (tmp.length() != webpBytes.size.toLong()) throw IllegalStateException("tmp write incomplete")
            if (f.exists() && !f.delete()) throw IllegalStateException("cannot replace cache file")
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                if (f.length() != webpBytes.size.toLong()) throw IllegalStateException("copy failed")
                tmp.delete()
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            return f
        }
        pruneIfNeeded()
        return f
    }

    fun hashBytes(bytes: ByteArray): String = pageHash(bytes)

    fun clearForManga(mangaId: Long) = Unit

    fun clearAll() {
        try {
            cacheDir().listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun pruneIfNeeded() {
        try {
            val dir = cacheDir()
            val files = dir.listFiles()?.filter { it.isFile && it.extension == "webp" } ?: return
            val now = System.currentTimeMillis()
            val maxAgeMs = MAX_FILE_AGE_DAYS * 24 * 60 * 60 * 1000
            files.filter { now - it.lastModified() > maxAgeMs }.forEach { it.delete() }
            val remaining = dir.listFiles()?.filter { it.isFile && it.extension == "webp" }?.sortedBy { it.lastModified() } ?: return
            var total = remaining.sumOf { it.length() }
            for (f in remaining) {
                if (total <= MAX_CACHE_BYTES) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        } catch (_: Exception) {}
    }

    fun sizeBytes(): Long {
        return try {
            cacheDir().listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}

@SingleIn(AppScope::class)
@Inject
class TranslatedPageStore(
    private val context: android.content.Context,
) {
    companion object {
        private const val MAX_SAVED_BYTES = 256L * 1024 * 1024
        private const val MAX_SAVED_CHAPTERS = 40
    }

    private fun baseDir(): java.io.File = java.io.File(context.filesDir, "yakuyomi_saved").apply { mkdirs() }
    private fun chapterDir(mangaId: Long, chapterId: Long): java.io.File =
        java.io.File(baseDir(), "$mangaId/$chapterId").apply { mkdirs() }
    fun pageFile(mangaId: Long, chapterId: Long, pageIndex: Int): java.io.File =
        java.io.File(chapterDir(mangaId, chapterId), "page_$pageIndex.webp")
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
        if (webpBytes.isEmpty() || webpBytes.size > 5 * 1024 * 1024) return
        if (pageIndex < 0 || pageIndex > 5000) return
        val f = pageFile(mangaId, chapterId, pageIndex)
        try {
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(webpBytes)
            if (tmp.length() != webpBytes.size.toLong()) throw IllegalStateException("tmp incomplete")
            if (f.exists() && !f.delete()) throw IllegalStateException("cannot replace")
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

    @Synchronized
    fun pruneIfNeeded() {
        try {
            val base = baseDir()
            val chapters = base.listFiles()?.filter { it.isDirectory }?.flatMap { manga -> manga.listFiles()?.filter { it.isDirectory } ?: emptyList() } ?: return
            var total = chapters.sumOf { dir -> dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L }
            if (chapters.size <= MAX_SAVED_CHAPTERS && total <= MAX_SAVED_BYTES) return
            val ordered = chapters.sortedBy { it.lastModified() }
            for (dir in ordered) {
                if (chapters.size - ordered.indexOf(dir) <= MAX_SAVED_CHAPTERS && total <= MAX_SAVED_BYTES) break
                val size = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
                if (dir.deleteRecursively()) total -= size
            }
        } catch (_: Exception) {}
    }
}

/** Functional breadcrumb notes for the no-MTL variant: keeps off-device context. */
@SingleIn(AppScope::class)
@Inject
class BreadcrumbNotes(
    private val context: android.content.Context,
) {
    private fun file(mangaId: Long): java.io.File = java.io.File(context.filesDir, "yakuyomi_notes/$mangaId.json").apply { parentFile?.mkdirs() }
    fun buildContextPrompt(mangaId: Long, budget: Int = 1000): String {
        return try {
            file(mangaId).takeIf { it.exists() }?.readText()?.take(budget) ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    fun buildContextPrompt(mangaId: Long): String = buildContextPrompt(mangaId, 1000)
    fun appendFromTranslation(mangaId: Long, chapterId: Long, texts: List<String>) {
        try {
            val f = file(mangaId)
            val existing = f.takeIf { it.exists() }?.readText() ?: ""
            val appended = (existing + "\n" + texts.joinToString("\n")).take(8000)
            f.writeText(appended)
        } catch (_: Exception) {}
    }
}
