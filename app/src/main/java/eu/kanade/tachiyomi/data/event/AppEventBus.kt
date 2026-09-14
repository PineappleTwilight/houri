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
}

@Inject
@SingleIn(AppScope::class)
class AppEventBus(
    private val achievementDispatcher: AchievementDispatcher,
    private val webhookNotifier: WebhookNotifier,
) {
    fun emit(event: AppEvent) {
        event.achievementEvent?.let { achievementDispatcher.dispatchAsync(it) }
        event.webhookEvent?.let { webhook ->
            webhookNotifier.notify(
                webhook,
                event.webhookData,
                sourceId = event.sourceId,
                mangaId = event.mangaId,
            )
        }
    }
}
// KMK <--
