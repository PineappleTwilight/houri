package mihon.core.migration.migrations

import android.app.Application
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import mihon.app.di.globalAppGraph
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext

class SetupSyncDataMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        // KMK -->
        val syncPreferences = globalAppGraph.syncPreferences
        val syncEnabled = syncPreferences.isSyncEnabled()
        if (syncEnabled) {
            // KMK <--
            SyncDataJob.setupTask(context)
        }
        return true
    }
}
