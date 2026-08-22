// AM (CONNECTIONS) -->
package eu.kanade.tachiyomi.data.webhook

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.connections.service.WebhookPreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import logcat.logcat
import mihon.app.di.globalAppGraph
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.GetManga
import java.time.Instant

enum class WebhookEvent(val id: String, val title: String, val color: Long) {
    CHAPTER_STARTED("chapter_started", "Chapter started", 0xEB459E),
    CHAPTER_READ("chapter_read", "Chapter read", 0x5865F2),
    NEW_MANGA_STARTED("new_manga_started", "New manga started", 0x00B0F4),
    MANGA_FINISHED("manga_finished", "Manga finished", 0xFAA61A),
    LIBRARY_UPDATE("library_update", "Library updated", 0x3BA55D),
    BACKUP_CREATED("backup_created", "Backup created", 0xE67E22),
    // KMK -->
    MANGA_ADDED("manga_added", "Manga added", 0x2ECC71),
    MANGA_REMOVED("manga_removed", "Manga removed", 0xE74C3C),
    DOWNLOADS_FINISHED("downloads_finished", "Downloads finished", 0x9B59B6),
    BACKUP_RESTORED("backup_restored", "Backup restored", 0xF1C40F),
    MANGA_MIGRATED("manga_migrated", "Manga migrated", 0x1ABC9C),
    APP_UPDATED("app_updated", "App updated", 0x95A5A6),
    // KMK <--
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

    // KMK -->
    private val getCategories: GetCategories by lazy { globalAppGraph.getCategories }
    private val getIncognitoState: GetIncognitoState by lazy { globalAppGraph.getIncognitoState }
    private val getManga: GetManga by lazy { globalAppGraph.getManga }
    // KMK <--

    fun notify(
        event: WebhookEvent,
        data: Map<String, String>,
        // KMK -->
        sourceId: Long? = null,
        mangaId: Long? = null,
        // KMK <--
    ) {
        if (!webhookPreferences.enabled().get()) return

        val enabledForEvent = when (event) {
            WebhookEvent.CHAPTER_STARTED -> webhookPreferences.notifyOnChapterStarted()
            WebhookEvent.CHAPTER_READ -> webhookPreferences.notifyOnChapterRead()
            WebhookEvent.NEW_MANGA_STARTED -> webhookPreferences.notifyOnNewMangaStarted()
            WebhookEvent.MANGA_FINISHED -> webhookPreferences.notifyOnMangaFinished()
            WebhookEvent.LIBRARY_UPDATE -> webhookPreferences.notifyOnLibraryUpdate()
            WebhookEvent.BACKUP_CREATED -> webhookPreferences.notifyOnBackupCreated()
            // KMK -->
            WebhookEvent.MANGA_ADDED -> webhookPreferences.notifyOnMangaAdded()
            WebhookEvent.MANGA_REMOVED -> webhookPreferences.notifyOnMangaRemoved()
            WebhookEvent.DOWNLOADS_FINISHED -> webhookPreferences.notifyOnDownloadsFinished()
            WebhookEvent.BACKUP_RESTORED -> webhookPreferences.notifyOnBackupRestored()
            WebhookEvent.MANGA_MIGRATED -> webhookPreferences.notifyOnMangaMigrated()
            WebhookEvent.APP_UPDATED -> webhookPreferences.notifyOnAppUpdated()
            // KMK <--
        }
        if (!enabledForEvent.get()) return

        launchIO {
            // KMK -->
            if (isSuppressed(sourceId, mangaId)) return@launchIO
            val coverUrl = mangaId?.let { resolveCoverUrl(it) }
            sendToAll(event, data, coverUrl)
            // KMK <--
        }
    }

    // KMK -->
    private suspend fun isSuppressed(sourceId: Long?, mangaId: Long?): Boolean {
        if (sourceId != null && getIncognitoState.await(sourceId)) return true

        val excludedIds = webhookPreferences.excludedCategories().get()
            .mapNotNull(String::toLongOrNull)
            .toSet()
        if (mangaId == null || excludedIds.isEmpty()) return false

        val parentById = getCategories.await().associate { it.id to it.parentId }
        return getCategories.await(mangaId).any { category ->
            category.id in excludedIds ||
                generateSequence(category.parentId) { parentById[it] }
                    .takeWhile { it != 0L }
                    .any { it in excludedIds }
        }
    }

    /** Null when the cover is not a remote URL (local/custom file covers are unreachable by consumers). */
    private suspend fun resolveCoverUrl(mangaId: Long): String? = runCatching {
        getManga.await(mangaId)?.thumbnailUrl
            ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }.getOrNull()
    // KMK <--

    suspend fun sendTest() {
        sendToAll(
            WebhookEvent.CHAPTER_READ,
            mapOf(
                "message" to "This is a test notification",
                "manga" to "Test manga",
                "chapter" to "Test chapter",
            ),
            // KMK -->
            null,
            // KMK <--
        )
    }

    private suspend fun sendToAll(
        event: WebhookEvent,
        data: Map<String, String>,
        // KMK -->
        coverUrl: String?,
        // KMK <--
    ) {
        val discordUrl = webhookPreferences.discordWebhookUrl().get()
        val genericUrl = webhookPreferences.genericWebhookUrl().get()

        if (discordUrl.isNotBlank()) {
            // KMK -->
            post(discordUrl, json.encodeToString(buildDiscordPayload(event, data, coverUrl)))
            // KMK <--
        }
        if (genericUrl.isNotBlank()) {
            // KMK -->
            post(genericUrl, json.encodeToString(buildGenericPayload(event, data, coverUrl)))
            // KMK <--
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

    private fun buildDiscordPayload(
        event: WebhookEvent,
        data: Map<String, String>,
        // KMK -->
        coverUrl: String?,
        // KMK <--
    ): JsonObject {
        // KMK -->
        val description = embedDescription(event, data)
        val fields = embedFields(data)
        // KMK <--

        return buildJsonObject {
            putJsonArray("embeds") {
                add(
                    buildJsonObject {
                        put("title", event.title)
                        put("color", event.color)
                        put("timestamp", Instant.now().toString())
                        // KMK -->
                        description?.let { put("description", it) }
                        coverUrl?.let { cover ->
                            putJsonObject("thumbnail") {
                                put("url", cover)
                            }
                        }
                        if (fields.isNotEmpty()) {
                            putJsonArray("fields") {
                                fields.forEach { add(it) }
                            }
                        }
                        putJsonObject("footer") {
                            put("text", "Houri v${BuildConfig.VERSION_NAME}")
                        }
                        // KMK <--
                    },
                )
            }
        }
    }

    // KMK -->
    private val baseDescriptionKeys = setOf("manga", "chapter", "message")

    private fun consumedKeys(data: Map<String, String>): Set<String> = if (
        data.containsKey("manga") &&
        data.containsKey("from_source") &&
        data.containsKey("to_source")
    ) {
        baseDescriptionKeys + setOf("from_source", "to_source")
    } else {
        baseDescriptionKeys
    }

    private val hiddenEmbedFields = setOf("time_spent_seconds")

    private val fullWidthEmbedFields = setOf("location")

    private fun embedDescription(event: WebhookEvent, data: Map<String, String>): String? {
        val parts = buildList {
            data["manga"]?.let { manga ->
                add("**$manga**")
                val subtitle = when {
                    data["chapter"] != null -> data["chapter"]
                    data.containsKey("from_source") && data.containsKey("to_source") ->
                        "${data["from_source"]} → ${data["to_source"]}"
                    event == WebhookEvent.MANGA_FINISHED -> "All chapters read"
                    else -> null
                }
                subtitle?.let(::add)
            }
            data["message"]?.let(::add)
        }
        return parts.joinToString("\n\n").ifEmpty { null }
    }

    private fun embedFields(data: Map<String, String>): List<JsonObject> = data
        .filterNot { (key, _) -> key in consumedKeys(data) || key in hiddenEmbedFields }
        .filterNot { (key, value) -> key == "failed" && value == "0" }
        .map { (key, value) ->
            buildJsonObject {
                put("name", key.toEmbedFieldLabel())
                put("value", value.toEmbedFieldValue(key))
                put("inline", key !in fullWidthEmbedFields)
            }
        }

    private fun String.toEmbedFieldLabel(): String = replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun String.toEmbedFieldValue(key: String): String = when {
        key == "location" -> "`$this`"
        this == "true" -> "Yes"
        this == "false" -> "No"
        else -> this
    }
    // KMK <--

    private fun buildGenericPayload(
        event: WebhookEvent,
        data: Map<String, String>,
        // KMK -->
        coverUrl: String?,
        // KMK <--
    ): JsonObject {
        return buildJsonObject {
            put("event", event.id)
            put("title", event.title)
            put("timestamp", Instant.now().toString())
            // KMK -->
            coverUrl?.let { put("cover_url", it) }
            // KMK <--
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
