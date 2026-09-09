package tachiyomi.domain.achievement.model

import kotlinx.serialization.Serializable

@Serializable
data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val tier: AchievementTier,
    val category: AchievementCategory = AchievementCategory.READING,
    val unlockedAt: Long? = null,
) {
    val isUnlocked: Boolean get() = unlockedAt != null
}

enum class AchievementTier { BRONZE, SILVER, GOLD, PLATINUM, LEGENDARY }

enum class AchievementCategory { READING, LIBRARY, TRACKER, TRANSLATION, SOCIAL, EXPLORATION }

@Serializable
data class AchievementStats(
    val organicChaptersRead: Long = 0,
    val mangaFinished: Long = 0,
    val libraryCount: Long = 0,
    val totalAchievements: Int = 0,
    val unlockedCount: Int = 0,
) {
    val rank: String get() = when {
        unlockedCount >= 25 && organicChaptersRead >= 1000 && mangaFinished >= 10 -> "Legend"
        unlockedCount >= 18 && organicChaptersRead >= 500 && mangaFinished >= 5 -> "Master"
        unlockedCount >= 12 && organicChaptersRead >= 250 -> "Veteran"
        unlockedCount >= 6 -> "Explorer"
        unlockedCount >= 3 -> "Apprentice"
        else -> "Novice"
    }

    val rankTier: AchievementTier get() = when (rank) {
        "Legend" -> AchievementTier.LEGENDARY
        "Master" -> AchievementTier.PLATINUM
        "Veteran" -> AchievementTier.GOLD
        "Explorer" -> AchievementTier.SILVER
        else -> AchievementTier.BRONZE
    }
}

object Achievements {
    val all = listOf(
        Achievement("first_chapter", "First Steps", "Read your first chapter organically", AchievementTier.BRONZE, AchievementCategory.READING),
        Achievement("ten_chapters", "Getting Started", "Read 10 chapters organically", AchievementTier.BRONZE, AchievementCategory.READING),
        Achievement("fifty_chapters", "Half Century", "Read 50 chapters organically", AchievementTier.SILVER, AchievementCategory.READING),
        Achievement("hundred_chapters", "Century", "Read 100 chapters organically", AchievementTier.SILVER, AchievementCategory.READING),
        Achievement("two_fifty", "Quarter K", "Read 250 chapters", AchievementTier.GOLD, AchievementCategory.READING),
        Achievement("five_hundred", "Marathon", "Read 500 chapters organically", AchievementTier.GOLD, AchievementCategory.READING),
        Achievement("thousand", "Legend", "Read 1000 chapters organically", AchievementTier.PLATINUM, AchievementCategory.READING),
        Achievement("two_thousand", "Mythic", "Read 2000 chapters", AchievementTier.LEGENDARY, AchievementCategory.READING),
        Achievement("first_manga_finished", "Finisher", "Complete a manga", AchievementTier.SILVER, AchievementCategory.READING),
        Achievement("five_manga_finished", "Collector", "Complete 5 manga", AchievementTier.GOLD, AchievementCategory.READING),
        Achievement("ten_manga_finished", "Completionist", "Complete 10 manga", AchievementTier.PLATINUM, AchievementCategory.READING),
        Achievement("tracker_connected", "Connected", "Connect a tracker", AchievementTier.BRONZE, AchievementCategory.TRACKER),
        Achievement("tracker_three", "Tracker Trio", "Connect 3 trackers", AchievementTier.SILVER, AchievementCategory.TRACKER),
        Achievement("library_5", "Shelf Starter", "Add 5 manga to library", AchievementTier.BRONZE, AchievementCategory.LIBRARY),
        Achievement("library_10", "Librarian", "Add 10 manga to library", AchievementTier.BRONZE, AchievementCategory.LIBRARY),
        Achievement("library_50", "Curator", "Add 50 manga to library", AchievementTier.SILVER, AchievementCategory.LIBRARY),
        Achievement("library_100", "Archivist", "Add 100 manga to library", AchievementTier.GOLD, AchievementCategory.LIBRARY),
        Achievement("library_250", "Hoarder", "Add 250 manga to library", AchievementTier.PLATINUM, AchievementCategory.LIBRARY),
        Achievement("rereader", "Rereader", "Reread a manga", AchievementTier.SILVER, AchievementCategory.READING),
        Achievement("reread_five", "Nostalgic", "Reread 5 manga", AchievementTier.GOLD, AchievementCategory.READING),
        Achievement("translator", "Polyglot", "Translate a chapter", AchievementTier.SILVER, AchievementCategory.TRANSLATION),
        Achievement("translator_ten", "Bridge Builder", "Translate 10 chapters", AchievementTier.GOLD, AchievementCategory.TRANSLATION),
        Achievement("download_ten", "Offline Ready", "Download 10 chapters", AchievementTier.BRONZE, AchievementCategory.EXPLORATION),
        Achievement("category_master", "Organizer", "Create 5 categories", AchievementTier.SILVER, AchievementCategory.EXPLORATION),
        Achievement("night_owl", "Night Owl", "Read 10 chapters after midnight", AchievementTier.BRONZE, AchievementCategory.SOCIAL),
    )

    fun forId(id: String) = all.find { it.id == id }
}
