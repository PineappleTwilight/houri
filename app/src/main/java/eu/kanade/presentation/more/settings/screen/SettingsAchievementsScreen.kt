package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.tierAnimatedBackground
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import tachiyomi.domain.achievement.model.Achievement
import tachiyomi.domain.achievement.model.AchievementProgress
import tachiyomi.domain.achievement.model.AchievementTier
import tachiyomi.domain.achievement.model.Achievements
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsAchievementsScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.label_achievements

    @Composable
    override fun getPreferences(): List<Preference> {
        return listOf(
            getHeader(),
            getGeneralGroup(),
            getAchievementsGridGroup(),
        )
    }

    @Composable
    private fun getHeader(): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = "",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = "",
                    content = {
                        val prefs = remember { globalAppGraph.achievementPreferences }
                        val unlockedIds by prefs.unlockedAchievements().collectAsState()
                        val unlockedSet = rememberUnlockedSet(unlockedIds)
                        val countableTotal = Achievements.countable.size
                        val countableUnlocked = unlockedSet.count { Achievements.forId(it)?.countsTowardsProgress == true }
                        val stats = prefs.computeStats(countableTotal)
                        AchievementsHeaderCompact(
                            stats = stats,
                            unlocked = countableUnlocked,
                            total = countableTotal,
                        )
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getGeneralGroup(): Preference.PreferenceGroup {
        val prefs = remember { globalAppGraph.achievementPreferences }
        val enabled by prefs.achievementsEnabled().collectAsState()
        val toastsEnabled by prefs.achievementToastsEnabled().collectAsState()
        val soundsEnabled by prefs.achievementSoundsEnabled().collectAsState()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var showWipeFirst by remember { mutableStateOf(false) }
        var showWipeSecond by remember { mutableStateOf(false) }

        if (showWipeFirst) {
            AlertDialog(
                onDismissRequest = { showWipeFirst = false },
                title = { Text("Reset achievements?") },
                text = { Text("This will wipe all achievement progress and stats. Are you sure?") },
                confirmButton = {
                    TextButton(onClick = {
                        showWipeFirst = false
                        showWipeSecond = true
                    }) { Text("Yes, continue") }
                },
                dismissButton = { TextButton(onClick = { showWipeFirst = false }) { Text("Cancel") } },
            )
        }
        if (showWipeSecond) {
            AlertDialog(
                onDismissRequest = { showWipeSecond = false },
                title = { Text("Are you REALLY sure?") },
                text = { Text("This cannot be undone. All ${Achievements.all.size} achievements will be locked again.") },
                confirmButton = {
                    TextButton(onClick = {
                        showWipeSecond = false
                        scope.launch {
                            globalAppGraph.achievementManager.wipeWithConfirmation(true, true)
                            context.toast("Achievements wiped")
                        }
                    }) { Text("Wipe everything") }
                },
                dismissButton = { TextButton(onClick = { showWipeSecond = false }) { Text("Cancel") } },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(tachiyomi.i18n.MR.strings.pref_category_general),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.achievementsEnabled(),
                    title = "Enable achievements",
                    subtitle = if (enabled) "Achievements are enabled" else "Achievements disabled — no tracking or popups",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.achievementToastsEnabled(),
                    title = "Achievement popups",
                    subtitle = if (toastsEnabled) "Show toast when you unlock an achievement" else "Toasts disabled",
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.achievementSoundsEnabled(),
                    title = "Achievement sounds",
                    subtitle = if (soundsEnabled) "Play a chime per tier (bronze→mythic)" else "Sounds muted",
                    enabled = enabled,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Wipe achievement data",
                    subtitle = "Double confirmation required",
                    onClick = { showWipeFirst = true },
                    enabled = enabled,
                ),
            ),
        )
    }

    @Composable
    private fun getAchievementsGridGroup(): Preference.PreferenceGroup {
        val prefs = remember { globalAppGraph.achievementPreferences }
        val unlockedIds by prefs.unlockedAchievements().collectAsState()
        val unlockedSet = rememberUnlockedSet(unlockedIds)
        val animationsEnabled by prefs.animationsEnabled().collectAsState()
        val all = Achievements.all

        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.label_achievements),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = "",
                    content = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val rows = all.chunked(2)
                            rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    row.forEach { ach ->
                                        val isUnlocked = ach.id in unlockedSet
                                        val display = ach.copy(unlockedAt = if (isUnlocked) 1L else null)
                                        val progress = rememberTierProgress(animationsEnabled && isUnlocked, ach.tier)
                                        androidx.compose.foundation.layout.Box(
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            AchievementCardSimple(
                                                achievement = display,
                                                isUnlocked = isUnlocked,
                                                animationsEnabled = animationsEnabled,
                                                progress = progress,
                                            )
                                        }
                                    }
                                    if (row.size == 1) {
                                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    },
                ),
            ),
        )
    }

    @Composable
    private fun rememberUnlockedSet(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    @Composable
    private fun AchievementsHeaderCompact(
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(KMR.strings.label_achievements), style = MaterialTheme.typography.titleLarge)
                    Text(text = "$unlocked/$total", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(text = stringResource(KMR.strings.achievements_rank_prefix, stats.rank), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                    StatChipCompact(label = "Read", value = "${stats.organicChaptersRead}")
                    StatChipCompact(label = "Finished", value = "${stats.mangaFinished}")
                    StatChipCompact(label = "Library", value = "${stats.libraryCount}")
                    StatChipCompact(label = "Hours", value = "${stats.readingTimeMinutes / 60}")
                    if (stats.negativeUnlocked > 0) StatChipCompact(label = "Neg", value = "${stats.negativeUnlocked}")
                }
                if (stats.backlogCount > 0) {
                    Text(text = "Backlog: ${stats.backlogCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun StatChipCompact(label: String, value: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, style = MaterialTheme.typography.titleMedium)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun rememberTierProgress(enabled: Boolean, tier: AchievementTier): Float {
        return eu.kanade.presentation.library.components.rememberTierProgress(enabled, tier)
    }

    @Composable
    private fun AchievementCardSimple(achievement: Achievement, isUnlocked: Boolean, animationsEnabled: Boolean, progress: Float) {
        val prefs = globalAppGraph.achievementPreferences
        val hasProgress = !isUnlocked && AchievementProgress.hasProgress(achievement.id)
        val progressValue = if (hasProgress) AchievementProgress.progressFor(achievement.id, prefs) else 0f
        val progressLabel = if (hasProgress) AchievementProgress.labelFor(achievement.id, prefs) else null
        val alpha = if (isUnlocked) 1f else 0.45f
        val tierColor = when (achievement.tier) {
            AchievementTier.BRONZE -> MaterialTheme.colorScheme.secondary
            AchievementTier.SILVER -> MaterialTheme.colorScheme.tertiary
            AchievementTier.GOLD -> MaterialTheme.colorScheme.primary
            AchievementTier.PLATINUM -> MaterialTheme.colorScheme.primary
            AchievementTier.LEGENDARY -> MaterialTheme.colorScheme.error
            AchievementTier.MYTHIC -> MaterialTheme.colorScheme.error
            AchievementTier.ULTIMATE -> MaterialTheme.colorScheme.error
        }
        val animatedModifier = if (isUnlocked && animationsEnabled && !achievement.isNegative) {
            when (achievement.tier) {
                AchievementTier.GOLD, AchievementTier.PLATINUM, AchievementTier.LEGENDARY, AchievementTier.MYTHIC, AchievementTier.ULTIMATE ->
                    Modifier.tierAnimatedBackground(achievement.tier, isUnlocked, achievement.isNegative, achievement.isSecret, animationsEnabled, progress)
                else -> Modifier
            }
        } else {
            Modifier
        }
        val cardModifier = Modifier.alpha(alpha).then(animatedModifier)
        androidx.compose.material3.Card(
            modifier = cardModifier,
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = if (animatedModifier != Modifier) MaterialTheme.colorScheme.surface.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isUnlocked) 2.dp else 0.dp),
        ) {
            androidx.compose.foundation.layout.Box {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = achievement.displayIcon, style = MaterialTheme.typography.titleLarge)
                    Text(text = achievement.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(text = achievement.displayDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
                    Text(text = achievement.tier.name, style = MaterialTheme.typography.labelSmall, color = tierColor)
                    if (hasProgress && progressLabel != null) {
                        LinearProgressIndicator(progress = { progressValue }, modifier = Modifier.fillMaxWidth().height(6.dp))
                        Text(text = progressLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (achievement.isSecret) {
                        Text(text = if (isUnlocked) "Secret • Unlocked" else "Secret", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (achievement.isNegative) {
                        Text(text = "Negative • No rank points", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (achievement.isRotating) {
                        Text(text = "Rotating", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}
