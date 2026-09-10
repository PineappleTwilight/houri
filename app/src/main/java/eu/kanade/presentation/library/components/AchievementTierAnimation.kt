package eu.kanade.presentation.library.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import tachiyomi.domain.achievement.model.Achievement
import tachiyomi.domain.achievement.model.AchievementTier

object AchievementTierAnimation {
    fun brushForTier(
        tier: AchievementTier,
        isNegative: Boolean,
        isSecret: Boolean,
        progress: Float,
    ): Brush? {
        if (isNegative) return null
        if (isSecret) return null
        return when (tier) {
            AchievementTier.BRONZE -> null
            AchievementTier.SILVER -> silverBrush(progress)
            AchievementTier.GOLD -> goldBrush(progress)
            AchievementTier.PLATINUM -> platinumBrush(progress)
            AchievementTier.LEGENDARY -> legendaryBrush(progress)
            AchievementTier.MYTHIC -> mythicBrush(progress)
            AchievementTier.ULTIMATE -> ultimateBrush(progress)
        }
    }

    fun iconEffect(tier: AchievementTier, isNegative: Boolean): IconEffect {
        if (isNegative) return IconEffect.None
        return when (tier) {
            AchievementTier.LEGENDARY -> IconEffect.Glow(Color(0xFFFF6B00))
            AchievementTier.MYTHIC -> IconEffect.Pulse(Color(0xFFAA00FF))
            AchievementTier.ULTIMATE -> IconEffect.Flame
            else -> IconEffect.None
        }
    }

    private fun silverBrush(p: Float): Brush = Brush.linearGradient(
        colors = listOf(Color(0xFFE8E8E8), Color(0xFFB0BEC5), Color(0xFFE8E8E8)),
        start = Offset(p * 300f, 0f),
        end = Offset(p * 300f + 200f, 200f),
        tileMode = TileMode.Mirror,
    )

    private fun goldBrush(p: Float): Brush = Brush.linearGradient(
        colors = listOf(Color(0xFFFFD54F), Color(0xFFFFA000), Color(0xFFFFECB3), Color(0xFFFFA000)),
        start = Offset(p * 400f, 0f),
        end = Offset(p * 400f + 300f, 300f),
        tileMode = TileMode.Mirror,
    )

    private fun platinumBrush(p: Float): Brush = Brush.linearGradient(
        colors = listOf(Color(0xFF90CAF9), Color(0xFFE1F5FE), Color(0xFF42A5F5), Color(0xFF90CAF9)),
        start = Offset(p * 500f, 0f),
        end = Offset(p * 500f + 400f, 400f),
        tileMode = TileMode.Mirror,
    )

    private fun legendaryBrush(p: Float): Brush = Brush.linearGradient(
        colors = listOf(Color(0xFFFF3D00), Color(0xFFFFEA00), Color(0xFFFF3D00), Color(0xFFAA00FF)),
        start = Offset(p * 600f, 0f),
        end = Offset(p * 600f + 500f, 500f),
        tileMode = TileMode.Mirror,
    )

    private fun mythicBrush(p: Float): Brush = Brush.sweepGradient(
        colors = listOf(Color(0xFF00E5FF), Color(0xFFAA00FF), Color(0xFFFF1744), Color(0xFF00E5FF)),
        center = Offset(0.5f + p * 0.1f, 0.5f),
    )

    private fun ultimateBrush(p: Float): Brush = Brush.linearGradient(
        colors = listOf(Color(0xFF000000), Color(0xFF1A237E), Color(0xFF4A148C), Color(0xFF00E5FF), Color(0xFF000000)),
        start = Offset(p * 800f, 0f),
        end = Offset(p * 800f + 600f, 600f),
        tileMode = TileMode.Mirror,
    )

    enum class IconEffect { None, Glow, Pulse, Flame }
}

@Composable
fun rememberTierProgress(enabled: Boolean, tier: AchievementTier): Float {
    if (!enabled) return 0f
    if (tier == AchievementTier.BRONZE) return 0f
    val transition = rememberInfiniteTransition(label = "tierProgress")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = tierDuration(tier), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress",
    )
    return progress
}

private fun tierDuration(tier: AchievementTier): Int = when (tier) {
    AchievementTier.SILVER -> 3200
    AchievementTier.GOLD -> 2800
    AchievementTier.PLATINUM -> 2400
    AchievementTier.LEGENDARY -> 1800
    AchievementTier.MYTHIC -> 1600
    AchievementTier.ULTIMATE -> 1200
    AchievementTier.BRONZE -> 0
}

fun Modifier.tierAnimatedBackground(
    tier: AchievementTier,
    isUnlocked: Boolean,
    isNegative: Boolean,
    isSecret: Boolean,
    animationsEnabled: Boolean,
    progress: Float,
): Modifier = composed {
    if (!isUnlocked || !animationsEnabled) return@composed this
    val brush = remember(tier, isNegative, isSecret, progress) {
        AchievementTierAnimation.brushForTier(tier, isNegative, isSecret, progress)
    }
    if (brush == null) this else this.background(brush)
}
