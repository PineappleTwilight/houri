package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context

class WebGpuReadingPositionStore(
    private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences("webgpu_positions", Context.MODE_PRIVATE) }

    fun save(chapterId: Long, pageIndex: Int, offsetRatio: Float = 0f, zoom: Float = 1f) {
        try {
            val safeIndex = pageIndex.coerceAtLeast(0).coerceAtMost(9999)
            val safeOffset = offsetRatio.coerceIn(0f, 1f)
            val safeZoom = zoom.coerceIn(0.5f, 8f)
            val v = "$safeIndex|$safeOffset|$safeZoom|${System.currentTimeMillis()}"
            prefs.edit().putString(key(chapterId), v).apply()
            pruneIfNeeded()
        } catch (_: Exception) {}
    }

    fun load(chapterId: Long): PositionData? {
        return try {
            val raw = prefs.getString(key(chapterId), null) ?: return null
            val p = raw.split("|")
            if (p.size < 3) return null
            val idx = p[0].toInt().coerceAtLeast(0)
            val off = p[1].toFloat().coerceIn(0f, 1f)
            val zom = p[2].toFloat().coerceIn(0.5f, 8f)
            if (p.size >= 4) {
                val ts = p[3].toLongOrNull() ?: 0L
                if (System.currentTimeMillis() - ts > 30L * 24 * 60 * 60 * 1000) return null
            }
            PositionData(idx, off, zom)
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
                val oldest = prefs.all.entries.sortedBy { (it.value as? String)?.substringAfterLast("|")?.toLongOrNull() ?: 0L }.take(100)
                val ed = prefs.edit()
                oldest.forEach { ed.remove(it.key) }
                ed.apply()
            }
        } catch (_: Exception) {}
    }

    private fun key(chapterId: Long) = "pos_$chapterId"

    data class PositionData(val pageIndex: Int, val offsetRatio: Float, val zoom: Float)
}
