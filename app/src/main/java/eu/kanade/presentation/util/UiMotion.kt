package eu.kanade.presentation.util

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

// KMK -->
/**
 * Shared motion system for UI transitions.
 *
 * Centralizes durations and easings so screen, tab, sheet, and bar animations
 * stay consistent. Based on Material 3 motion guidance: emphasized easing
 * `cubic-bezier(0.2, 0, 0, 1)` for screen-level transitions, with longer
 * enter than exit so incoming content leads the transition.
 */
object UiMotion {
    /** Material 3 emphasized easing. */
    val EMPHASIZED: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Material 3 emphasized decelerate (enter). */
    val EMPHASIZED_DECELERATE: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Material 3 emphasized accelerate (exit). */
    val EMPHASIZED_ACCELERATE: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Voyager push enter duration. */
    const val ScreenEnter = 300

    /** Voyager push/pop exit duration. */
    const val ScreenExit = 250

    /** Bottom nav / rail tab switch duration (fade-through). */
    const val TabDuration = 220

    /** Sheet navigator fade-in duration. */
    const val SheetFadeIn = 220

    /** Sheet navigator fade-out duration. */
    const val SheetFadeOut = 90

    /** Bottom bar show/hide duration. */
    const val NavBarDuration = 220
}
// KMK <--
