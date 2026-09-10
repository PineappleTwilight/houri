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
        checkUltimateProgress()
        return r
    }

    @Synchronized
    fun onReadingTimeMinutes(minutes: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (minutes <= 0) return emptyList()
        prefs.addReadingTimeMinutes(minutes)
        val total = prefs.totalReadingTimeMinutes().get().coerceAtLeast(0L)
        val unlocked = mutableListOf<String>()
        if (total >= 60) tryUnlock("reading_time_1h", unlocked)
        if (total >= 600) tryUnlock("reading_time_10h", unlocked)
        if (total >= 3000) tryUnlock("reading_time_50h", unlocked)
        if (total >= 6000) tryUnlock("reading_time_100h", unlocked)
        if (total >= 30000) tryUnlock("reading_time_500h", unlocked)
        if (total >= 60000) tryUnlock("reading_time_1000h", unlocked)
        if (total >= 120000) tryUnlock("ultimate_time_dilation", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        checkUltimateProgress()
        return unlocked
    }

    @Synchronized
    fun onBacklogChanged(libraryCount: Long, finishedCount: Long): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val backlog = (libraryCount - finishedCount).coerceAtLeast(0L)
        val unlocked = mutableListOf<String>()
        if (backlog >= 10) tryUnlock("backlog_10", unlocked)
        if (backlog >= 25) tryUnlock("backlog_25", unlocked)
        if (backlog >= 50) tryUnlock("backlog_50", unlocked)
        if (backlog >= 100) tryUnlock("backlog_100", unlocked)
        if (backlog >= 250) tryUnlock("backlog_250", unlocked)
        if (backlog >= 500) {
            tryUnlock("negative_hoarder_shame", unlocked)
            if (finishedCount == 0L) tryUnlock("negative_abandoned", unlocked)
        }
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun onBacklogCleared(count: Int = 1): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        repeat(count.coerceIn(1, 1000)) { prefs.incrementBacklogCleared() }
        val total = prefs.backlogClearedCount().get().coerceAtLeast(0L)
        val unlocked = mutableListOf<String>()
        if (total >= 10) tryUnlock("backlog_cleared_10", unlocked)
        if (total >= 100) tryUnlock("backlog_cleared_100", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        checkUltimateProgress()
        return unlocked
    }

    @Synchronized
    fun onLtrFinished(): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        prefs.incrementLtrFinished()
        val unlocked = mutableListOf<String>()
        tryUnlock("ltr_reader", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun onNegativeEvent(id: String): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val allowed = setOf(
            "negative_binge_guilt",
            "negative_abandoned",
            "negative_midnight_oil",
            "negative_spoiled",
            "negative_hoarder_shame",
            "negative_rage_quit",
            "negative_do_not_disturb",
        )
        if (id !in allowed) return emptyList()
        val unlocked = mutableListOf<String>()
        tryUnlock(id, unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun onEhBrowsed(): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        val unlocked = mutableListOf<String>()
        tryUnlock("eh_browsed", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        return unlocked
    }

    @Synchronized
    fun onMangaFinished(): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (prefs.suppressOrganicForImport) return emptyList()
        prefs.incrementMangaFinished()
        val count = prefs.mangaFinishedCount().get().coerceAtLeast(0L)
        val unlocked = mutableListOf<String>()
        if (count >= 1) tryUnlock("first_manga_finished", unlocked)
        if (count >= 5) tryUnlock("five_manga_finished", unlocked)
        if (count >= 10) tryUnlock("ten_manga_finished", unlocked)
        if (count >= 20) tryUnlock("twenty_manga_finished", unlocked)
        if (count >= 50) tryUnlock("fifty_manga_finished", unlocked)
        onBacklogCleared(1)
        onBacklogChanged(prefs.libraryMangaCount().get(), count)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        checkUltimateProgress()
        return unlocked
    }

    @Synchronized
    fun onMangaCaughtUp(): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        if (prefs.suppressOrganicForImport) return emptyList()
        prefs.incrementMangaCaughtUp()
        val count = prefs.mangaCaughtUpCount().get().coerceAtLeast(0L)
        val unlocked = mutableListOf<String>()
        if (count >= 1) tryUnlock("first_manga_caught_up", unlocked)
        if (count >= 5) tryUnlock("five_manga_caught_up", unlocked)
        if (count >= 10) tryUnlock("ten_manga_caught_up", unlocked)
        if (count >= 20) tryUnlock("twenty_manga_caught_up", unlocked)
        if (count >= 50) tryUnlock("fifty_manga_caught_up", unlocked)
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        checkUltimateProgress()
        return unlocked
    }

    fun isPermanentStatus(status: Long): Boolean {
        return when (status.toInt()) {
            eu.kanade.tachiyomi.source.model.SManga.COMPLETED,
            eu.kanade.tachiyomi.source.model.SManga.CANCELLED,
            eu.kanade.tachiyomi.source.model.SManga.PUBLISHING_FINISHED,
            eu.kanade.tachiyomi.source.model.SManga.LICENSED -> true
            else -> false
        }
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
        onBacklogChanged(safe, prefs.mangaFinishedCount().get())
        if (unlocked.isNotEmpty()) notifyIfNeeded(unlocked)
        checkUltimateProgress()
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
        if (count >= 25) tryUnlock("twenty_five", unlocked)
        if (count >= 50) tryUnlock("fifty_chapters", unlocked)
        if (count >= 100) tryUnlock("hundred_chapters", unlocked)
        if (count >= 250) tryUnlock("two_fifty", unlocked)
        if (count >= 500) tryUnlock("five_hundred", unlocked)
        if (count >= 1000) tryUnlock("thousand", unlocked)
        if (count >= 2000) tryUnlock("two_thousand", unlocked)
        if (count >= 5000) tryUnlock("five_thousand", unlocked)
        if (count >= 10000) tryUnlock("ten_thousand", unlocked)
        if (count >= 20000) tryUnlock("ultimate_ink_god", unlocked)
        return unlocked
    }

    private fun checkUltimateProgress() {
        val countable = prefs.getUnlockedIds().count { Achievements.forId(it)?.countsTowardsProgress == true }
        if (countable >= 200) tryUnlockDirect("ultimate_perfection")
        val library = prefs.libraryMangaCount().get()
        if (library >= 2000) tryUnlockDirect("ultimate_eternal_library")
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
