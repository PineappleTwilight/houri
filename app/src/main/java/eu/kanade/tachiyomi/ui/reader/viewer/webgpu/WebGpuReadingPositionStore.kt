package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context

class WebGpuReadingPositionStore(
    private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences("webgpu_positions", Context.MODE_PRIVATE) }

    fun save(chapterId: Long, pageIndex: Int, offsetRatio: Float = 0f, zoom: Float = 1f) {
        // Legacy overload kept for compat — offsetRatio may be old 0..1 fraction or new documentY
        // Detect intent: if offsetRatio > 2f treat as documentY, otherwise fraction
        if (offsetRatio > 2f) {
            saveDocument(chapterId, pageIndex, offsetRatio, zoom, 0f)
        } else {
            // old fraction path — preserve but migrate to v2 on next save via new overload
            try {
                val safeIndex = pageIndex.coerceAtLeast(0).coerceAtMost(9999)
                val safeFraction = offsetRatio.coerceIn(0f, 1f)
                val safeZoom = zoom.coerceIn(0.5f, 8f)
                val v = "v2|$safeIndex|$safeFraction|0.0|$safeZoom|${System.currentTimeMillis()}"
                // store fraction in documentY slot for migration; loader will detect small value + pageIndex
                prefs.edit().putString(key(chapterId), v).apply()
                pruneIfNeeded()
            } catch (_: Exception) {}
        }
    }

    fun saveDocument(chapterId: Long, pageIndex: Int, documentY: Float, zoom: Float, offsetX: Float) {
        try {
            val safeIndex = pageIndex.coerceAtLeast(0).coerceAtMost(9999)
            val safeDocY = when {
                !documentY.isFinite() -> 0f
                documentY < 0f -> 0f
                documentY > 1e7f -> 1e7f
                else -> documentY
            }
            val safeZoom = zoom.coerceIn(0.5f, 8f).let { if (!it.isFinite()) 1f else it }
            val safeOffsetX = offsetX.coerceIn(-1f, 1f).let { if (!it.isFinite()) 0f else it }
            val v = "v2|$safeIndex|$safeDocY|$safeOffsetX|$safeZoom|${System.currentTimeMillis()}"
            prefs.edit().putString(key(chapterId), v).apply()
            pruneIfNeeded()
        } catch (_: Exception) {}
    }

    fun savePosition(chapterId: Long, pageIndex: Int, documentY: Float, scale: Float, offsetX: Float, fraction: Float) {
        try {
            val safeIndex = pageIndex.coerceAtLeast(0).coerceAtMost(9999)
            val safeDocY = documentY.coerceIn(0f, 1e7f).let { if (!it.isFinite()) 0f else it }
            val safeScale = scale.coerceIn(0.5f, 8f).let { if (!it.isFinite()) 1f else it }
            val safeOffsetX = offsetX.coerceIn(-1f, 1f).let { if (!it.isFinite()) 0f else it }
            val safeFraction = fraction.coerceIn(0f, 1f).let { if (!it.isFinite()) 0f else it }
            val v = "v2|$safeIndex|$safeDocY|$safeOffsetX|$safeScale|$safeFraction|${System.currentTimeMillis()}"
            prefs.edit().putString(key(chapterId), v).apply()
            pruneIfNeeded()
        } catch (_: Exception) {}
    }

    fun load(chapterId: Long): PositionData? {
        return try {
            val raw = prefs.getString(key(chapterId), null) ?: return null
            if (raw.startsWith("v2|")) {
                val p = raw.split("|")
                when (p.size) {
                    6 -> {
                        // v2|pageIndex|documentY|offsetX|zoom|timestamp
                        val idx = p[1].toIntOrNull()?.coerceAtLeast(0) ?: return null
                        val docY = p[2].toFloatOrNull()?.coerceIn(0f, 1e7f) ?: return null
                        val offX = p[3].toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f
                        val zom = p[4].toFloatOrNull()?.coerceIn(0.5f, 8f) ?: 1f
                        val ts = p[5].toLongOrNull() ?: 0L
                        if (System.currentTimeMillis() - ts > 30L * 24 * 60 * 60 * 1000) return null
                        val fraction = if (docY <= 1f) docY else 0f
                        PositionData(idx, docY, zom, offX, fraction, isV2 = true)
                    }
                    7 -> {
                        // v2|pageIndex|documentY|offsetX|zoom|fraction|timestamp
                        val idx = p[1].toIntOrNull()?.coerceAtLeast(0) ?: return null
                        val docY = p[2].toFloatOrNull()?.coerceIn(0f, 1e7f) ?: return null
                        val offX = p[3].toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f
                        val zom = p[4].toFloatOrNull()?.coerceIn(0.5f, 8f) ?: 1f
                        val frac = p[5].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
                        val ts = p[6].toLongOrNull() ?: 0L
                        if (System.currentTimeMillis() - ts > 30L * 24 * 60 * 60 * 1000) return null
                        PositionData(idx, docY, zom, offX, frac, isV2 = true)
                    }
                    else -> null
                }
            } else {
                // legacy: pageIndex|offsetRatio|zoom|timestamp
                val p = raw.split("|")
                if (p.size < 3) return null
                val idx = p[0].toIntOrNull()?.coerceAtLeast(0) ?: return null
                val off = p[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
                val zom = p[2].toFloatOrNull()?.coerceIn(0.5f, 8f) ?: 1f
                if (p.size >= 4) {
                    val ts = p[3].toLongOrNull() ?: 0L
                    if (ts != 0L && System.currentTimeMillis() - ts > 30L * 24 * 60 * 60 * 1000) return null
                }
                // off is fraction, not documentY — preserve as fraction for caller to resolve
                PositionData(idx, off, zom, 0f, off, isV2 = false)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(chapterId: Long) {
        try {
            prefs.edit().remove(key(chapterId)).apply()
        } catch (_: Exception) {}
    }

    private fun pruneIfNeeded() {
        try {
            if (prefs.all.size > 500) {
                val entries = prefs.all.entries.mapNotNull { e ->
                    val v = e.value as? String ?: return@mapNotNull null
                    val ts = v.split("|").lastOrNull()?.toLongOrNull() ?: 0L
                    e.key to ts
                }.sortedBy { it.second }
                val toRemove = entries.take(100)
                val ed = prefs.edit()
                toRemove.forEach { ed.remove(it.first) }
                ed.apply()
            }
        } catch (_: Exception) {}
    }

    private fun key(chapterId: Long) = "pos_$chapterId"

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
