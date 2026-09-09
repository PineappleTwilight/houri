package tachiyomi.domain.achievement.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class AchievementPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun unlockedAchievements() = preferenceStore.getString("pref_unlocked_achievements", "")
    fun organicChaptersRead() = preferenceStore.getLong("pref_organic_chapters_read", 0)
    fun mangaFinishedCount() = preferenceStore.getLong("pref_achievement_manga_finished", 0)
    fun libraryMangaCount() = preferenceStore.getLong("pref_achievement_library_count", 0)
    fun achievementsData() = preferenceStore.getString("pref_achievements_data", "")
    fun unlockedTimestamps() = preferenceStore.getString("pref_achievement_timestamps", "")

    fun incrementOrganicRead() {
        organicChaptersRead().set(organicChaptersRead().get() + 1)
    }

    fun incrementMangaFinished() {
        mangaFinishedCount().set(mangaFinishedCount().get() + 1)
    }

    fun setLibraryCount(count: Long) {
        libraryMangaCount().set(count)
    }

    fun unlock(id: String): Boolean {
        if (isUnlocked(id)) return false
        val current = unlockedAchievements().get()
        val set = if (current.isBlank()) mutableSetOf<String>() else current.split(",").filter { it.isNotBlank() }.toMutableSet()
        if (!set.add(id)) return false
        unlockedAchievements().set(set.joinToString(","))
        val ts = unlockedTimestamps().get()
        val entry = "$id:${System.currentTimeMillis()}"
        unlockedTimestamps().set(if (ts.isBlank()) entry else "$ts,$entry")
        return true
    }

    fun isUnlocked(id: String): Boolean {
        val current = unlockedAchievements().get()
        if (current.isBlank()) return false
        return current.split(",").contains(id)
    }

    fun getUnlockedIds(): Set<String> {
        val current = unlockedAchievements().get()
        if (current.isBlank()) return emptySet()
        return current.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun getUnlockedWithTimestamps(): Map<String, Long> {
        val raw = unlockedTimestamps().get()
        if (raw.isBlank()) return emptyMap()
        return raw.split(",").mapNotNull {
            val parts = it.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val ts = parts[1].toLongOrNull() ?: return@mapNotNull null
            parts[0] to ts
        }.toMap()
    }

    fun computeStats(totalAchievements: Int): tachiyomi.domain.achievement.model.AchievementStats {
        val unlocked = getUnlockedIds().size
        return tachiyomi.domain.achievement.model.AchievementStats(
            organicChaptersRead = organicChaptersRead().get(),
            mangaFinished = mangaFinishedCount().get(),
            libraryCount = libraryMangaCount().get(),
            totalAchievements = totalAchievements,
            unlockedCount = unlocked,
        )
    }

    fun wipe() {
        unlockedAchievements().set("")
        organicChaptersRead().set(0)
        mangaFinishedCount().set(0)
        libraryMangaCount().set(0)
        achievementsData().set("")
        unlockedTimestamps().set("")
    }
}
