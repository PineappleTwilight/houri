package eu.kanade.tachiyomi.data.achievement

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.domain.achievement.service.AchievementManager

/**
 * Modular handler that encapsulates Reader -> Achievement mapping.
 *
 * Previously this logic was inlined in [eu.kanade.tachiyomi.ui.reader.ReaderViewModel]
 * with direct `achievementManager.tryUnlockDirect("rtl_reader")` string literals
 * scattered across the ViewModel, violating single-responsibility and making
 * testing impossible. This handler centralizes:
 * - organic chapter read counting
 * - reading-mode specific achievements (LTR/RTL/vertical)
 * - manga completion vs caught-up branching
 * - reading-time batching
 *
 * ViewModel delegates to this handler, enabling unit testing and keeping
 * achievement IDs out of the ViewModel.
 */
@SingleIn(AppScope::class)
@Inject
class ReaderAchievementHandler(
    private val achievementManager: AchievementManager,
) {
    fun onChapterRead(readingModeFlag: Int) {
        try {
            achievementManager.onOrganicChapterRead(0)
            when (readingModeFlag) {
                ReadingMode.LEFT_TO_RIGHT.flagValue -> achievementManager.onLtrFinished()
                ReadingMode.RIGHT_TO_LEFT.flagValue -> achievementManager.tryUnlockDirect("rtl_reader")
                ReadingMode.VERTICAL.flagValue,
                ReadingMode.WEBTOON.flagValue,
                ReadingMode.CONTINUOUS_VERTICAL.flagValue,
                -> achievementManager.tryUnlockDirect("vertical_reader")
            }
            // Pager double-page spreads are handled separately via PagerConfig,
            // WebGPU spreads via WebGpuViewer — keep out of this handler.
        } catch (_: Exception) {
        }
    }

    fun onDoublePageDisplayed() {
        try {
            achievementManager.tryUnlockDirect("double_page")
        } catch (_: Exception) {
        }
    }

    fun onMangaCompleted(status: Long) {
        try {
            val isPermanent = achievementManager.isPermanentStatus(status)
            if (isPermanent) {
                achievementManager.onMangaFinished()
            } else {
                achievementManager.onMangaCaughtUp()
                achievementManager.onBacklogCleared(1)
            }
        } catch (_: Exception) {
        }
    }

    fun onReadingTimeMinutes(minutes: Long) {
        try {
            if (minutes > 0) achievementManager.onReadingTimeMinutes(minutes)
        } catch (_: Exception) {
        }
    }
}
