package tachiyomi.domain.achievement.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.achievement.model.Achievement
import tachiyomi.domain.achievement.model.Achievements
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@SingleIn(AppScope::class)
@Inject
class RotatingAchievementPool(
    private val prefs: AchievementPreferences,
    private val manager: AchievementManager,
) {
    fun getActiveDaily(): List<Achievement> {
        val now = System.currentTimeMillis()
        val epoch = TimeUnit.MILLISECONDS.toDays(now)
        val storedEpoch = prefs.rotatingLastDailyEpoch().get()
        val storedIds = prefs.rotatingDailyIds().get()
        if (storedEpoch == epoch && storedIds.isNotBlank()) {
            return storedIds.split(",").mapNotNull { Achievements.forId(it.trim()) }
        }
        val pool = Achievements.rotating.filter { it.id.startsWith("rotating_daily") }
        val unlocked = prefs.getUnlockedIds()
        val available = pool.filter { it.id !in unlocked }
        val candidates = if (available.size >= 3) available else pool
        val selected = candidates.shuffled(Random(epoch)).take(3)
        prefs.rotatingLastDailyEpoch().set(epoch)
        prefs.rotatingDailyIds().set(selected.joinToString(",") { it.id })
        return selected
    }

    fun getActiveWeekly(): List<Achievement> {
        val now = System.currentTimeMillis()
        val epoch = TimeUnit.MILLISECONDS.toDays(now) / 7
        val storedEpoch = prefs.rotatingLastWeeklyEpoch().get()
        val storedIds = prefs.rotatingWeeklyIds().get()
        if (storedEpoch == epoch && storedIds.isNotBlank()) {
            return storedIds.split(",").mapNotNull { Achievements.forId(it.trim()) }
        }
        val pool = Achievements.rotating.filter { it.id.startsWith("rotating_weekly") }
        val unlocked = prefs.getUnlockedIds()
        val available = pool.filter { it.id !in unlocked }
        val candidates = if (available.size >= 4) available else pool
        val selected = candidates.shuffled(Random(epoch + 1000)).take(4)
        prefs.rotatingLastWeeklyEpoch().set(epoch)
        prefs.rotatingWeeklyIds().set(selected.joinToString(",") { it.id })
        return selected
    }

    fun getAllActive(): List<Achievement> = getActiveDaily() + getActiveWeekly()

    fun markProgress(id: String, progress: Int = 1) {
        val ach = Achievements.forId(id) ?: return
        if (!ach.isRotating) return
        if (prefs.getUnlockedIds().contains(id)) return
        val raw = prefs.rotatingProgress().get()
        val map = if (raw.isBlank()) {
            mutableMapOf<String, Int>()
        } else {
            raw.split(",").mapNotNull {
                val parts = it.split(":", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                parts[0] to (parts[1].toIntOrNull() ?: 0)
            }.toMap().toMutableMap()
        }
        val cur = (map[id] ?: 0) + progress
        map[id] = cur
        prefs.rotatingProgress().set(map.entries.joinToString(",") { "${it.key}:${it.value}" })
        val threshold = rotatingThreshold(id)
        if (cur >= threshold) {
            manager.tryUnlockDirect(id)
            map.remove(id)
            prefs.rotatingProgress().set(map.entries.joinToString(",") { "${it.key}:${it.value}" })
        }
    }

    fun getProgress(id: String): Int {
        val raw = prefs.rotatingProgress().get()
        if (raw.isBlank()) return 0
        return raw.split(",").firstNotNullOfOrNull {
            val parts = it.split(":", limit = 2)
            if (parts[0] == id) parts[1].toIntOrNull() else null
        } ?: 0
    }

    private fun rotatingThreshold(id: String): Int = when {
        id.contains("read_5") -> 5
        id.contains("read_15") -> 15
        id.contains("read_30") -> 30
        id.contains("read_75") -> 75
        id.contains("library_add_3") -> 3
        id.contains("library_10") -> 10
        id.contains("finish_1") -> 1
        id.contains("finish_3") -> 3
        id.contains("translate_2") -> 2
        id.contains("translate_10") -> 10
        id.contains("tracker_update_3") -> 3
        id.contains("search_5") -> 5
        else -> 1
    }

    fun refreshIfNeeded() {
        getActiveDaily()
        getActiveWeekly()
    }
}
