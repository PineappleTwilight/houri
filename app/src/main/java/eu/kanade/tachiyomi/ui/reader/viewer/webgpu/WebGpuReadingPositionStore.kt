package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context

class WebGpuReadingPositionStore(
    private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences("webgpu_positions", Context.MODE_PRIVATE) }

    fun save(chapterId: Long, pageIndex: Int, offsetRatio: Float = 0f, zoom: Float = 1f) {
        try {
            val v = "${pageIndex.coerceAtLeast(0)}|${offsetRatio.coerceAtLeast(0f)}|${zoom.coerceAtLeast(1f)}|${System.currentTimeMillis()}"
            prefs.edit().putString(key(chapterId), v).apply()
        } catch (_: Exception) {}
    }

    fun load(chapterId: Long): PositionData? {
        return try {
            val raw = prefs.getString(key(chapterId), null) ?: return null
            val p = raw.split("|")
            if (p.size < 3) return null
            PositionData(p[0].toInt(), p[1].toFloat(), p[2].toFloat())
        } catch (_: Exception) {
            null
        }
    }

    fun clear(chapterId: Long) {
        try {
            prefs.edit().remove(key(chapterId)).apply()
        } catch (_: Exception) {}
    }

    private fun key(chapterId: Long) = "pos_$chapterId"

    data class PositionData(val pageIndex: Int, val offsetRatio: Float, val zoom: Float)
}
