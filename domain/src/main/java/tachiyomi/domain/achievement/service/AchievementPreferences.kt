package tachiyomi.domain.achievement.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class AchievementPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun unlockedAchievements() = preferenceStore.getString("pref_unlocked_achievements", "")
    fun organicChaptersRead() = preferenceStore.getLong("pref_organic_chapters_read", 0)
    fun achievementsData() = preferenceStore.getString("pref_achievements_data", "")

    fun incrementOrganicRead() {
        organicChaptersRead().set(organicChaptersRead().get() + 1)
    }

    fun unlock(id: String) {
        val current = unlockedAchievements().get()
        val set = if (current.isBlank()) mutableSetOf() else current.split(",").toMutableSet()
        if (set.add(id)) unlockedAchievements().set(set.joinToString(","))
    }

    fun isUnlocked(id: String): Boolean {
        val current = unlockedAchievements().get()
        if (current.isBlank()) return false
        return current.split(",").contains(id)
    }

    fun wipe() {
        unlockedAchievements().set("")
        organicChaptersRead().set(0)
        achievementsData().set("")
    }
}
