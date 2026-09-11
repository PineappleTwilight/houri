package tachiyomi.presentation.core.components.manga

import androidx.compose.ui.graphics.Color

/**
 * Pure, serializable config for manga cover rendering. Extracted from
 * `CommonMangaItem` which previously computed `bgColor`/`onBgColor`/`alpha`
 * inline with direct `coverData.dominantCoverColors` reads and
 * `globalAppGraph` censor checks, mixing 4 concerns in one composable.
 *
 * Single-responsibility: just data, no composition, no DI. ViewModels or
 * parent composables map domain `MangaCover` -> this config once, then pass it
 * down. This makes cover rendering testable and moves the 18.dp blur / color
 * logic out of the grid item.
 */
data class MangaCoverConfig(
    val alpha: Float = 1f,
    val bgColor: Color? = null,
    val tint: Color? = null,
    val shouldCensor: Boolean = false,
    val blurRadius: Float = 16f,
)

/**
 * Maps a domain `coverData`'s dominant colors to a [MangaCoverConfig].
 * Pure function, no Compose, no DI — unit-testable.
 */
fun coverConfigFor(
    dominantRgb: Int?,
    titleTextColor: Int?,
    libraryColored: Boolean,
    shouldCensor: Boolean,
    isSelected: Boolean,
    coverAlpha: Float,
    selectedAlpha: Float = 0.76f,
): MangaCoverConfig {
    val bg = dominantRgb?.let { Color(it) }.takeIf { libraryColored && !shouldCensor }
    val tint = titleTextColor?.let { Color(it) }.takeIf { libraryColored && !shouldCensor }
    val alpha = if (isSelected) selectedAlpha else coverAlpha
    return MangaCoverConfig(alpha = alpha, bgColor = bg, tint = tint?.let { Color(it.value) }, shouldCensor = shouldCensor)
}
