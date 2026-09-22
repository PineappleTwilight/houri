package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context

class WebGpuReadingPositionStore(
    private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences("webgpu_positions", Context.MODE_PRIVATE) }

    // Coalesces the per-page-crossing write storm: flings report many pages in
    // quick succession, so allow at most one write per window for the same chapter.
    // A different chapter always writes immediately so restores stay exact.
    private var lastSaveAtMs = 0L
    private var lastSavedChapterId = -1L

    // Tracks entry count without deserializing the whole file per save. -1 means
    // unknown (read once, then maintained locally). Overwrites may overcount by one,
    // which only prunes slightly early — never late, never skipped.
    private var entryCount = -1

    private fun takeSaveSlot(chapterId: Long, now: Long): Boolean {
        if (chapterId != lastSavedChapterId) {
            lastSavedChapterId = chapterId
            lastSaveAtMs = now
            return true
        }
        val elapsed = now - lastSaveAtMs
        if (elapsed < 0 || elapsed >= SAVE_THROTTLE_MS) {
            lastSaveAtMs = now
            return true
        }
        return false
    }

    fun save(chapterId: Long, pageIndex: Int, offsetRatio: Float = 0f, zoom: Float = 1f) {
        // KMK --> Legacy overload: offsetRatio is always the old 0..1 page fraction.
        // The old ">2f means documentY" heuristic is gone — it misclassified large
        // fractions/NaN and could never round-trip. Callers needing document scroll
        // use saveDocument/savePaged/savePosition, which all write v3 explicitly.
        saveFraction(chapterId, pageIndex, offsetRatio, zoom)
        // KMK <--
    }

    // KMK --> Explicit fraction saver behind the legacy overload above.
    private fun saveFraction(chapterId: Long, pageIndex: Int, fraction: Float, zoom: Float) {
        try {
            if (!takeSaveSlot(chapterId, System.currentTimeMillis())) return
            val safeIndex = pageIndex.coerceAtLeast(0).coerceAtMost(9999)
            val safeFraction = sanitizeFraction(fraction)
            val safeZoom = sanitizeZoom(zoom)
            val v = "$CURRENT_VERSION|$safeIndex|0.0|0.0|$safeZoom|$safeFraction|${System.currentTimeMillis()}"
            prefs.edit().putString(key(chapterId), v).apply()
            pruneIfNeeded()
        } catch (_: Exception) {}
    }
    // KMK <--

    // KMK --> Paged zoom restore parity with continuous saveDocument: persists the
    // paged viewer's zoom (+ pan offset) instead of dropping them via the legacy
    // fraction overload. documentY is unused in paged mode (always 0).
    fun savePaged(chapterId: Long, pageIndex: Int, zoom: Float, offsetX: Float = 0f) {
        try {
            if (!takeSaveSlot(chapterId, System.currentTimeMillis())) return
            val safeIndex = pageIndex.coerceAtLeast(0).coerceAtMost(9999)
            val safeZoom = sanitizeZoom(zoom)
            val safeOffsetX = sanitizeOffsetX(offsetX)
            val v = "$CURRENT_VERSION|$safeIndex|0.0|$safeOffsetX|$safeZoom|0.0|${System.currentTimeMillis()}"
            prefs.edit().putString(key(chapterId), v).apply()
            pruneIfNeeded()
        } catch (_: Exception) {}
    }
    // KMK <--

    fun saveDocument(chapterId: Long, pageIndex: Int, documentY: Float, zoom: Float, offsetX: Float) {
        try {
            if (!takeSaveSlot(chapterId, System.currentTimeMillis())) return
            val safeIndex = pageIndex.coerceAtLeast(0).coerceAtMost(9999)
            val safeDocY = sanitizeDocumentY(documentY)
            val safeZoom = sanitizeZoom(zoom)
            val safeOffsetX = sanitizeOffsetX(offsetX)
            val v = "$CURRENT_VERSION|$safeIndex|$safeDocY|$safeOffsetX|$safeZoom|${System.currentTimeMillis()}"
            prefs.edit().putString(key(chapterId), v).apply()
            pruneIfNeeded()
        } catch (_: Exception) {}
    }

    fun savePosition(chapterId: Long, pageIndex: Int, documentY: Float, scale: Float, offsetX: Float, fraction: Float) {
        try {
            if (!takeSaveSlot(chapterId, System.currentTimeMillis())) return
            val safeIndex = pageIndex.coerceAtLeast(0).coerceAtMost(9999)
            val safeDocY = sanitizeDocumentY(documentY)
            val safeScale = sanitizeZoom(scale)
            val safeOffsetX = sanitizeOffsetX(offsetX)
            val safeFraction = sanitizeFraction(fraction)
            val v = "$CURRENT_VERSION|$safeIndex|$safeDocY|$safeOffsetX|$safeScale|$safeFraction|${System.currentTimeMillis()}"
            prefs.edit().putString(key(chapterId), v).apply()
            pruneIfNeeded()
        } catch (_: Exception) {}
    }

    fun load(chapterId: Long): PositionData? {
        return try {
            val raw = prefs.getString(key(chapterId), null) ?: return null
            parsePosition(raw, System.currentTimeMillis())
        } catch (_: Exception) {
            null
        }
    }

    fun clear(chapterId: Long) {
        try {
            if (chapterId == lastSavedChapterId) lastSavedChapterId = -1L
            if (entryCount > 0) entryCount--
            prefs.edit().remove(key(chapterId)).apply()
        } catch (_: Exception) {}
    }

    private fun pruneIfNeeded() {
        try {
            if (entryCount < 0) {
                entryCount = prefs.all.size
            } else {
                entryCount++
            }
            if (entryCount > MAX_ENTRIES) {
                val entries = prefs.all.entries.mapNotNull { e ->
                    val v = e.value as? String ?: return@mapNotNull null
                    val ts = v.split("|").lastOrNull()?.toLongOrNull() ?: 0L
                    e.key to ts
                }.sortedBy { it.second }
                val toRemove = entries.take(100)
                val ed = prefs.edit()
                toRemove.forEach { ed.remove(it.first) }
                ed.apply()
                entryCount = prefs.all.size
            }
        } catch (_: Exception) {}
    }

    private fun key(chapterId: Long) = "pos_$chapterId"

    companion object {
        private const val SAVE_THROTTLE_MS = 500L

        // KMK --> Explicit version tag. v3 has the same field layout as the v2
        // 7-field record (pageIndex|documentY|offsetX|zoom|fraction|timestamp);
        // the tag only removes the ambiguity the v2 6-field record had, where a
        // small documentY (<=1) was indistinguishable from a legacy 0..1 fraction
        // and the saver guessed with a ">2f" heuristic. v2 and legacy strings
        // already on disk keep loading via parsePosition below.
        internal const val CURRENT_VERSION = "v3"
        internal const val MAX_ENTRIES = 500
        internal const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        // KMK <--

        // KMK --> NaN/Inf-safe clamps. coerceIn alone propagates NaN (NaN
        // comparisons are false, so NaN.coerceIn returns NaN), which would then
        // serialize as "NaN" and poison the stored record — hence finite first.
        internal fun sanitizeDocumentY(v: Float): Float {
            if (!v.isFinite()) return 0f
            return v.coerceIn(0f, 1e7f)
        }

        internal fun sanitizeZoom(v: Float): Float {
            if (!v.isFinite()) return 1f
            return v.coerceIn(0.5f, 8f)
        }

        internal fun sanitizeOffsetX(v: Float): Float {
            if (!v.isFinite()) return 0f
            return v.coerceIn(-1f, 1f)
        }

        internal fun sanitizeFraction(v: Float): Float {
            if (!v.isFinite()) return 0f
            return v.coerceIn(0f, 1f)
        }
        // KMK <--

        // KMK --> Pure tolerant parser (no prefs/clock reads besides the passed
        // nowMs), so the v3/v2/legacy/corrupt vectors can be checked without
        // Android infra. Versioned records never guess field meaning from value
        // magnitude; only the tagless legacy layout implies fraction semantics.
        internal fun parsePosition(raw: String, nowMs: Long): PositionData? {
            return try {
                if (raw.startsWith("v3|") || raw.startsWith("v2|")) {
                    val p = raw.split("|")
                    when (p.size) {
                        6 -> {
                            // v2|pageIndex|documentY|offsetX|zoom|timestamp (legacy v2 shape)
                            val idx = p[1].toIntOrNull()?.coerceAtLeast(0) ?: return null
                            val docY = sanitizeDocumentY(p[2].toFloatOrNull() ?: return null)
                            val offX = sanitizeOffsetX(p[3].toFloatOrNull() ?: 0f)
                            val zom = sanitizeZoom(p[4].toFloatOrNull() ?: 1f)
                            val ts = p[5].toLongOrNull() ?: 0L
                            if (nowMs - ts > TTL_MS) return null
                            val fraction = if (docY <= 1f) docY else 0f
                            PositionData(idx, docY, zom, offX, fraction, isV2 = true)
                        }
                        7 -> {
                            // v3|pageIndex|documentY|offsetX|zoom|fraction|timestamp
                            // (v2 7-field shares this layout)
                            val idx = p[1].toIntOrNull()?.coerceAtLeast(0) ?: return null
                            val docY = sanitizeDocumentY(p[2].toFloatOrNull() ?: return null)
                            val offX = sanitizeOffsetX(p[3].toFloatOrNull() ?: 0f)
                            val zom = sanitizeZoom(p[4].toFloatOrNull() ?: 1f)
                            val frac = sanitizeFraction(p[5].toFloatOrNull() ?: 0f)
                            val ts = p[6].toLongOrNull() ?: 0L
                            if (nowMs - ts > TTL_MS) return null
                            PositionData(idx, docY, zom, offX, frac, isV2 = true)
                        }
                        else -> null
                    }
                } else {
                    // legacy: pageIndex|offsetRatio|zoom|timestamp
                    val p = raw.split("|")
                    if (p.size < 3) return null
                    val idx = p[0].toIntOrNull()?.coerceAtLeast(0) ?: return null
                    val off = sanitizeFraction(p[1].toFloatOrNull() ?: return null)
                    val zom = sanitizeZoom(p[2].toFloatOrNull() ?: 1f)
                    if (p.size >= 4) {
                        val ts = p[3].toLongOrNull() ?: 0L
                        if (ts != 0L && nowMs - ts > TTL_MS) return null
                    }
                    PositionData(idx, off, zom, 0f, off, isV2 = false)
                }
            } catch (_: Exception) {
                null
            }
        }
        // KMK <--
    }

    data class PositionData(
        val pageIndex: Int,
        val offsetRatio: Float,
        val zoom: Float,
        val offsetX: Float = 0f,
        val fraction: Float = offsetRatio.coerceIn(0f, 1f),
        val isV2: Boolean = false,
    ) {
        val documentY: Float get() = offsetRatio
    }
}
