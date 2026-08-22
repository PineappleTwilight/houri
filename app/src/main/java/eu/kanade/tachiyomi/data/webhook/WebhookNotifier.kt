// AM (CONNECTIONS) -->
package eu.kanade.tachiyomi.data.webhook

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.connections.service.WebhookPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.LogPriority
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.launchIO
import java.time.Instant

enum class WebhookEvent(val id: String, val title: String, val color: Long) {
    CHAPTER_STARTED("chapter_started", "Chapter started", 0xEB459E),
    CHAPTER_READ("chapter_read", "Chapter read", 0x5865F2),
    NEW_MANGA_STARTED("new_manga_started", "New manga started", 0x00B0F4),
    MANGA_FINISHED("manga_finished", "Manga finished", 0xFAA61A),
    LIBRARY_UPDATE("library_update", "Library updated", 0x3BA55D),
    BACKUP_CREATED("backup_created", "Backup created", 0xE67E22),
}

/**
 * Posts app events to user-configured Discord webhooks and/or a generic
 * JSON webhook endpoint. Delivery is fire-and-forget: failures are logged
 * and never interrupt the triggering action.
 */
@Inject
@SingleIn(AppScope::class)
class WebhookNotifier(
    private val webhookPreferences: WebhookPreferences,
    private val networkHelper: NetworkHelper,
) {

    private val json = Json
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun notify(event: WebhookEvent, data: Map<String, String>) {
        if (!webhookPreferences.enabled().get()) return

        val enabledForEvent = when (event) {
            WebhookEvent.CHAPTER_STARTED -> webhookPreferences.notifyOnChapterStarted()
            WebhookEvent.CHAPTER_READ -> webhookPreferences.notifyOnChapterRead()
            WebhookEvent.NEW_MANGA_STARTED -> webhookPreferences.notifyOnNewMangaStarted()
            WebhookEvent.MANGA_FINISHED -> webhookPreferences.notifyOnMangaFinished()
            WebhookEvent.LIBRARY_UPDATE -> webhookPreferences.notifyOnLibraryUpdate()
            WebhookEvent.BACKUP_CREATED -> webhookPreferences.notifyOnBackupCreated()
        }
        if (!enabledForEvent.get()) return

        launchIO {
            sendToAll(event, data)
        }
    }

    suspend fun sendTest() {
        sendToAll(
            WebhookEvent.CHAPTER_READ,
            mapOf(
                "message" to "This is a test notification",
                "manga" to "Test manga",
                "chapter" to "Test chapter",
            ),
        )
    }

    private suspend fun sendToAll(event: WebhookEvent, data: Map<String, String>) {
        val discordUrl = webhookPreferences.discordWebhookUrl().get()
        val genericUrl = webhookPreferences.genericWebhookUrl().get()

        if (discordUrl.isNotBlank()) {
            post(discordUrl, json.encodeToString(buildDiscordPayload(event, data)))
        }
        if (genericUrl.isNotBlank()) {
            post(genericUrl, json.encodeToString(buildGenericPayload(event, data)))
        }
    }

    private suspend fun post(url: String, payload: String) {
        runCatching {
            networkHelper.client.newCall(
                Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody(jsonMediaType))
                    .build(),
            ).awaitSuccess()
        }.onFailure {
            logcat(LogPriority.WARN) { "Failed to deliver webhook payload: ${it.message}" }
        }
    }

    private fun buildDiscordPayload(event: WebhookEvent, data: Map<String, String>): JsonObject {
        return buildJsonObject {
            putJsonArray("embeds") {
                add(
                    buildJsonObject {
                        put("title", event.title)
                        put("color", event.color)
                        putJsonArray("fields") {
                            data.forEach { (key, value) ->
                                add(
                                    buildJsonObject {
                                        put("name", key)
                                        put("value", value)
                                        put("inline", true)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    private fun buildGenericPayload(event: WebhookEvent, data: Map<String, String>): JsonObject {
        return buildJsonObject {
            put("event", event.id)
            put("title", event.title)
            put("timestamp", Instant.now().toString())
            data.forEach { (key, value) -> put(key, value) }
        }
    }

    companion object {
        fun formatReadingDuration(totalSeconds: Long): String {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return buildString {
                if (hours > 0) append("${hours}h ")
                if (minutes > 0 || hours > 0) append("${minutes}m ")
                append("${seconds}s")
            }.trim()
        }
    }
}
// <-- AM (CONNECTIONS)
