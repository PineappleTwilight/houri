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
        // Keep disk usage bounded; baked WEBP per pageHash+targetLang+model
        private const val MAX_CACHE_BYTES = 32L * 1024 * 1024
        private const val MAX_FILE_AGE_DAYS = 30L
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

    fun getIfExists(pageHash: String, targetLang: String, model: String): File? =
        getFile(pageHash, targetLang, model).takeIf { it.exists() && it.length() > 0 }

    @Synchronized
    fun put(pageHash: String, targetLang: String, model: String, webpBytes: ByteArray): File {
        val f = getFile(pageHash, targetLang, model)
        f.parentFile?.mkdirs()
        // Atomic write: write to temp then rename to avoid half-written files on crash
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            tmp.writeBytes(webpBytes)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            xLogD("TranslationCache put failed: ${e.message}")
            try {
                tmp.delete()
            } catch (_: Exception) {}
            // Fallback direct write
            try {
                f.writeBytes(webpBytes)
            } catch (_: Exception) {}
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

    fun pruneIfNeeded() {
        try {
            val dir = cacheDir()
            val files = dir.listFiles()?.filter { it.isFile && it.extension == "webp" } ?: return
            // Evict expired first
            val now = System.currentTimeMillis()
            val maxAgeMs = MAX_FILE_AGE_DAYS * 24 * 60 * 60 * 1000
            files.forEach { f ->
                if (now - f.lastModified() > maxAgeMs) {
                    f.delete()
                }
            }
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
