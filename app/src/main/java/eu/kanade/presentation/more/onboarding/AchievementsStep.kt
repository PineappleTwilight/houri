package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

internal class AchievementsStep : OnboardingStep {

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        val prefs = globalAppGraph.achievementPreferences
        val enabled by prefs.achievementsEnabled().collectAsState()
        val toasts by prefs.achievementToastsEnabled().collectAsState()
        val sounds by prefs.achievementSoundsEnabled().collectAsState()

        Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
            Text(
                text = stringResource(KMR.strings.onboarding_achievements_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(KMR.strings.onboarding_achievements_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Enable achievements")
                    Text(
                        text = if (enabled) stringResource(KMR.strings.onboarding_achievements_enabled_desc) else stringResource(KMR.strings.onboarding_achievements_disabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { prefs.achievementsEnabled().set(it) })
            }

            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Achievement popups")
                    Text(
                        text = stringResource(KMR.strings.onboarding_achievements_toasts_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = toasts,
                    enabled = enabled,
                    onCheckedChange = { prefs.achievementToastsEnabled().set(it) },
                )
            }

            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Achievement sounds")
                    Text(
                        text = stringResource(KMR.strings.onboarding_achievements_sounds_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = sounds,
                    enabled = enabled,
                    onCheckedChange = { prefs.achievementSoundsEnabled().set(it) },
                )
            }
        }
    }
}
