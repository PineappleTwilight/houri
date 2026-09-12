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

    private val cacheDir: File = File(cacheRoot, "upscale_cache").apply {
        try { mkdirs() } catch (_: Exception) {}
    }
    private val maxCacheBytes = 200L * 1024 * 1024
    private val maxSingleFileBytes = 20L * 1024 * 1024
    private val ttlMillis = 30L * 24 * 60 * 60 * 1000
    private val lock = Any()

    fun cacheKey(bytes: ByteArray, factor: Float, model: String): String {
        return cacheKeyDetailed(bytes, factor, model, "", "")
    }

    fun cacheKeyDetailed(
        bytes: ByteArray,
        factor: Float,
        model: String,
        extra1: String,
        extra2: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (bytes.isNotEmpty()) {
            val prefixLen = minOf(bytes.size, 32 * 1024)
            digest.update(bytes, 0, prefixLen)
            if (bytes.size > 64 * 1024) {
                val mid = bytes.size / 2
                val midLen = minOf(16 * 1024, bytes.size - mid)
                digest.update(bytes, mid, midLen)
                val tailLen = minOf(16 * 1024, bytes.size)
                digest.update(bytes, bytes.size - tailLen, tailLen)
            } else if (bytes.size > prefixLen) {
                val tailLen = minOf(16 * 1024, bytes.size - prefixLen)
                digest.update(bytes, bytes.size - tailLen, tailLen)
            }
        }
        digest.update(bytes.size.toString().toByteArray())
        val safeFactor = if (factor.isFinite()) factor else 2f
        val normalizedExtras = buildString {
            append(safeFactor)
            append('|')
            append(model.take(64))
            if (extra1.isNotEmpty()) { append('|'); append(extra1.take(64)) }
            if (extra2.isNotEmpty()) { append('|'); append(extra2.take(64)) }
        }
        digest.update(normalizedExtras.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun getCached(key: String): ByteArray? = synchronized(lock) {
        if (!isValidKey(key)) return null
        val file = File(cacheDir, "$key.webp")
        try {
            if (!file.exists() || file.length() <= 0 || file.length() > maxSingleFileBytes) {
                if (file.length() > maxSingleFileBytes) try { file.delete() } catch (_: Exception) {}
                return null
            }
            if (ttlMillis > 0 && System.currentTimeMillis() - file.lastModified() > ttlMillis) {
                try { file.delete() } catch (_: Exception) {}
                return null
            }
            file.readBytes().also {
                try { file.setLastModified(System.currentTimeMillis()) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            null
        }
    }

    fun putCached(key: String, bytes: ByteArray) = synchronized(lock) {
        if (!isValidKey(key)) return
        if (bytes.isEmpty() || bytes.size > maxSingleFileBytes) return
        try {
            ensureCacheDir()
            val target = File(cacheDir, "$key.webp")
            val tmp = File(cacheDir, "$key.tmp.${System.nanoTime()}")
            tmp.writeBytes(bytes)
            if (tmp.length() != bytes.size.toLong()) {
                try { tmp.delete() } catch (_: Exception) {}
                return
            }
            if (!tmp.renameTo(target)) {
                try { target.delete() } catch (_: Exception) {}
                tmp.renameTo(target)
            }
            pruneIfNeededLocked()
        } catch (_: Exception) {
        }
    }

    fun clear() = synchronized(lock) {
        try {
            cacheDir.listFiles()?.forEach {
                try { it.delete() } catch (_: Exception) {}
            }
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

    fun fileCount(): Int = synchronized(lock) {
        try { cacheDir.listFiles()?.size ?: 0 } catch (_: Exception) { 0 }
    }

    fun pruneExpired() = synchronized(lock) {
        try {
            val now = System.currentTimeMillis()
            cacheDir.listFiles()?.forEach { f ->
                if (now - f.lastModified() > ttlMillis) try { f.delete() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun ensureCacheDir() {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
        } catch (_: Exception) {}
    }

    private fun isValidKey(key: String): Boolean {
        if (key.length != 64) return false
        for (c in key) if (c !in '0'..'9' && c !in 'a'..'f') return false
        return true
    }

    private fun pruneIfNeededLocked() {
        try {
            pruneExpiredLocked()
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= maxCacheBytes) break
                val len = f.length()
                try { if (f.delete()) total -= len } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
    }

    private fun pruneExpiredLocked() {
        try {
            val now = System.currentTimeMillis()
            cacheDir.listFiles()?.forEach { f ->
                if (now - f.lastModified() > ttlMillis) try { f.delete() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
