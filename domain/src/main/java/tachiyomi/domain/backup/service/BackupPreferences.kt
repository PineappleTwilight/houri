package tachiyomi.domain.backup.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class BackupPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun backupInterval() = preferenceStore.getInt("backup_interval", 12)

    fun lastAutoBackupTimestamp() = preferenceStore.getLong(Preference.appStateKey("last_auto_backup_timestamp"), 0L)

    // KMK -->
    fun showRestoringProgressBanner() = preferenceStore.getBoolean(
        Preference.appStateKey("pref_show_restoring_progress_banner_key"),
        true,
    )
    // KMK <--
}
