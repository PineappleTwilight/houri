package eu.kanade.tachiyomi.util.system

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import coil3.size.ScaleDrawable

fun Drawable.getBitmapOrNull(): Bitmap? = when (this) {
    is BitmapDrawable -> bitmap
    is ScaleDrawable -> child.toBitmap()
    // KMK -->
    else -> runCatching {
        // Animated/exotic covers: render current (first) frame instead of failing save/palette
        if (intrinsicWidth > 0 && intrinsicHeight > 0) toBitmap() else null
    }.getOrNull()
    // KMK <--
}
