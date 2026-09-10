package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mihon.app.di.globalAppGraph
import tachiyomi.domain.achievement.model.Achievement
import tachiyomi.domain.achievement.model.AchievementProgress
import tachiyomi.domain.achievement.model.Achievements
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun AchievementsContent(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onLeaveAchievements: (() -> Unit)? = null,
) {
    val prefs = globalAppGraph.achievementPreferences
    val unlockedIds by prefs.unlockedAchievements().collectAsState()
    val unlockedSet = rememberUnlockedSet(unlockedIds)
    val countableTotal = Achievements.countable.size
    val countableUnlocked = unlockedSet.count { Achievements.forId(it)?.countsTowardsProgress == true }
    val stats = prefs.computeStats(countableTotal)
    val animationsEnabled by prefs.animationsEnabled().collectAsState()
    val rotatingPoolActive = remember {
        try {
            globalAppGraph.rotatingAchievementPool.getAllActive()
        } catch (_: Exception) {
            emptyList()
        }
    }
    val all = Achievements.all

    Column(modifier = modifier.padding(contentPadding)) {
        if (onLeaveAchievements != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.IconButton(onClick = onLeaveAchievements) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(tachiyomi.i18n.MR.strings.action_bar_up_description),
                    )
                }
                Text(
                    text = stringResource(tachiyomi.i18n.MR.strings.label_library),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onLeaveAchievements) {
                    Text(text = stringResource(tachiyomi.i18n.MR.strings.label_library))
                }
            }
        }
        AchievementsHeader(stats = stats, unlocked = countableUnlocked, total = countableTotal, animationsEnabled = animationsEnabled, onToggleAnimations = { prefs.animationsEnabled().set(it) })
        if (rotatingPoolActive.isNotEmpty()) {
            Text(
                text = "Rotating Pool (Daily/Weekly)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(all, key = { it.id }) { ach ->
                val isUnlocked = ach.id in unlockedSet
                val display = ach.copy(unlockedAt = if (isUnlocked) 1L else null)
                val progress = rememberTierProgress(animationsEnabled && isUnlocked, ach.tier)
                AchievementCard(achievement = display, isUnlocked = isUnlocked, animationsEnabled = animationsEnabled, progress = progress)
            }
        }
    }
}

@Composable
private fun rememberUnlockedSet(raw: String): Set<String> {
    if (raw.isBlank()) return emptySet()
    return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
}

@Composable
private fun AchievementsHeader(
    stats: tachiyomi.domain.achievement.model.AchievementStats,
    unlocked: Int,
    total: Int,
    animationsEnabled: Boolean,
    onToggleAnimations: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(KMR.strings.label_achievements),
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "Animations", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = animationsEnabled, onCheckedChange = onToggleAnimations)
                }
            }
            Text(
                text = stringResource(KMR.strings.achievements_unlocked_count, unlocked, total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(KMR.strings.achievements_rank_prefix, stats.rank),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                StatChip(label = "Read", value = "${stats.organicChaptersRead}")
                StatChip(label = "Finished", value = "${stats.mangaFinished}")
                StatChip(label = "Library", value = "${stats.libraryCount}")
                StatChip(label = "Hours", value = "${stats.readingTimeMinutes / 60}")
                if (stats.negativeUnlocked > 0) StatChip(label = "Neg", value = "${stats.negativeUnlocked}")
            }
            if (stats.backlogCount > 0) {
                Text(text = "Backlog: ${stats.backlogCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AchievementCard(achievement: Achievement, isUnlocked: Boolean, animationsEnabled: Boolean, progress: Float) {
    val prefs = globalAppGraph.achievementPreferences
    val hasProgress = !isUnlocked && AchievementProgress.hasProgress(achievement.id)
    val progressValue = if (hasProgress) AchievementProgress.progressFor(achievement.id, prefs) else 0f
    val progressLabel = if (hasProgress) AchievementProgress.labelFor(achievement.id, prefs) else null
    val rotatingProgressPair = if (!isUnlocked && achievement.isRotating) {
        try {
            val pool = globalAppGraph.rotatingAchievementPool
            val cur = pool.getProgress(achievement.id)
            val label = "$cur"
            cur to label
        } catch (_: Exception) { null }
    } else null
    val alpha = if (isUnlocked) 1f else 0.45f
    val tierColor = when (achievement.tier) {
        tachiyomi.domain.achievement.model.AchievementTier.BRONZE -> MaterialTheme.colorScheme.secondary
        tachiyomi.domain.achievement.model.AchievementTier.SILVER -> MaterialTheme.colorScheme.tertiary
        tachiyomi.domain.achievement.model.AchievementTier.GOLD -> MaterialTheme.colorScheme.primary
        tachiyomi.domain.achievement.model.AchievementTier.PLATINUM -> MaterialTheme.colorScheme.primary
        tachiyomi.domain.achievement.model.AchievementTier.LEGENDARY -> MaterialTheme.colorScheme.error
        tachiyomi.domain.achievement.model.AchievementTier.MYTHIC -> MaterialTheme.colorScheme.error
        tachiyomi.domain.achievement.model.AchievementTier.ULTIMATE -> MaterialTheme.colorScheme.error
    }
    val animatedModifier = if (isUnlocked && animationsEnabled && !achievement.isNegative) {
        when (achievement.tier) {
            tachiyomi.domain.achievement.model.AchievementTier.GOLD,
            tachiyomi.domain.achievement.model.AchievementTier.PLATINUM,
            tachiyomi.domain.achievement.model.AchievementTier.LEGENDARY,
            tachiyomi.domain.achievement.model.AchievementTier.MYTHIC,
            tachiyomi.domain.achievement.model.AchievementTier.ULTIMATE,
            -> Modifier.tierAnimatedBackground(achievement.tier, isUnlocked, achievement.isNegative, achievement.isSecret, animationsEnabled, progress)
            else -> Modifier
        }
    } else {
        Modifier
    }
    val cardModifier = Modifier.alpha(alpha).then(animatedModifier)
    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (animatedModifier != Modifier) MaterialTheme.colorScheme.surface.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isUnlocked) 2.dp else 0.dp),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = achievement.displayIcon, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = achievement.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                Text(
                    text = achievement.displayDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = achievement.tier.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = tierColor,
                )
                if (hasProgress && progressLabel != null) {
                    LinearProgressIndicator(
                        progress = { progressValue },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (rotatingProgressPair != null) {
                    Text(
                        text = "Progress ${rotatingProgressPair.second}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (achievement.isSecret) {
                    Text(
                        text = if (isUnlocked) "Secret • Unlocked" else "Secret",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (achievement.isNegative) {
                    Text(
                        text = "Negative • No rank points",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (achievement.isRotating) {
                    Text(
                        text = "Rotating",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}
