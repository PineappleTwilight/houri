package tachiyomi.core.common.preference

// KMK -->
/**
 * Modular settings framework — store-level building blocks.
 *
 * A [SettingKey] is the single source of truth for one persisted setting: its exact
 * SharedPreferences key plus the default value. Feature `*Preferences` classes and UI
 * [hosts][SettingHost] both reference the same [SettingKey] instance, so keys can no
 * longer drift between the declaration site and the screen.
 *
 * Conventions:
 * - Resolved keys only — if the stored key uses a prefix helper, apply it when building
 *   the [SettingKey] (e.g. `SettingKey(Preference.privateKey("pref_example_url"), "")`).
 * - Never rename [key] without adding a migration in `mihon.core.migration.migrations`;
 *   backup files and restores match on the raw key string.
 * - Backup visibility is derived from the key itself via [SettingsRegistry.isBackedUp],
 *   using the same `__PRIVATE_` / `__APP_STATE_` rules as [PreferenceBackupCreator].
 */
data class SettingKey<T>(
    val key: String,
    val default: T,
)

/**
 * A feature module's contribution to the settings framework.
 *
 * Implementations (e.g. `WebhookSettingsHost`) list every persisted key they own so the
 * [SettingsRegistry] can assert global uniqueness at startup and tests can snapshot the
 * full key space for backup/restore coverage.
 */
interface SettingHost {
    val settingKeys: List<SettingKey<*>>
}

/**
 * Central registry of all [SettingHost] contributions.
 *
 * Registration is explicit and fail-fast: registering two hosts that own the same key
 * throws [IllegalArgumentException] immediately instead of silently shadowing a setting.
 * Hosts self-register from their `init` block so no DI or call-site wiring is needed:
 *
 * ```
 * object WebhookSettingsHost : SettingHost {
 *     override val settingKeys = WebhookSettingKeys.all
 *     init { SettingsRegistry.register(this) }
 * }
 * ```
 */
object SettingsRegistry {
    private val hosts = mutableListOf<SettingHost>()

    @Synchronized
    fun register(host: SettingHost) {
        val known = hosts.flatMap { it.settingKeys }.map { it.key }.toSet()
        val duplicates = host.settingKeys.map { it.key }.filter { it in known }
        require(duplicates.isEmpty()) {
            "Duplicate setting keys already registered: $duplicates"
        }
        hosts.add(host)
    }

    @Synchronized
    fun allKeys(): List<SettingKey<*>> = hosts.flatMap { it.settingKeys }

    @Synchronized
    fun isRegistered(key: String): Boolean = hosts.any { host -> host.settingKeys.any { it.key == key } }

    /**
     * Mirrors the backup filtering in `PreferenceBackupCreator`: internal app state is never
     * backed up, private keys only with user consent.
     */
    fun isBackedUp(key: String): Boolean = !Preference.isPrivate(key) && !Preference.isAppState(key)
}
// KMK <--
