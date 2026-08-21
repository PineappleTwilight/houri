package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.backupSavedSearchMapper
import mihon.app.di.globalAppGraph
import tachiyomi.data.DatabaseHandler

class SavedSearchBackupCreator(
    private val handler: DatabaseHandler = globalAppGraph.databaseHandler,
) {

    suspend operator fun invoke(): List<BackupSavedSearch> {
        return handler.awaitList { saved_searchQueries.selectAll(backupSavedSearchMapper) }
    }
}
