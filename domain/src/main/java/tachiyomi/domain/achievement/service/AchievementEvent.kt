package tachiyomi.domain.achievement.service

sealed interface AchievementEvent {
    data class OrganicChapterRead(val totalHint: Long = 0) : AchievementEvent
    data class ReadingTimeMinutes(val minutes: Long) : AchievementEvent
    data class LibraryCountChanged(val count: Long) : AchievementEvent
    data class MangaFinished(val isPermanent: Boolean) : AchievementEvent
    object MangaCaughtUp : AchievementEvent
    object LtrFinished : AchievementEvent
    data class BacklogChanged(val libraryCount: Long, val finishedCount: Long) : AchievementEvent
    data class BacklogCleared(val count: Int = 1) : AchievementEvent
    data class Negative(val id: String) : AchievementEvent
    object EhBrowsed : AchievementEvent
    data class DirectUnlock(val id: String) : AchievementEvent
    data class TrackerConnected(val total: Int) : AchievementEvent
    data class Reread(val count: Int = 1) : AchievementEvent
    data class Translated(val count: Long) : AchievementEvent
}

class AchievementDispatcher @dev.zacsweers.metro.Inject constructor(
    private val manager: AchievementManager,
    private val prefs: AchievementPreferences,
) {
    @Synchronized
    fun dispatch(event: AchievementEvent): List<String> {
        if (!prefs.achievementsEnabled().get()) return emptyList()
        return when (event) {
            is AchievementEvent.OrganicChapterRead -> manager.onOrganicChapterRead(event.totalHint)
            is AchievementEvent.ReadingTimeMinutes -> manager.onReadingTimeMinutes(event.minutes)
            is AchievementEvent.LibraryCountChanged -> manager.onLibraryCountChanged(event.count)
            is AchievementEvent.MangaFinished -> if (event.isPermanent) manager.onMangaFinished() else manager.onMangaCaughtUp()
            is AchievementEvent.MangaCaughtUp -> manager.onMangaCaughtUp()
            is AchievementEvent.LtrFinished -> manager.onLtrFinished()
            is AchievementEvent.BacklogChanged -> manager.onBacklogChanged(event.libraryCount, event.finishedCount)
            is AchievementEvent.BacklogCleared -> manager.onBacklogCleared(event.count)
            is AchievementEvent.Negative -> manager.onNegativeEvent(event.id)
            is AchievementEvent.EhBrowsed -> manager.onEhBrowsed()
            is AchievementEvent.DirectUnlock -> if (manager.tryUnlockDirect(event.id)) listOf(event.id) else emptyList()
            is AchievementEvent.TrackerConnected -> manager.onTrackerConnected(event.total)
            is AchievementEvent.Reread -> manager.onReread(event.count)
            is AchievementEvent.Translated -> manager.onTranslated(event.count)
        }
    }

    fun dispatchAsync(event: AchievementEvent) {
        try { dispatch(event) } catch (_: Exception) {}
    }
}
