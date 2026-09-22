// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.webgpuviewer.filter.Lut3d
import java.io.File
import java.io.FileInputStream

internal const val WEBGPU_LUT_PRESET_NONE = "none"
internal const val WEBGPU_LUT_PRESET_CUSTOM = "custom"

private const val PRESET_LUT_SIZE = 32

private fun buildLut(transform: (r: Float, g: Float, b: Float) -> Triple<Float, Float, Float>): Lut3d {
    val size = PRESET_LUT_SIZE
    val data = FloatArray(size * size * size * 3)
    var i = 0
    for (b in 0 until size) {
        for (g in 0 until size) {
            for (r in 0 until size) {
                val (or, og, ob) = transform(
                    r.toFloat() / (size - 1),
                    g.toFloat() / (size - 1),
                    b.toFloat() / (size - 1),
                )
                data[i++] = or.coerceIn(0f, 1f)
                data[i++] = og.coerceIn(0f, 1f)
                data[i++] = ob.coerceIn(0f, 1f)
            }
        }
    }
    return Lut3d(size, data)
}

internal fun webgpuBuiltInLut(preset: String): Lut3d? = when (preset) {
    "grayscale" -> buildLut { r, g, b ->
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        Triple(luma, luma, luma)
    }
    "sepia" -> buildLut { r, g, b ->
        Triple(
            0.393f * r + 0.769f * g + 0.189f * b,
            0.349f * r + 0.686f * g + 0.168f * b,
            0.272f * r + 0.534f * g + 0.131f * b,
        )
    }
    "warm" -> buildLut { r, g, b -> Triple(r * 1.08f, g * 1.0f, b * 0.92f) }
    "cool" -> buildLut { r, g, b -> Triple(r * 0.92f, g * 1.0f, b * 1.08f) }
    else -> null
}

internal fun webgpuParseCustomLut(path: String): Lut3d? {
    if (path.isBlank()) return null
    val file = File(path)
    if (!file.isFile || !file.canRead()) return null
    if (file.length() <= 0L || file.length() > 128L * 1024 * 1024) return null
    return try {
        FileInputStream(file).use { stream ->
            if (path.endsWith(".3dlut", ignoreCase = true)) {
                Lut3d.parseMadVr(stream)
            } else {
                Lut3d.parseCube(stream)
            }
        }
    } catch (_: Exception) {
        null
    }
}
// KMK <--
