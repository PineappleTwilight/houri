package mihon.core.migration.migrations

import android.app.Application
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Migrates Suwayomi tracker from ID 62 (Houri-specific) to ID 9 (upstream parity).
 *
 * Copies preference keys from _62 suffix to _9 suffix so existing logins
 * are preserved. Also clears the stale old-ID keys.
 */
class MigrateSuwayomiTrackerIdMigration : Migration {
    override val version: Float = 82f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val context = migrationContext.get<Application>() ?: return@withIOContext false
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val oldId = 62L
        val newId = 9L

        val prefPrefixes = listOf(
            "pref_mangasync_username_",
            "pref_mangasync_password_",
            "pref_tracker_auth_expired_",
            "track_token_",
        )

        // Read all values first, then write in a single edit transaction
        val migrations = prefPrefixes.mapNotNull { prefix ->
            val oldKey = "${prefix}$oldId"
            val newKey = "${prefix}$newId"
            val oldValue = prefs.getString(oldKey, null)
            if (oldValue != null) Triple(oldKey, newKey, oldValue) else null
        }

        if (migrations.isNotEmpty()) {
            prefs.edit {
                for ((oldKey, newKey, value) in migrations) {
                    putString(newKey, value)
                    remove(oldKey)
                }
            }
        }

        return@withIOContext true
    }
}
