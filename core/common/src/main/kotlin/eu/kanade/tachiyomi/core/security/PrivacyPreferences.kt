package eu.kanade.tachiyomi.core.security

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class PrivacyPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun crashlytics() = preferenceStore.getBoolean("crashlytics", true)

    fun analytics() = preferenceStore.getBoolean("analytics", true)
}
