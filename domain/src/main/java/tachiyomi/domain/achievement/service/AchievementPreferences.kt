package tachiyomi.domain.achievement.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.achievement.model.Achievements

@SingleIn(AppScope::class)
@Inject
class AchievementPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun achievementsEnabled() = preferenceStore.getBoolean("pref_achievements_enabled", true)
    fun achievementToastsEnabled() = preferenceStore.getBoolean("pref_achievement_toasts_enabled", true)
    fun achievementSoundsEnabled() = preferenceStore.getBoolean("pref_achievement_sounds_enabled", true)

    fun unlockedAchievements() = preferenceStore.getString("pref_unlocked_achievements", "")
    fun organicChaptersRead() = preferenceStore.getLong("pref_organic_chapters_read", 0)
    fun mangaFinishedCount() = preferenceStore.getLong("pref_achievement_manga_finished", 0)
    fun libraryMangaCount() = preferenceStore.getLong("pref_achievement_library_count", 0)
    fun achievementsData() = preferenceStore.getString("pref_achievements_data", "")
    fun unlockedTimestamps() = preferenceStore.getString("pref_achievement_timestamps", "")

    @Synchronized
    fun incrementOrganicRead() {
        val cur = organicChaptersRead().get()
        if (cur < 1_000_000L) organicChaptersRead().set(cur + 1)
    }

    @Synchronized
    fun incrementMangaFinished() {
        val cur = mangaFinishedCount().get()
        if (cur < 1_000_000L) mangaFinishedCount().set(cur + 1)
    }

    @Synchronized
    fun setLibraryCount(count: Long) {
        libraryMangaCount().set(count.coerceIn(0L, 10_000L))
    }

    @Synchronized
    fun unlock(id: String): Boolean {
        if (id.isBlank() || id.length > 64) return false
        if (tachiyomi.domain.achievement.model.Achievements.forId(id) == null) return false
        if (isUnlocked(id)) return false
        val current = unlockedAchievements().get()
        val set = if (current.isBlank()) mutableSetOf<String>() else current.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
        if (set.size >= 300) return false
        if (!set.add(id)) return false
        unlockedAchievements().set(set.joinToString(","))
        val ts = unlockedTimestamps().get()
        val entry = "$id:${System.currentTimeMillis()}"
        val newTs = if (ts.isBlank()) entry else "$ts,$entry"
        if (newTs.length > 16_000) {
            val trimmed = newTs.split(",").takeLast(200).joinToString(",")
            unlockedTimestamps().set(trimmed)
        } else {
            unlockedTimestamps().set(newTs)
        }
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
        return current.split(",").map { it.trim() }.filter { it.isNotBlank() && it.length <= 64 && Achievements.forId(it) != null }.toSet()
    }

    fun getUnlockedWithTimestamps(): Map<String, Long> {
        val raw = unlockedTimestamps().get()
        if (raw.isBlank()) return emptyMap()
        return raw.split(",").mapNotNull {
            val parts = it.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].trim()
            if (id.isBlank() || id.length > 64 || Achievements.forId(id) == null) return@mapNotNull null
            val ts = parts[1].toLongOrNull() ?: return@mapNotNull null
            if (ts <= 0L || ts > System.currentTimeMillis() + 86_400_000L) return@mapNotNull null
            id to ts
        }.toMap()
    }

    fun computeStats(totalAchievements: Int): tachiyomi.domain.achievement.model.AchievementStats {
        val ids = getUnlockedIds()
        val unlocked = ids.size
        val secretUnlocked = ids.count { tachiyomi.domain.achievement.model.Achievements.forId(it)?.isSecret == true }
        return tachiyomi.domain.achievement.model.AchievementStats(
            organicChaptersRead = organicChaptersRead().get().coerceAtLeast(0L),
            mangaFinished = mangaFinishedCount().get().coerceAtLeast(0L),
            libraryCount = libraryMangaCount().get().coerceAtLeast(0L),
            totalAchievements = totalAchievements.coerceAtLeast(0),
            unlockedCount = unlocked,
            secretUnlocked = secretUnlocked,
        )
    }

    @Synchronized
    fun wipe() {
        unlockedAchievements().set("")
        organicChaptersRead().set(0)
        mangaFinishedCount().set(0)
        libraryMangaCount().set(0)
        achievementsData().set("")
        unlockedTimestamps().set("")
    }

    fun isEnabled(): Boolean = achievementsEnabled().get()

    fun hasCompletedOnboarding(): Boolean = preferenceStore.getBoolean("pref_achievements_onboarded", false).get()

    fun setOnboarded() {
        preferenceStore.getBoolean("pref_achievements_onboarded", false).set(true)
    }
}
