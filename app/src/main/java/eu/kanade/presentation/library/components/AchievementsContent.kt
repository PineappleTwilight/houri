package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mihon.app.di.globalAppGraph
import tachiyomi.domain.achievement.model.Achievement
import tachiyomi.domain.achievement.model.Achievements
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun AchievementsContent(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val prefs = globalAppGraph.achievementPreferences
    val unlockedIds by prefs.unlockedAchievements().collectAsState()
    val unlockedSet = rememberUnlockedSet(unlockedIds)
    val stats = prefs.computeStats(Achievements.all.size)
    val all = Achievements.all

    Column(modifier = modifier.padding(contentPadding)) {
        AchievementsHeader(stats = stats, unlocked = unlockedSet.size, total = all.size)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(all, key = { it.id }) { ach ->
                val unlockedAt = if (ach.id in unlockedSet) System.currentTimeMillis() else null
                val display = ach.copy(unlockedAt = if (ach.id in unlockedSet) 1L else null)
                AchievementCard(achievement = display, isUnlocked = ach.id in unlockedSet)
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
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(KMR.strings.label_achievements),
                style = MaterialTheme.typography.titleLarge,
            )
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
private fun AchievementCard(achievement: Achievement, isUnlocked: Boolean) {
    val alpha = if (isUnlocked) 1f else 0.45f
    val tierColor = when (achievement.tier) {
        tachiyomi.domain.achievement.model.AchievementTier.BRONZE -> MaterialTheme.colorScheme.secondary
        tachiyomi.domain.achievement.model.AchievementTier.SILVER -> MaterialTheme.colorScheme.tertiary
        tachiyomi.domain.achievement.model.AchievementTier.GOLD -> MaterialTheme.colorScheme.primary
        tachiyomi.domain.achievement.model.AchievementTier.PLATINUM -> MaterialTheme.colorScheme.primary
        tachiyomi.domain.achievement.model.AchievementTier.LEGENDARY -> MaterialTheme.colorScheme.error
        tachiyomi.domain.achievement.model.AchievementTier.MYTHIC -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.alpha(alpha),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isUnlocked) 2.dp else 0.dp),
    ) {
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
            if (achievement.isSecret) {
                Text(
                    text = if (isUnlocked) "Secret • Unlocked" else "Secret",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
