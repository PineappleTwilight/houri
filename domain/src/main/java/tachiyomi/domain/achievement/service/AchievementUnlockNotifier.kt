package tachiyomi.domain.achievement.service

interface AchievementUnlockNotifier {
    fun onUnlocked(ids: List<String>)
}
