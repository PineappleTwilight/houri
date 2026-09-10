package eu.kanade.tachiyomi.data.achievement

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.domain.achievement.model.Achievements
import tachiyomi.domain.achievement.service.AchievementPreferences
import tachiyomi.domain.achievement.service.AchievementUnlockNotifier

@SingleIn(AppScope::class)
@Inject
class AchievementNotifier(
    private val context: Context,
    private val prefs: AchievementPreferences,
    private val soundPlayer: AchievementSoundPlayer,
) : AchievementUnlockNotifier {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var lastSeen = prefs.getUnlockedIds()
    private var started = false

    fun start() {
        if (started) return
        started = true
        lastSeen = prefs.getUnlockedIds()
        scope.launch {
            prefs.unlockedAchievements().changes()
                .map { raw -> if (raw.isBlank()) emptySet() else raw.split(",").filter { it.isNotBlank() }.toSet() }
                .distinctUntilChanged()
                .collect { current ->
                    val newly = current - lastSeen
                    if (newly.isNotEmpty()) {
                        var delayMs = 0L
                        for (id in newly) {
                            val ach = Achievements.forId(id) ?: continue
                            handler.postDelayed({
                                showToast(ach)
                                soundPlayer.play(ach.tier)
                            }, delayMs)
                            delayMs += 900
                        }
                    }
                    lastSeen = current
                }
        }
    }

    override fun onUnlocked(ids: List<String>) = notifyNow(ids)

    fun notifyNow(ids: List<String>) {
        if (!prefs.achievementsEnabled().get()) return
        if (ids.isEmpty()) return
        val valid = ids.mapNotNull { Achievements.forId(it) }
        if (valid.isEmpty()) return
        if (valid.size > 3) {
            val summary = "Unlocked ${valid.size} achievements: " + valid.take(3).joinToString(", ") { it.displayTitle } + if (valid.size > 3) " +${valid.size - 3} more" else ""
            handler.post {
                if (prefs.achievementToastsEnabled().get()) {
                    try { context.toast(summary, duration = Toast.LENGTH_LONG) } catch (_: Exception) {}
                }
                valid.forEach { soundPlayer.play(it.tier) }
            }
            var delayMs = 900L
            for (ach in valid.takeLast(2)) {
                handler.postDelayed({
                    if (prefs.achievementToastsEnabled().get()) showToast(ach)
                }, delayMs)
                delayMs += 900
            }
            return
        }
        var delayMs = 0L
        for (ach in valid) {
            handler.postDelayed({
                if (prefs.achievementToastsEnabled().get()) showToast(ach)
                soundPlayer.play(ach.tier)
            }, delayMs)
            delayMs += 900
        }
    }

    private fun showToast(ach: tachiyomi.domain.achievement.model.Achievement) {
        if (!prefs.achievementsEnabled().get()) return
        if (!prefs.achievementToastsEnabled().get()) return
        val tierLabel = when (ach.tier) {
            tachiyomi.domain.achievement.model.AchievementTier.MYTHIC -> "MYTHIC"
            tachiyomi.domain.achievement.model.AchievementTier.LEGENDARY -> "LEGENDARY"
            tachiyomi.domain.achievement.model.AchievementTier.PLATINUM -> "PLATINUM"
            tachiyomi.domain.achievement.model.AchievementTier.GOLD -> "GOLD"
            tachiyomi.domain.achievement.model.AchievementTier.SILVER -> "SILVER"
            tachiyomi.domain.achievement.model.AchievementTier.ULTIMATE -> "ULTIMATE"
            else -> "BRONZE"
        }
        val secretPrefix = if (ach.isSecret) "Secret Unlocked! " else ""
        val desc = if (ach.displayDescription.length > 80) ach.displayDescription.take(77) + "..." else ach.displayDescription
        val msg = "${ach.displayIcon}  ${secretPrefix}${ach.displayTitle} [$tierLabel] — $desc"
        try {
            context.toast(msg, duration = Toast.LENGTH_LONG)
        } catch (_: Exception) {}
    }
}
