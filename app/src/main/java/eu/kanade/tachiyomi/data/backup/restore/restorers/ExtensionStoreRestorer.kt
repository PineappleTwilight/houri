package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import mihon.app.di.globalAppGraph
import tachiyomi.data.Database

class ExtensionStoreRestorer(
    private val database: Database = globalAppGraph.database,
) {

    suspend operator fun invoke(
        backupStore: BackupExtensionStore,
    ) {
        // KMK -->
        val indexUrl = if (backupStore.isLegacy == null) {
            backupStore.indexUrl.removeSuffix("/index.min.json").removeSuffix("/index.json")
                .removeSuffix("/repo.json") + "/repo.json"
        } else {
            backupStore.indexUrl
        }
        // KMK <--
        database.extension_storeQueries.upsert(
            indexUrl = indexUrl,
            name = backupStore.name,
            badgeLabel = backupStore.badgeLabel ?: backupStore.name,
            signingKey = backupStore.signingKey,
            contactWebsite = backupStore.contactWebsite,
            contactDiscord = backupStore.contactDiscord,
            isLegacy = backupStore.isLegacy ?: true,
            extensionListUrl = backupStore.extensionListUrl,
        )
    }
}
