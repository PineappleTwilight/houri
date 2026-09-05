package exh.yakuyomi

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.log.xLogD
import java.io.File
import java.security.MessageDigest

@SingleIn(AppScope::class)
@Inject
class TranslationCache(
    private val context: Context,
) {
    companion object {
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        private const val MAX_FILE_AGE_DAYS = 14L
        private const val MAX_FILE_SIZE = 5L * 1024 * 1024
    }

    private fun cacheDir(): File = File(context.cacheDir, "yakuyomi").apply { mkdirs() }

    fun key(pageHash: String, targetLang: String, model: String): String {
        val raw = "$pageHash|$targetLang|$model"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) } + ".webp"
    }

    fun pageHash(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        // Use full 64-char hex; truncated 16-char had collision risk for 32MB cache
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun getFile(pageHash: String, targetLang: String, model: String): File =
        File(cacheDir(), key(pageHash, targetLang, model))

    fun getIfExists(pageHash: String, targetLang: String, model: String): File? {
        if (pageHash.length != 64 || !pageHash.matches(Regex("[0-9a-f]{64}"))) return null
        val f = getFile(pageHash, targetLang, model)
        return f.takeIf { it.exists() && it.length() in 1..MAX_FILE_SIZE }
    }

    @Synchronized
    fun put(pageHash: String, targetLang: String, model: String, webpBytes: ByteArray): File {
        require(pageHash.matches(Regex("[0-9a-f]{64}"))) { "invalid pageHash" }
        require(webpBytes.size in 1..MAX_FILE_SIZE.toInt()) { "invalid webpBytes size ${webpBytes.size}" }
        require(targetLang.isNotBlank() && targetLang.length <= 10) { "invalid targetLang" }
        val f = getFile(pageHash, targetLang, model)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
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
            xLogD("TranslationCache put failed: ${e.message}")
            runCatching { tmp.delete() }
            return f
        }
        pruneIfNeeded()
        return f
    }

    fun hashBytes(bytes: ByteArray): String = pageHash(bytes)

    fun clearForManga(mangaId: Long) {
        // Per-manga cache is pageHash-based, not mangaId-based; this is best-effort
        // for future use if naming ever includes mangaId. Currently no-op.
        xLogD("TranslationCache clearForManga $mangaId: pageHash cache is global, skipping")
    }

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
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") && now - it.lastModified() > 3600_000 }?.forEach { it.delete() }
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
