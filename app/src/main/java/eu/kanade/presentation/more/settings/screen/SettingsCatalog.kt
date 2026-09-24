package eu.kanade.presentation.more.settings.screen

// KMK -->
/**
 * Single source of truth for the list of searchable settings screens.
 *
 * Previously this list was hardcoded in `SettingsSearchScreen` while the settings hub
 * (`SettingsMainScreen.items`) kept its own parallel list, so adding a screen meant
 * editing two files. Screen registration now happens here:
 *
 * - Full category entries (hub + search): add the screen here AND an `Item` in
 *   `SettingsMainScreen.items` (which additionally carries icon/subtitle UI metadata).
 * - Search-only coverage is automatic: `SettingsSearchScreen` indexes this list.
 *
 * Keep this list in sync with `SettingsMainScreen.items` — every hub screen that
 * implements [SearchableSettings] must appear here.
 */
object SettingsCatalog {
    val searchableScreens: List<SearchableSettings> = listOfNotNull(
        SettingsAppearanceScreen,
        SettingsLibraryScreen,
        SettingsReaderScreen,
        SettingsDownloadScreen,
        SettingsTrackingScreen,
        // AM (CONNECTIONS) -->
        SettingsConnectionScreen,
        // <-- AM (CONNECTIONS)
        SettingsBrowseScreen,
        SettingsDataScreen,
        SettingsSecurityScreen,
        // SY -->
        SettingsEhScreen,
        SettingsMangadexScreen,
        // SY <--
        // KMK --> Off-device MTL is available even on no-MTL builds.
        SettingsYakuyomiScreen,
        SettingsYakuyomiProviderScreen,
        SettingsYakuyomiModelsScreen,
        SettingsYakuyomiBehaviorScreen,
        SettingsYakuyomiPromptScreen,
        SettingsUpscalerScreen,
        // KMK <--
        SettingsAdvancedScreen,
    )
}
// KMK <--
