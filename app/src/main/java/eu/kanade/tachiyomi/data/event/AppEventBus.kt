// KMK -->
package eu.kanade.tachiyomi.data.event

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.webhook.WebhookEvent
import eu.kanade.tachiyomi.data.webhook.WebhookNotifier
import tachiyomi.domain.achievement.service.AchievementDispatcher
import tachiyomi.domain.achievement.service.AchievementEvent

/**
 * App-wide event bus fanning out to [AchievementDispatcher] and [WebhookNotifier].
 *
 * Call sites emit a single [AppEvent] instead of reaching for each system directly.
 * Delivery is fire-and-forget on both sides: achievement dispatch swallows errors
 * ([AchievementDispatcher.dispatchAsync]) and webhooks post off a background scope.
 */
sealed interface AppEvent {
    val achievementEvent: AchievementEvent?
    val webhookEvent: WebhookEvent?
    val webhookData: Map<String, String>
    val sourceId: Long?
    val mangaId: Long?

    data class LibraryUpdated(
        val mangasUpdated: Int,
        val newChapters: Int,
        val failed: Int,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent? = null
        override val webhookEvent: WebhookEvent = WebhookEvent.LIBRARY_UPDATE
        override val webhookData: Map<String, String> = mapOf(
            "mangas_updated" to mangasUpdated.toString(),
            "new_chapters" to newChapters.toString(),
            "failed" to failed.toString(),
        )
        override val sourceId: Long? = null
        override val mangaId: Long? = null
    }

    data class DownloadsFinished(
        val chaptersDownloaded: Int,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent? = null
        override val webhookEvent: WebhookEvent = WebhookEvent.DOWNLOADS_FINISHED
        override val webhookData: Map<String, String> = mapOf(
            "chapters_downloaded" to chaptersDownloaded.toString(),
        )
        override val sourceId: Long? = null
        override val mangaId: Long? = null
    }

    data class FavoriteToggled(
        val mangaTitle: String,
        val favorite: Boolean,
        override val sourceId: Long?,
        override val mangaId: Long?,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent? = null
        override val webhookEvent: WebhookEvent =
            if (favorite) WebhookEvent.MANGA_ADDED else WebhookEvent.MANGA_REMOVED
        override val webhookData: Map<String, String> = mapOf("manga" to mangaTitle)
    }

    data class Achievement(
        val event: AchievementEvent,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent = event
        override val webhookEvent: WebhookEvent? = null
        override val webhookData: Map<String, String> = emptyMap()
        override val sourceId: Long? = null
        override val mangaId: Long? = null
    }

    data class ChapterStarted(
        val mangaTitle: String,
        val chapterName: String,
        override val sourceId: Long?,
        override val mangaId: Long?,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent? = null
        override val webhookEvent: WebhookEvent = WebhookEvent.CHAPTER_STARTED
        override val webhookData: Map<String, String> = mapOf(
            "manga" to mangaTitle,
            "chapter" to chapterName,
        )
    }

    data class ChapterRead(
        override val webhookData: Map<String, String>,
        override val sourceId: Long?,
        override val mangaId: Long?,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent? = null
        override val webhookEvent: WebhookEvent = WebhookEvent.CHAPTER_READ
    }

    data class MangaStarted(
        val mangaTitle: String,
        val chapterName: String,
        override val sourceId: Long?,
        override val mangaId: Long?,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent? = null
        override val webhookEvent: WebhookEvent = WebhookEvent.NEW_MANGA_STARTED
        override val webhookData: Map<String, String> = mapOf(
            "manga" to mangaTitle,
            "chapter" to chapterName,
        )
    }

    data class MangaCompleted(
        val mangaTitle: String,
        val finished: Boolean,
        override val sourceId: Long?,
        override val mangaId: Long?,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent? = null
        override val webhookEvent: WebhookEvent =
            if (finished) WebhookEvent.MANGA_FINISHED else WebhookEvent.MANGA_CAUGHT_UP
        override val webhookData: Map<String, String> = mapOf("manga" to mangaTitle)
    }

    data class BackupCreated(
        val location: String,
        val automatic: Boolean,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent? = null
        override val webhookEvent: WebhookEvent = WebhookEvent.BACKUP_CREATED
        override val webhookData: Map<String, String> = mapOf(
            "location" to location,
            "automatic" to automatic.toString(),
        )
        override val sourceId: Long? = null
        override val mangaId: Long? = null
    }

    data class BackupRestored(
        val mode: String,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent? = null
        override val webhookEvent: WebhookEvent = WebhookEvent.BACKUP_RESTORED
        override val webhookData: Map<String, String> = mapOf("mode" to mode)
        override val sourceId: Long? = null
        override val mangaId: Long? = null
    }

    data class AppUpdated(
        val previousVersionCode: Int,
        val newVersionCode: Int,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent? = null
        override val webhookEvent: WebhookEvent = WebhookEvent.APP_UPDATED
        override val webhookData: Map<String, String> = mapOf(
            "previous_version_code" to previousVersionCode.toString(),
            "new_version_code" to newVersionCode.toString(),
        )
        override val sourceId: Long? = null
        override val mangaId: Long? = null
    }

    data class MangaMigrated(
        val mangaTitle: String,
        val fromSource: String,
        val toSource: String,
        override val sourceId: Long?,
        override val mangaId: Long?,
    ) : AppEvent {
        override val achievementEvent: AchievementEvent? = null
        override val webhookEvent: WebhookEvent = WebhookEvent.MANGA_MIGRATED
        override val webhookData: Map<String, String> = mapOf(
            "manga" to mangaTitle,
            "from_source" to fromSource,
            "to_source" to toSource,
        )
    }
}

@Inject
@SingleIn(AppScope::class)
class AppEventBus(
    private val achievementDispatcher: AchievementDispatcher,
    private val webhookNotifier: WebhookNotifier,
) {
    /**
     * Fan out to each sink in isolation: a throwing sink is swallowed so one
     * system's failure can never break the caller or the other system. Safe
     * to call from any thread (both sinks post off-thread themselves).
     */
    fun emit(event: AppEvent) {
        event.achievementEvent?.let {
            runCatching { achievementDispatcher.dispatchAsync(it) }
        }
        event.webhookEvent?.let { webhook ->
            runCatching {
                webhookNotifier.notify(
                    webhook,
                    event.webhookData,
                    sourceId = event.sourceId,
                    mangaId = event.mangaId,
                )
            }
        }
    }
}
// KMK <--
