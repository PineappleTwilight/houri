package eu.kanade.domain.source.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import eu.kanade.tachiyomi.util.system.LocaleHelper
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getLongArray
import tachiyomi.domain.library.model.LibraryDisplayMode

@SingleIn(AppScope::class)
@Inject
class SourcePreferences(
    private val preferenceStore: PreferenceStore,
    // KMK --> For extensionAutoUpdateSupported: whether a background update can finish unattended
    // depends on which installer is selected. Injected rather than reached for through the global
    // graph - it is the same preference store, so a preferences class has no reason to need a
    // singleton lookup.
    private val basePreferences: BasePreferences,
) {

    fun sourceDisplayMode() = preferenceStore.getObjectFromString(
        "pref_display_mode_catalogue",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    fun enabledLanguages() = preferenceStore.getStringSet("source_languages", LocaleHelper.getDefaultEnabledLanguages())

    fun disabledSources() = preferenceStore.getStringSet("hidden_catalogues", emptySet())

    fun incognitoExtensions() = preferenceStore.getStringSet("incognito_extensions", emptySet())

    fun pinnedSources() = preferenceStore.getStringSet(
        // KMK -->
        PINNED_SOURCES_PREF_KEY,
        // KMK <--
        emptySet(),
    )

    fun lastUsedSource() = preferenceStore.getLong(
        Preference.appStateKey("last_catalogue_source"),
        -1,
    )

    fun showNsfwSource() = preferenceStore.getBoolean("show_nsfw_source", true)

    fun migrationSortingMode() = preferenceStore.getEnum("pref_migration_sorting", SetMigrateSorting.Mode.ALPHABETICAL)

    fun migrationSortingDirection() = preferenceStore.getEnum(
        "pref_migration_direction",
        SetMigrateSorting.Direction.ASCENDING,
    )

    fun hideInLibraryItems() = preferenceStore.getBoolean("browse_hide_in_library_items", false)

    // KMK -->
    fun hideInLibraryFeedItems() = preferenceStore.getBoolean("feed_hide_in_library_items", false)
    // KMK <--

    @Deprecated("Use ExtensionRepoRepository instead", replaceWith = ReplaceWith("ExtensionRepoRepository.getAll()"))
    fun extensionRepos() = preferenceStore.getStringSet("extension_repos", emptySet())

    fun extensionUpdatesCount() = preferenceStore.getInt("ext_updates_count", 0)

    // KMK -->
    /**
     * Extension error prompts already shown, keyed `pkgName:versionCode:reason`. Without this a
     * runtime failure re-appears on every launch, because the failure itself persists.
     */
    fun extensionErrorPromptsShown() = preferenceStore.getStringSet(
        Preference.appStateKey("extension_error_prompts_shown"),
        emptySet(),
    )

    /** Keys are `pkgName:versionCode`, so a new version of the same extension prompts again. */
    fun extensionErrorPromptsSuppressed() = preferenceStore.getStringSet(
        Preference.appStateKey("extension_error_prompts_suppressed"),
        emptySet(),
    )

    /**
     * Whether the watchdog popup may be shown at all. On by default - a broken extension is a
     * silent failure otherwise, and nothing in the UI explains why a source returns nothing.
     *
     * Deliberately not an appState key: it is a user-facing setting that should survive a clear
     * and follow a backup, unlike the shown/suppressed sets above which are per-install bookkeeping.
     */
    fun extensionWatchdogEnabled() = preferenceStore.getBoolean("extension_watchdog_enabled", true)

    /**
     * Whether a failing extension shows its failure as text on the extensions page. On by default.
     *
     * Separate from [extensionWatchdogEnabled] because the two answer different questions: that one
     * is about being interrupted with a popup, this one is about the quiet label under the row.
     * Turning the watchdog off leaves the text in place, and turning this off leaves the popups
     * working. Switching it off hides the text only - failures are still recorded, so turning it
     * back on brings back what is currently true rather than starting from nothing.
     */
    fun extensionErrorTextEnabled() = preferenceStore.getBoolean("extension_error_text_enabled", true)

    /**
     * Whether installed extensions are updated without being asked.
     *
     * Off by default: an update swaps a scraper under the user, and a user who wants that can say
     * so. Only the installers that can install without a system dialog are eligible - see
     * [extensionAutoUpdateSupported] - so turning this on with the legacy installer selected does
     * nothing rather than failing.
     */
    fun extensionAutoUpdate() = preferenceStore.getBoolean("extension_auto_update", false)

    /**
     * How often the auto-update check runs, in hours.
     *
     * Two rather than one setting: the interval is only meaningful when auto-update is on, and a
     * stored interval that outlives the toggle is what the toggle being switched back on should
     * restore, not reset to a default.
     *
     * 6h is the default - long enough not to hammer the repo API on every app start, short enough
     * that a scraper fixed this morning is usually fixed by the evening.
     */
    fun extensionAutoUpdateInterval() = preferenceStore.getInt("extension_auto_update_interval", 6)

    /**
     * Whether [extensionAutoUpdate] can actually act with the installer currently selected.
     *
     * Only PRIVATE (installs the APK itself) and SHIZUKU (installs through the shell) can finish
     * an update with no user interaction. LEGACY and PACKAGEINSTALLER both raise a system
     * confirmation dialog, which a background worker cannot answer - and a worker that posted a
     * dialog would do it from the background, where the user may not be, and repeatedly.
     */
    fun extensionAutoUpdateSupported(): Boolean {
        val installer = try {
            basePreferences.extensionInstaller().get()
        } catch (_: Exception) {
            return false
        }
        return installer == BasePreferences.ExtensionInstaller.PRIVATE ||
            installer == BasePreferences.ExtensionInstaller.SHIZUKU
    }
    // KMK <--

    fun trustedExtensions() = preferenceStore.getStringSet(
        Preference.appStateKey("trusted_extensions"),
        emptySet(),
    )

    fun globalSearchFilterState() = preferenceStore.getBoolean(
        Preference.appStateKey("has_filters_toggle_state"),
        false,
    )

    fun migrationSources() = preferenceStore.getLongArray("migration_sources", emptyList())

    fun migrationFlags() = preferenceStore.getObjectFromInt(
        key = "migration_flags",
        defaultValue = MigrationFlag.entries.toSet(),
        serializer = { MigrationFlag.toBit(it) },
        deserializer = { value: Int -> MigrationFlag.fromBit(value) },
    )

    fun migrationDeepSearchMode() = preferenceStore.getBoolean("migration_deep_search", false)

    fun migrationPrioritizeByChapters() = preferenceStore.getBoolean("migration_prioritize_by_chapters", false)

    fun migrationHideUnmatched() = preferenceStore.getBoolean("migration_hide_unmatched", false)

    fun migrationHideWithoutUpdates() = preferenceStore.getBoolean("migration_hide_without_updates", false)

    // KMK -->
    fun migrationSmartSearchSingleEntry() = preferenceStore.getBoolean("migration_smart_search_single_entry", false)

    // Fetch each manual-search result's chapter list in the background so the latest chapter
    // number can be shown on the card. Adds one request per result; shown for results only,
    // and skipped for entries whose chapters are already known.
    fun migrationFetchChaptersForResults() = preferenceStore.getBoolean("migration_fetch_chapters_for_results", true)
    // KMK <--

    fun globalSearchPinnedState() = preferenceStore.getEnum(
        Preference.appStateKey("global_search_pinned_toggle_state"),
        SourceFilter.PinnedOnly,
    )

    fun globalSearchSourceCategory() = preferenceStore.getString(
        Preference.appStateKey("global_search_source_category"),
        "",
    )

    fun globalSearchExactAuthorMatch() = preferenceStore.getBoolean(
        Preference.appStateKey("global_search_exact_author_match"),
        false,
    )

    fun disabledRepos() = preferenceStore.getStringSet("disabled_repos", emptySet())
    // KMK <--

    // SY -->
    fun enableSourceBlacklist() = preferenceStore.getBoolean("eh_enable_source_blacklist", true)

    fun sourcesTabCategories() = preferenceStore.getStringSet("sources_tab_categories", mutableSetOf())

    fun sourcesTabCategoriesFilter() = preferenceStore.getBoolean("sources_tab_categories_filter", false)

    fun sourcesTabSourcesInCategories() = preferenceStore.getStringSet("sources_tab_source_categories", mutableSetOf())

    fun dataSaver() = preferenceStore.getEnum("data_saver", DataSaver.NONE)

    fun dataSaverIgnoreJpeg() = preferenceStore.getBoolean("ignore_jpeg", false)

    fun dataSaverIgnoreGif() = preferenceStore.getBoolean("ignore_gif", true)

    fun dataSaverImageQuality() = preferenceStore.getInt("data_saver_image_quality", 80)

    fun dataSaverImageFormatJpeg() = preferenceStore.getBoolean("data_saver_image_format_jpeg", false)

    fun dataSaverServer() = preferenceStore.getString("data_saver_server", "")

    fun dataSaverColorBW() = preferenceStore.getBoolean("data_saver_color_bw", false)

    fun dataSaverExcludedSources() = preferenceStore.getStringSet("data_saver_excluded", emptySet())

    fun dataSaverDownloader() = preferenceStore.getBoolean("data_saver_downloader", true)

    enum class DataSaver {
        NONE,
        BANDWIDTH_HERO,
        WSRV_NL,
    }

    fun allowLocalSourceHiddenFolders() = preferenceStore.getBoolean("allow_local_source_hidden_folders", false)

    fun preferredMangaDexId() = preferenceStore.getString("preferred_mangaDex_id", "0")

    fun mangadexSyncToLibraryIndexes() = preferenceStore.getStringSet(
        "pref_mangadex_sync_to_library_indexes",
        emptySet(),
    )

    fun recommendationSearchFlags() = preferenceStore.getInt("rec_search_flags", Int.MAX_VALUE)
    // SY <--

    // KMK -->
    fun relatedMangas() = preferenceStore.getBoolean("related_mangas", true)

    companion object {
        const val PINNED_SOURCES_PREF_KEY = "pinned_catalogues"
    }
    // KMK <--
}
