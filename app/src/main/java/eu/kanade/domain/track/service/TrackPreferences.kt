package eu.kanade.domain.track.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.anilist.Anilist
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

    fun getPreferredTrackerForManga(mangaId: Long): Long? {
        val raw = preferredTrackerForManga().get()
        if (raw.isBlank()) return null
        return try {
            val map = raw.split(";").associate {
                val (k, v) = it.split(":", limit = 2)
                k.toLong() to v.toLong()
            }
            map[mangaId]
        } catch (_: Exception) {
            null
        }
    }

    fun setPreferredTrackerForManga(mangaId: Long, trackerId: Long?) {
        val raw = preferredTrackerForManga().get()
        val map = try {
            if (raw.isBlank()) {
                mutableMapOf<Long, Long>()
            } else {
                raw.split(";").associate {
                    val (k, v) = it.split(":", limit = 2)
                    k.toLong() to v.toLong()
                }.toMutableMap()
            }
        } catch (_: Exception) {
            mutableMapOf()
        }
        if (trackerId == null) map.remove(mangaId) else map[mangaId] = trackerId
        preferredTrackerForManga().set(map.entries.joinToString(";") { "${it.key}:${it.value}" })
    }

    fun getPreferredTrackerForCategory(categoryId: Long): Long? {
        val raw = preferredTrackerForCategory().get()
        if (raw.isBlank()) return null
        return try {
            val map = raw.split(";").associate {
                val (k, v) = it.split(":", limit = 2)
                k.toLong() to v.toLong()
            }
            map[categoryId]
        } catch (_: Exception) {
            null
        }
    }

    fun setPreferredTrackerForCategory(categoryId: Long, trackerId: Long?) {
        val raw = preferredTrackerForCategory().get()
        val map = try {
            if (raw.isBlank()) {
                mutableMapOf<Long, Long>()
            } else {
                raw.split(";").associate {
                    val (k, v) = it.split(":", limit = 2)
                    k.toLong() to v.toLong()
                }.toMutableMap()
            }
        } catch (_: Exception) {
            mutableMapOf()
        }
        if (trackerId == null) map.remove(categoryId) else map[categoryId] = trackerId
        preferredTrackerForCategory().set(map.entries.joinToString(";") { "${it.key}:${it.value}" })
    }
    // KMK <--
}
