package tachiyomi.domain.achievement.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.achievement.model.AchievementStats
import tachiyomi.domain.achievement.model.Achievements

@SingleIn(AppScope::class)
@Inject
class AchievementManager(
    private val prefs: AchievementPreferences,
    private val notifier: AchievementUnlockNotifier? = null,
) {
    @Synchronized
    fun onOrganicChapterRead(totalRead: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (totalRead < 0) return emptyList()
        prefs.incrementOrganicRead()
        val count = prefs.organicChaptersRead().get().coerceAtLeast(0L)
        val r = checkThresholds(count)
        if (r.isNotEmpty()) notifyIfNeeded(r)
        return r
    }

    @Synchronized
    fun onMangaFinished(): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        prefs.incrementMangaFinished()
        val count = prefs.mangaFinishedCount().get().coerceAtLeast(0L)
        val unlocked = mutableListOf<String>()
        if (count >= 1) tryUnlock("first_manga_finished", unlocked)
        if (count >= 5) tryUnlock("five_manga_finished", unlocked)
        if (count >= 10) tryUnlock("ten_manga_finished", unlocked)
        if (count >= 20) tryUnlock("twenty_manga_finished", unlocked)
        if (count >= 50) tryUnlock("fifty_manga_finished", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun onLibraryCountChanged(count: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val safe = count.coerceIn(0L, 10_000L)
        prefs.setLibraryCount(safe)
        val unlocked = mutableListOf<String>()
        if (safe >= 1) tryUnlock("library_1", unlocked)
        if (safe >= 5) tryUnlock("library_5", unlocked)
        if (safe >= 10) tryUnlock("library_10", unlocked)
        if (safe >= 25) tryUnlock("library_25", unlocked)
        if (safe >= 50) tryUnlock("library_50", unlocked)
        if (safe >= 100) tryUnlock("library_100", unlocked)
        if (safe >= 250) tryUnlock("library_250", unlocked)
        if (safe >= 500) tryUnlock("library_500", unlocked)
        if (safe >= 1000) tryUnlock("library_1000", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun onTrackerConnected(totalTrackers: Int): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val safe = totalTrackers.coerceIn(0, 20)
        val unlocked = mutableListOf<String>()
        if (safe >= 1) tryUnlock("tracker_connected", unlocked)
        if (safe >= 2) tryUnlock("tracker_two", unlocked)
        if (safe >= 3) tryUnlock("tracker_three", unlocked)
        if (safe >= 5) tryUnlock("tracker_five", unlocked)
        if (safe >= 8) tryUnlock("tracker_all", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun onReread(count: Int = 1): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val safe = count.coerceIn(1, 10_000)
        val unlocked = mutableListOf<String>()
        tryUnlock("rereader", unlocked)
        if (safe >= 5) tryUnlock("reread_five", unlocked)
        if (safe >= 20) tryUnlock("reread_twenty", unlocked)
        if (safe >= 100) tryUnlock("reread_hundred", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun onTranslated(count: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val safe = count.coerceIn(0L, 10_000L)
        val unlocked = mutableListOf<String>()
        if (safe >= 1) tryUnlock("translator", unlocked)
        if (safe >= 5) tryUnlock("translator_five", unlocked)
        if (safe >= 10) tryUnlock("translator_ten", unlocked)
        if (safe >= 50) tryUnlock("translator_fifty", unlocked)
        if (safe >= 100) tryUnlock("translator_hundred", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun tryUnlockDirect(id: String): Boolean {
        if (!prefs.achievementsEnabled().get()) return false
        val out = mutableListOf<String>()
        tryUnlock(id, out)
        if (out.isNotEmpty()) notifyIfNeeded(out)
        return out.isNotEmpty()
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
        if (!prefs.achievementsEnabled().get()) return
        if (prefs.unlock(id)) out.add(id)
    }

    private fun notifyIfNeeded(unlocked: List<String>) {
        if (unlocked.isNotEmpty()) notifier?.onUnlocked(unlocked)
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
