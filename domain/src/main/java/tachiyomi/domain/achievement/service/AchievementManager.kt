package tachiyomi.domain.achievement.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.achievement.model.Achievements
import tachiyomi.domain.achievement.model.AchievementStats

@SingleIn(AppScope::class)
@Inject
class AchievementManager(
    private val prefs: AchievementPreferences,
) {
    fun onOrganicChapterRead(totalRead: Long): List<String> {
        prefs.incrementOrganicRead()
        val count = prefs.organicChaptersRead().get()
        return checkThresholds(count)
    }

    fun onMangaFinished(): List<String> {
        prefs.incrementMangaFinished()
        val count = prefs.mangaFinishedCount().get()
        val unlocked = mutableListOf<String>()
        if (count >= 1) tryUnlock("first_manga_finished", unlocked)
        if (count >= 5) tryUnlock("five_manga_finished", unlocked)
        if (count >= 10) tryUnlock("ten_manga_finished", unlocked)
        return unlocked
    }

    fun onLibraryCountChanged(count: Long): List<String> {
        prefs.setLibraryCount(count)
        val unlocked = mutableListOf<String>()
        if (count >= 5) tryUnlock("library_5", unlocked)
        if (count >= 10) tryUnlock("library_10", unlocked)
        if (count >= 50) tryUnlock("library_50", unlocked)
        if (count >= 100) tryUnlock("library_100", unlocked)
        if (count >= 250) tryUnlock("library_250", unlocked)
        return unlocked
    }

    fun onTrackerConnected(totalTrackers: Int): List<String> {
        val unlocked = mutableListOf<String>()
        if (totalTrackers >= 1) tryUnlock("tracker_connected", unlocked)
        if (totalTrackers >= 3) tryUnlock("tracker_three", unlocked)
        return unlocked
    }

    fun onReread(count: Int = 1): List<String> {
        val unlocked = mutableListOf<String>()
        tryUnlock("rereader", unlocked)
        if (count >= 5) tryUnlock("reread_five", unlocked)
        return unlocked
    }

    fun onTranslated(count: Long): List<String> {
        val unlocked = mutableListOf<String>()
        if (count >= 1) tryUnlock("translator", unlocked)
        if (count >= 10) tryUnlock("translator_ten", unlocked)
        return unlocked
    }

    private fun checkThresholds(count: Long): List<String> {
        val unlocked = mutableListOf<String>()
        if (count >= 1) tryUnlock("first_chapter", unlocked)
        if (count >= 10) tryUnlock("ten_chapters", unlocked)
        if (count >= 50) tryUnlock("fifty_chapters", unlocked)
        if (count >= 100) tryUnlock("hundred_chapters", unlocked)
        if (count >= 250) tryUnlock("two_fifty", unlocked)
        if (count >= 500) tryUnlock("five_hundred", unlocked)
        if (count >= 1000) tryUnlock("thousand", unlocked)
        if (count >= 2000) tryUnlock("two_thousand", unlocked)
        return unlocked
    }

    private fun tryUnlock(id: String, out: MutableList<String>) {
        if (prefs.unlock(id)) out.add(id)
    }

    fun getStats(): AchievementStats = prefs.computeStats(Achievements.all.size)

    fun getUnlockedWithMeta(): List<Pair<String, Long>> {
        val map = prefs.getUnlockedWithTimestamps()
        return map.entries.map { it.key to it.value }.sortedBy { it.second }
    }

    fun wipeWithConfirmation(firstConfirmed: Boolean, secondConfirmed: Boolean): Boolean {
        if (!firstConfirmed || !secondConfirmed) return false
        prefs.wipe()
        return true
    }
}
