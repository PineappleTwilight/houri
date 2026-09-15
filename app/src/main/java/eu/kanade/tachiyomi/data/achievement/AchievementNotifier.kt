package eu.kanade.tachiyomi.data.achievement

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegate
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import tachiyomi.domain.achievement.model.Achievements
import tachiyomi.domain.achievement.service.AchievementPreferences
import tachiyomi.domain.achievement.service.AchievementUnlockNotifier

@SingleIn(AppScope::class)
@Inject
class AchievementNotifier(
    private val context: Context,
    private val prefs: AchievementPreferences,
    private val soundPlayer: AchievementSoundPlayer,
    // KMK -->
    // Non-null: Metro resolves `Type? = null` only from a nullable binding, so the old
    // nullable param silently stayed null and no ACHIEVEMENT_UNLOCKED webhook was sent.
    private val webhookNotifier: eu.kanade.tachiyomi.data.webhook.WebhookNotifier,
    // KMK <--
) : AchievementUnlockNotifier {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var lastSeen = prefs.getUnlockedIds()
    private var started = false
    private val recentlyNotified = mutableMapOf<String, Long>()

    fun start() {
        if (started) return
        started = true
        lastSeen = prefs.getUnlockedIds()
        scope.launch {
            prefs.unlockedAchievements().changes()
                .map { raw -> if (raw.isBlank()) emptySet() else raw.split(",").filter { it.isNotBlank() }.toSet() }
                .distinctUntilChanged()
                .collect { current ->
                    val now = android.os.SystemClock.uptimeMillis()
                    val newly = (current - lastSeen)
                        .filterNot { (now - (recentlyNotified[it] ?: 0L)) < 10_000L }
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
        val now = android.os.SystemClock.uptimeMillis()
        ids.forEach { recentlyNotified[it] = now }
        // KMK -->
        ids.forEach { id ->
            val ach = Achievements.forId(id) ?: return@forEach
            val revealed = if (ach.isSecret) ach.copy(unlockedAt = 1L) else ach
            webhookNotifier.notify(
                event = eu.kanade.tachiyomi.data.webhook.WebhookEvent.ACHIEVEMENT_UNLOCKED,
                data = mapOf(
                    "achievement_id" to id,
                    "achievement_title" to revealed.displayTitle,
                    "achievement_tier" to ach.tier.name,
                ),
            )
        }
        // KMK <--
        val valid = ids.mapNotNull { Achievements.forId(it) }
        if (valid.isEmpty()) return
        if (valid.size > 3) {
            val revealed = valid.map { if (it.isSecret) it.copy(unlockedAt = 1L) else it }
            val summary = "Unlocked ${revealed.size} achievements: " + revealed.take(3).joinToString(", ") { it.displayTitle } + if (revealed.size > 3) " +${revealed.size - 3} more" else ""
            handler.post {
                if (prefs.achievementToastsEnabled().get()) {
                    try {
                        themedToastContext().toast(summary, duration = Toast.LENGTH_LONG)
                    } catch (_: Exception) {}
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
        val resolved = if (ach.isSecret) ach.copy(unlockedAt = 1L) else ach
        val tierLabel = when (resolved.tier) {
            tachiyomi.domain.achievement.model.AchievementTier.MYTHIC -> "MYTHIC"
            tachiyomi.domain.achievement.model.AchievementTier.LEGENDARY -> "LEGENDARY"
            tachiyomi.domain.achievement.model.AchievementTier.PLATINUM -> "PLATINUM"
            tachiyomi.domain.achievement.model.AchievementTier.GOLD -> "GOLD"
            tachiyomi.domain.achievement.model.AchievementTier.SILVER -> "SILVER"
            tachiyomi.domain.achievement.model.AchievementTier.ULTIMATE -> "ULTIMATE"
            else -> "BRONZE"
        }
        val secretPrefix = if (resolved.isSecret) "Secret Unlocked! " else ""
        val desc = if (resolved.displayDescription.length > 80) resolved.displayDescription.take(77) + "..." else resolved.displayDescription
        val msg = "${resolved.displayIcon}  ${secretPrefix}${resolved.displayTitle} [$tierLabel] — $desc"
        try {
            themedToastContext().toast(msg, duration = Toast.LENGTH_LONG)
        } catch (_: Exception) {}
    }

    private fun themedToastContext(): Context {
        return try {
            val uiPrefs: UiPreferences = globalAppGraph.uiPreferences
            val night = when (uiPrefs.themeMode().get()) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> context.applicationContext.isNightMode()
            }
            val expected = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val base = context.applicationContext
            val wrapped = ContextThemeWrapper(base, R.style.Theme_Tachiyomi)
            if (base.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != expected) {
                val overrideConf = Configuration()
                overrideConf.setTo(base.resources.configuration)
                overrideConf.uiMode = (overrideConf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or expected
                wrapped.applyOverrideConfiguration(overrideConf)
            }
            ThemingDelegate.getThemeResIds(uiPrefs.appTheme().get(), uiPrefs.themeDarkAmoled().get())
                .forEach { wrapped.theme.applyStyle(it, true) }
            wrapped
        } catch (_: Exception) {
            context
        }
    }
}
