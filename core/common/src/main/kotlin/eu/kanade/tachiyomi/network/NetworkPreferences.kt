package eu.kanade.tachiyomi.network

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class NetworkPreferences(
    private val preferenceStore: PreferenceStore,
) {

    /* KMK --> fun verboseLogging(): Preference<Boolean> {
        return preferenceStore.getBoolean("verbose_logging", verboseLogging)
    } KMK <-- */

    fun dohProvider(): Preference<Int> {
        return preferenceStore.getInt("doh_provider", -1)
    }

    fun defaultUserAgent(): Preference<String> {
        return preferenceStore.getString(
            "default_user_agent",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36",
        )
    }

    // KMK --> FlareSolverr support, ported from jobobby04/TachiyomiSY#1124. Declared as plain
    // accessors rather than SettingKeys because this predates the modular settings framework in
    // this file; the keys are still the single definition both the screen and NetworkHelper read.
    val enableFlareSolverr: Preference<Boolean> = preferenceStore.getBoolean("enable_flare_solverr", false)

    val flareSolverrUrl: Preference<String> = preferenceStore.getString("flare_solverr_url", "http://localhost:8191/v1")

    val showCloudflareBypassBanner: Preference<Boolean> =
        preferenceStore.getBoolean("show_cloudflare_bypass_banner", true)
    // KMK <--
}
