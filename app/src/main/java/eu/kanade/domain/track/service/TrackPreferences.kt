package eu.kanade.domain.track.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

@SingleIn(AppScope::class)
@Inject
class TrackPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun trackUsername(tracker: Tracker) = preferenceStore.getString(
        Preference.privateKey("pref_mangasync_username_${tracker.id}"),
        "",
    )

    fun trackPassword(tracker: Tracker) = preferenceStore.getString(
        Preference.privateKey("pref_mangasync_password_${tracker.id}"),
        "",
    )

    fun trackAuthExpired(tracker: Tracker) = preferenceStore.getBoolean(
        Preference.privateKey("pref_tracker_auth_expired_${tracker.id}"),
        false,
    )

    fun setCredentials(tracker: Tracker, username: String, password: String) {
        trackUsername(tracker).set(username)
        trackPassword(tracker).set(password)
        trackAuthExpired(tracker).set(false)
    }

    fun trackToken(tracker: Tracker) = preferenceStore.getString(Preference.privateKey("track_token_${tracker.id}"), "")

    fun anilistScoreType() = preferenceStore.getString("anilist_score_type", Anilist.POINT_10)

    fun mangabakaScoreType() = preferenceStore.getString("mangabaka_score_type", "STEP_10")

    fun autoUpdateTrack() = preferenceStore.getBoolean("pref_auto_update_manga_sync_key", true)

    fun trackOnAddingToLibrary() = preferenceStore.getBoolean("track_on_adding_to_library", true)

    fun autoUpdateTrackOnMarkRead() = preferenceStore.getEnum(
        "pref_auto_update_manga_on_mark_read",
        AutoTrackState.ALWAYS,
    )

    // SY -->
    fun resolveUsingSourceMetadata() = preferenceStore.getBoolean(
        "pref_resolve_using_source_metadata_key",
        true,
    )
    // SY <--

    // KMK -->
    fun autoSyncProgressFromTrackers() = preferenceStore.getBoolean("pref_auto_sync_progress_from_trackers_key", true)

    fun preferredTrackerForManga() = preferenceStore.getString("pref_preferred_tracker_for_manga", "")

    fun preferredTrackerForCategory() = preferenceStore.getString("pref_preferred_tracker_for_category", "")

    private val preferredMapLock = Any()
    private val preferredMapJson = Json { ignoreUnknownKeys = true }
    private companion object {
        const val PREFERRED_MAP_MAX_ENTRIES = 5000
        const val PREFERRED_MAP_MAX_RAW_LENGTH = 100_000
    }

    private fun decodePreferredMap(raw: String): MutableMap<Long, Long> {
        if (raw.isBlank()) return mutableMapOf()
        val trimmed = raw.trim().take(PREFERRED_MAP_MAX_RAW_LENGTH)
        if (trimmed.startsWith("{")) {
            try {
                val stringMap = preferredMapJson.decodeFromString<Map<String, Long>>(trimmed)
                return stringMap.mapNotNull { (k, v) ->
                    val key = k.toLongOrNull() ?: return@mapNotNull null
                    if (key <= 0L || v <= 0L) return@mapNotNull null
                    key to v
                }.toMap().toMutableMap()
            } catch (_: Exception) {
            }
        }
        return trimmed.split(";").mapNotNull { entry ->
            if (entry.isBlank()) return@mapNotNull null
            try {
                val parts = entry.split(":", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val k = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
                val v = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
                if (k <= 0L || v <= 0L) return@mapNotNull null
                k to v
            } catch (_: Exception) {
                null
            }
        }.take(PREFERRED_MAP_MAX_ENTRIES).toMap().toMutableMap()
    }

    private fun encodePreferredMap(map: Map<Long, Long>): String {
        val filtered = map.entries
            .filter { it.key > 0L && it.value > 0L }
            .take(PREFERRED_MAP_MAX_ENTRIES)
            .associate { it.key.toString() to it.value }
        return try {
            preferredMapJson.encodeToString(filtered)
        } catch (_: Exception) {
            filtered.entries.joinToString(";") { "${it.key}:${it.value}" }
        }
    }

    fun getPreferredTrackerForManga(mangaId: Long): Long? {
        if (mangaId <= 0L) return null
        synchronized(preferredMapLock) {
            val raw = preferredTrackerForManga().get()
            if (raw.isBlank()) return null
            return try {
                decodePreferredMap(raw)[mangaId]
            } catch (_: Exception) {
                null
            }
        }
    }

    fun setPreferredTrackerForManga(mangaId: Long, trackerId: Long?) {
        if (mangaId <= 0L) return
        if (trackerId != null && trackerId <= 0L) return
        synchronized(preferredMapLock) {
            val raw = preferredTrackerForManga().get()
            val map = try {
                decodePreferredMap(raw)
            } catch (_: Exception) {
                mutableMapOf()
            }
            if (trackerId == null) map.remove(mangaId) else map[mangaId] = trackerId
            if (map.size > PREFERRED_MAP_MAX_ENTRIES) {
                val toRemove = map.size - PREFERRED_MAP_MAX_ENTRIES
                map.entries.take(toRemove).forEach { map.remove(it.key) }
            }
            preferredTrackerForManga().set(encodePreferredMap(map))
        }
    }

    fun getPreferredTrackerForCategory(categoryId: Long): Long? {
        if (categoryId <= 0L) return null
        synchronized(preferredMapLock) {
            val raw = preferredTrackerForCategory().get()
            if (raw.isBlank()) return null
            return try {
                decodePreferredMap(raw)[categoryId]
            } catch (_: Exception) {
                null
            }
        }
    }

    fun setPreferredTrackerForCategory(categoryId: Long, trackerId: Long?) {
        if (categoryId <= 0L) return
        if (trackerId != null && trackerId <= 0L) return
        synchronized(preferredMapLock) {
            val raw = preferredTrackerForCategory().get()
            val map = try {
                decodePreferredMap(raw)
            } catch (_: Exception) {
                mutableMapOf()
            }
            if (trackerId == null) map.remove(categoryId) else map[categoryId] = trackerId
            if (map.size > PREFERRED_MAP_MAX_ENTRIES) {
                val toRemove = map.size - PREFERRED_MAP_MAX_ENTRIES
                map.entries.take(toRemove).forEach { map.remove(it.key) }
            }
            preferredTrackerForCategory().set(encodePreferredMap(map))
        }
    }
    // KMK <--
}
