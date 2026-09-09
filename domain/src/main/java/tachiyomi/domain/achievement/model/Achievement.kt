package tachiyomi.domain.achievement.model

import kotlinx.serialization.Serializable

@Serializable
data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val tier: AchievementTier,
    val unlockedAt: Long? = null,
) {
    val isUnlocked: Boolean get() = unlockedAt != null
}

enum class AchievementTier { BRONZE, SILVER, GOLD, PLATINUM, LEGENDARY }

@Serializable
data class AchievementStats(
    val organicChaptersRead: Long = 0,
    val totalAchievements: Int = 0,
    val unlockedCount: Int = 0,
) {
    val rank: String get() = when {
        unlockedCount >= 30 && organicChaptersRead >= 1000 -> "Legend"
        unlockedCount >= 20 && organicChaptersRead >= 500 -> "Master"
        unlockedCount >= 10 && organicChaptersRead >= 100 -> "Veteran"
        unlockedCount >= 5 -> "Explorer"
        else -> "Novice"
    }
}

object Achievements {
    val all = listOf(
        Achievement("first_chapter", "First Steps", "Read your first chapter", AchievementTier.BRONZE),
        Achievement("ten_chapters", "Getting Started", "Read 10 chapters", AchievementTier.BRONZE),
        Achievement("hundred_chapters", "Century", "Read 100 chapters", AchievementTier.SILVER),
        Achievement("five_hundred", "Marathon", "Read 500 chapters", AchievementTier.GOLD),
        Achievement("thousand", "Legend", "Read 1000 chapters", AchievementTier.PLATINUM),
        Achievement("first_manga_finished", "Finisher", "Complete a manga", AchievementTier.SILVER),
        Achievement("five_manga_finished", "Collector", "Complete 5 manga", AchievementTier.GOLD),
        Achievement("tracker_connected", "Connected", "Connect a tracker", AchievementTier.BRONZE),
        Achievement("library_10", "Librarian", "Add 10 manga to library", AchievementTier.BRONZE),
        Achievement("library_100", "Archivist", "Add 100 manga to library", AchievementTier.GOLD),
        Achievement("rereader", "Rereader", "Reread a manga", AchievementTier.SILVER),
        Achievement("translator", "Polyglot", "Translate a chapter", AchievementTier.SILVER),
    )
}
