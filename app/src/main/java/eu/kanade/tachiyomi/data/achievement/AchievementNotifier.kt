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
                    if (newly.isNotEmpty() && lastSeen.isNotEmpty()) {
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
        var delayMs = 0L
        for (id in ids) {
            val ach = Achievements.forId(id) ?: continue
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
        val tierLabel = when (ach.tier.name) {
            "MYTHIC" -> "MYTHIC"
            "LEGENDARY" -> "LEGENDARY"
            "PLATINUM" -> "PLATINUM"
            "GOLD" -> "GOLD"
            "SILVER" -> "SILVER"
            else -> "BRONZE"
        }
        val secretPrefix = if (ach.isSecret) "Secret Unlocked! " else ""
        val msg = "${ach.displayIcon}  ${secretPrefix}${ach.displayTitle} [$tierLabel] — ${ach.displayDescription}"
        try {
            context.toast(msg, duration = Toast.LENGTH_LONG)
        } catch (_: Exception) {}
    }
}
