package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context
import ca.mpreg.webgpuviewer.reader.PageAnchor

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
    // unknown (read once, then maintained locally).
    private var entryCount = -1

    private fun takeSaveSlot(chapterId: Long, now: Long, force: Boolean): Boolean {
        if (force || chapterId != lastSavedChapterId) {
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

    // KMK --> Single writer: every overload maps scalars onto PageAnchor and serializes
    // the same v3 7-field record so save and loadAnchor stay interchangeable.
    fun save(chapterId: Long, pageIndex: Int, offsetRatio: Float = 0f, zoom: Float = 1f) {
        writeAnchor(
            chapterId,
            PageAnchor(
                pageIndex = pageIndex.coerceIn(0, PageAnchor.MAX_PAGE_INDEX),
                fraction = offsetRatio,
                scale = zoom,
            ),
        )
    }

    fun savePaged(chapterId: Long, pageIndex: Int, zoom: Float, offsetX: Float = 0f) {
        writeAnchor(
            chapterId,
            PageAnchor(
                pageIndex = pageIndex.coerceIn(0, PageAnchor.MAX_PAGE_INDEX),
                offsetX = offsetX,
                scale = zoom,
            ),
        )
    }

    fun saveDocument(chapterId: Long, pageIndex: Int, documentY: Float, zoom: Float, offsetX: Float) {
        writeAnchor(
            chapterId,
            PageAnchor(
                pageIndex = pageIndex.coerceIn(0, PageAnchor.MAX_PAGE_INDEX),
                documentY = documentY,
                offsetX = offsetX,
                scale = zoom,
            ),
        )
    }

    fun savePosition(
        chapterId: Long,
        pageIndex: Int,
        documentY: Float,
        scale: Float,
        offsetX: Float,
        fraction: Float,
    ) {
        writeAnchor(
            chapterId,
            PageAnchor(
                pageIndex = pageIndex.coerceIn(0, PageAnchor.MAX_PAGE_INDEX),
                documentY = documentY,
                offsetX = offsetX,
                scale = scale,
                fraction = fraction,
            ),
        )
    }

    fun saveAnchor(chapterId: Long, anchor: PageAnchor, force: Boolean = false) {
        writeAnchor(chapterId, anchor, force)
    }

    fun loadAnchor(chapterId: Long): PageAnchor? = load(chapterId)?.toAnchor()

    private fun writeAnchor(chapterId: Long, anchor: PageAnchor, force: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            if (!takeSaveSlot(chapterId, now, force)) return
            val storageKey = key(chapterId)
            val isNewEntry = !prefs.contains(storageKey)
            val a = anchor.sanitized()
            val fields = listOf(
                CURRENT_VERSION,
                a.pageIndex.toString(),
                a.documentY.toString(),
                a.offsetX.toString(),
                a.scale.toString(),
                a.fraction.toString(),
                now.toString(),
            )
            prefs.edit().putString(storageKey, fields.joinToString("|")).apply()
            pruneIfNeeded(isNewEntry)
        } catch (_: Exception) {}
    }
    // KMK <--

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
            val storageKey = key(chapterId)
            if (prefs.contains(storageKey) && entryCount > 0) entryCount--
            prefs.edit().remove(storageKey).apply()
        } catch (_: Exception) {}
    }

    private fun pruneIfNeeded(isNewEntry: Boolean) {
        try {
            if (entryCount < 0) {
                entryCount = prefs.all.size
            } else if (isNewEntry) {
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

        // KMK --> Bridge into the library anchor type used by saveAnchor/loadAnchor.
        fun toAnchor(): PageAnchor = PageAnchor(
            pageIndex = pageIndex,
            documentY = documentY,
            offsetX = offsetX,
            scale = zoom,
            fraction = fraction,
        )
        // KMK <--
    }
}
