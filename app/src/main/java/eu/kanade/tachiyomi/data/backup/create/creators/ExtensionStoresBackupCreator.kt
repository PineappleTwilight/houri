package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.backupExtensionStoreMapper
import mihon.app.di.globalAppGraph
import mihon.domain.extension.interactor.GetExtensionStores

class ExtensionStoresBackupCreator(
    private val getExtensionStores: GetExtensionStores = globalAppGraph.getExtensionStores,
) {

    suspend operator fun invoke(): List<BackupExtensionStore> {
        return getExtensionStores.get()
            .map(backupExtensionStoreMapper)
    }
}
