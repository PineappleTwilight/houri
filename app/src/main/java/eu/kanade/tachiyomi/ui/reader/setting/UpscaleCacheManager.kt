package eu.kanade.tachiyomi.ui.reader.setting

import java.io.File
import java.security.MessageDigest

/**
 * Cache manager for upscaled pages. Extracted from [UpscaleEngine] to isolate
 * I/O, hashing, and eviction concerns (single-responsibility).
 *
 * Fixes:
 * - B2: Hash now includes only first 64KB + length to avoid SHA-256 of 10MB pages per frame.
 * - B3/B9: Prune and read/write are synchronized to avoid concurrent `listFiles` races.
 */
class UpscaleCacheManager(cacheRoot: File) {

    private val cacheDir: File = File(cacheRoot, "upscale_cache").apply { mkdirs() }
    private val maxCacheBytes = 200L * 1024 * 1024
    private val lock = Any()

    fun cacheKey(bytes: ByteArray, factor: Float, model: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefixLen = minOf(bytes.size, 64 * 1024)
        digest.update(bytes, 0, prefixLen)
        digest.update(bytes.size.toString().toByteArray())
        digest.update("$factor|$model".toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun getCached(key: String): ByteArray? = synchronized(lock) {
        val file = File(cacheDir, "$key.webp")
        if (file.exists() && file.length() > 0) file.readBytes() else null
    }

    fun putCached(key: String, bytes: ByteArray) = synchronized(lock) {
        try {
            File(cacheDir, "$key.webp").writeBytes(bytes)
            pruneIfNeededLocked()
        } catch (_: Exception) {
        }
    }

    fun clear() = synchronized(lock) {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    fun sizeBytes(): Long = synchronized(lock) {
        try {
            cacheDir.listFiles()?.sumOf { it.length() } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun pruneIfNeededLocked() {
        try {
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= maxCacheBytes) break
                total -= f.length()
                f.delete()
            }
        } catch (_: Exception) {
        }
    }
}
