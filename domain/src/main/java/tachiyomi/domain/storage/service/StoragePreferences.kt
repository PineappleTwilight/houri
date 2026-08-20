package tachiyomi.domain.storage.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.FolderProvider

@SingleIn(AppScope::class)
@Inject
class StoragePreferences(
    private val folderProvider: FolderProvider,
    private val preferenceStore: PreferenceStore,
) {

    // Storing URI of the directory (either file:/// or storage://
    fun baseStorageDirectory() = preferenceStore.getString(Preference.appStateKey("storage_dir"), folderProvider.path())
}
