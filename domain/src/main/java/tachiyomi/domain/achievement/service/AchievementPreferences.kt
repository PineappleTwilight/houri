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
    fun totalReadingTimeMinutes() = preferenceStore.getLong("pref_achievement_reading_time_minutes", 0)
    fun backlogClearedCount() = preferenceStore.getLong("pref_achievement_backlog_cleared", 0)
    fun ltrMangaFinishedCount() = preferenceStore.getLong("pref_achievement_ltr_finished", 0)
    fun animationsEnabled() = preferenceStore.getBoolean("pref_achievement_animations_enabled", true)
    fun rotatingLastDailyEpoch() = preferenceStore.getLong("pref_achievement_rotating_daily_epoch", 0)
    fun rotatingLastWeeklyEpoch() = preferenceStore.getLong("pref_achievement_rotating_weekly_epoch", 0)
    fun rotatingDailyIds() = preferenceStore.getString("pref_achievement_rotating_daily_ids", "")
    fun rotatingWeeklyIds() = preferenceStore.getString("pref_achievement_rotating_weekly_ids", "")
    fun rotatingProgress() = preferenceStore.getString("pref_achievement_rotating_progress", "")

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
    fun addReadingTimeMinutes(minutes: Long) {
        if (minutes <= 0) return
        val cur = totalReadingTimeMinutes().get()
        totalReadingTimeMinutes().set((cur + minutes).coerceIn(0L, 10_000_000L))
    }

    @Synchronized
    fun incrementBacklogCleared() {
        val cur = backlogClearedCount().get()
        if (cur < 1_000_000L) backlogClearedCount().set(cur + 1)
    }

    @Synchronized
    fun incrementLtrFinished() {
        val cur = ltrMangaFinishedCount().get()
        if (cur < 1_000_000L) ltrMangaFinishedCount().set(cur + 1)
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
        val countableIds = ids.filter { tachiyomi.domain.achievement.model.Achievements.forId(it)?.countsTowardsProgress == true }
        val unlocked = countableIds.size
        val secretUnlocked = ids.count { tachiyomi.domain.achievement.model.Achievements.forId(it)?.isSecret == true }
        val negatives = ids.count { tachiyomi.domain.achievement.model.Achievements.forId(it)?.isNegative == true }
        val backlog = (libraryMangaCount().get() - mangaFinishedCount().get()).coerceAtLeast(0L)
        return tachiyomi.domain.achievement.model.AchievementStats(
            organicChaptersRead = organicChaptersRead().get().coerceAtLeast(0L),
            mangaFinished = mangaFinishedCount().get().coerceAtLeast(0L),
            libraryCount = libraryMangaCount().get().coerceAtLeast(0L),
            totalAchievements = totalAchievements.coerceAtLeast(0),
            unlockedCount = unlocked,
            secretUnlocked = secretUnlocked,
            readingTimeMinutes = totalReadingTimeMinutes().get().coerceAtLeast(0L),
            backlogCount = backlog,
            negativeUnlocked = negatives,
        )
    }

    @Synchronized
    fun wipe() {
        unlockedAchievements().set("")
        organicChaptersRead().set(0)
        mangaFinishedCount().set(0)
        libraryMangaCount().set(0)
        totalReadingTimeMinutes().set(0)
        backlogClearedCount().set(0)
        ltrMangaFinishedCount().set(0)
        rotatingDailyIds().set("")
        rotatingWeeklyIds().set("")
        rotatingProgress().set("")
        rotatingLastDailyEpoch().set(0)
        rotatingLastWeeklyEpoch().set(0)
        achievementsData().set("")
        unlockedTimestamps().set("")
    }

    fun isEnabled(): Boolean = achievementsEnabled().get()

    fun hasCompletedOnboarding(): Boolean = preferenceStore.getBoolean("pref_achievements_onboarded", false).get()

    fun setOnboarded() {
        preferenceStore.getBoolean("pref_achievements_onboarded", false).set(true)
    }
}
