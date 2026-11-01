package mihon.core.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.more.settings.screen.SearchableRoute
import eu.kanade.presentation.more.settings.screen.SettingsAchievementsRoute
import eu.kanade.presentation.more.settings.screen.SettingsAdvancedRoute
import eu.kanade.presentation.more.settings.screen.SettingsAppearanceRoute
import eu.kanade.presentation.more.settings.screen.SettingsBookReadingRoute
import eu.kanade.presentation.more.settings.screen.SettingsBrowseRoute
import eu.kanade.presentation.more.settings.screen.SettingsConnectionRoute
import eu.kanade.presentation.more.settings.screen.SettingsDataRoute
import eu.kanade.presentation.more.settings.screen.SettingsDiscordRoute
import eu.kanade.presentation.more.settings.screen.SettingsDownloadRoute
import eu.kanade.presentation.more.settings.screen.SettingsEhRoute
import eu.kanade.presentation.more.settings.screen.SettingsLibraryRoute
import eu.kanade.presentation.more.settings.screen.SettingsMangadexRoute
import eu.kanade.presentation.more.settings.screen.SettingsReaderRoute
import eu.kanade.presentation.more.settings.screen.SettingsSearchScreen
import eu.kanade.presentation.more.settings.screen.SettingsSecurityRoute
import eu.kanade.presentation.more.settings.screen.SettingsTrackingRoute
import eu.kanade.presentation.more.settings.screen.SettingsUpscalerRoute
import eu.kanade.presentation.more.settings.screen.SettingsWebhookRoute
import eu.kanade.presentation.more.settings.screen.SettingsYakuyomiBehaviorRoute
import eu.kanade.presentation.more.settings.screen.SettingsYakuyomiEngineAdvancedRoute
import eu.kanade.presentation.more.settings.screen.SettingsYakuyomiLlmAdvancedRoute
import eu.kanade.presentation.more.settings.screen.SettingsYakuyomiModelsRoute
import eu.kanade.presentation.more.settings.screen.SettingsYakuyomiPromptRoute
import eu.kanade.presentation.more.settings.screen.SettingsYakuyomiProviderRoute
import eu.kanade.presentation.more.settings.screen.SettingsYakuyomiRoute
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.more.settings.screen.about.OpenSourceLibraryLicenseRoute
import eu.kanade.presentation.more.settings.screen.about.OpenSourceLibraryLicenseScreen
import eu.kanade.presentation.more.settings.screen.about.OpenSourceLicensesScreen
import eu.kanade.presentation.more.settings.screen.advanced.ClearDatabaseScreen
import eu.kanade.presentation.more.settings.screen.appearance.AppCustomThemeColorPickerRoute
import eu.kanade.presentation.more.settings.screen.appearance.AppCustomThemeColorPickerScreen
import eu.kanade.presentation.more.settings.screen.appearance.AppLanguageScreen
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.more.settings.screen.data.SyncSettingsSelector
import eu.kanade.presentation.more.settings.screen.data.SyncSettingsSelectorRoute
import eu.kanade.presentation.more.settings.screen.data.SyncTriggerOptionsRoute
import eu.kanade.presentation.more.settings.screen.data.SyncTriggerOptionsScreen
import eu.kanade.presentation.more.settings.screen.debug.BackupSchemaScreen
import eu.kanade.presentation.more.settings.screen.debug.DebugInfoScreen
import eu.kanade.presentation.more.settings.screen.debug.WorkerInfoScreen
import eu.kanade.presentation.more.settings.screen.downloaded.DownloadedContentScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsRoute
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.category.biometric.BiometricTimesScreen
import eu.kanade.tachiyomi.ui.category.genre.SortTagScreen
import eu.kanade.tachiyomi.ui.category.sources.SourceCategoryScreen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.duplicates.DuplicateMangaScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.libraryUpdateError.LibraryUpdateErrorScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.manga.PaletteScreen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.ui.manga.scanlator.ScanlatorPreferenceScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackInfoDialog
import eu.kanade.tachiyomi.ui.more.ComingUpdatesScreen
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.ui.more.RoadmapScreen
import eu.kanade.tachiyomi.ui.more.WhatsNewScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.setting.connections.DiscordLoginScreen
import eu.kanade.tachiyomi.ui.stats.StatsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import exh.debug.SettingsDebugRoute
import exh.pagepreview.PagePreviewScreen
import exh.recs.MergedRecommendsRoute
import exh.recs.MergedRecommendsScreen
import exh.recs.RecommendsRoute
import exh.recs.RecommendsScreen
import exh.ui.batchadd.BatchAddScreen
import exh.ui.metadata.MetadataViewScreen
import mihon.core.navigation.util.LocalBackStack
import mihon.core.navigation.util.OverlaySheetScene
import mihon.core.navigation.util.TwoPaneSettingsScene
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.feature.migration.list.MigrationListScreen
import mihon.feature.upcoming.UpcomingScreen

fun EntryProviderScope<NavKey>.appEntries() {
    entry<HomeRoute> {
        HomeScreen()
    }
    // KMK --> Host for a Library Search started from another screen (#55)
    entry<LibrarySearchRoute> {
        HomeScreen()
    }
    // KMK <--
    entry<DeepLinkRoute> { route ->
        DeepLinkScreen(route.query)
    }

    entry<WebViewRoute> { route ->
        WebViewScreen(
            url = route.url,
            initialTitle = route.initialTitle,
            sourceId = route.sourceId,
        )
    }

    entry<OnboardingRoute> {
        OnboardingScreen()
    }

    entry<NewUpdateRoute> { route ->
        NewUpdateScreen(
            versionName = route.versionName,
            changelogInfo = route.changelogInfo,
            releaseLink = route.releaseLink,
            downloadLink = route.downloadLink,
        )
    }
    // KMK --> "What's coming" / roadmap / changelog screens
    entry<ComingUpdatesRoute> { route ->
        ComingUpdatesScreen(
            versionName = route.versionName,
            changelogInfo = route.changelogInfo,
            releaseLink = route.releaseLink,
            downloadLink = route.downloadLink,
        )
    }
    entry<WhatsNewRoute> { route ->
        WhatsNewScreen(
            currentVersion = route.currentVersion,
            versionName = route.versionName,
            changelogInfo = route.changelogInfo,
            releaseLink = route.releaseLink,
        )
    }
    entry<RoadmapRoute> {
        RoadmapScreen()
    }
    // KMK <--

    // Entry
    entry<MangaNotesRoute> { route ->
        MangaNotesScreen(route.manga)
    }

    entry<MangaRoute> { route ->
        MangaScreen(
            mangaId = route.mangaId,
            fromSource = route.fromSource,
            smartSearchOrigTitle = route.smartSearchOrigTitle,
            smartSearchOrigMangaId = route.smartSearchOrigMangaId,
        )
    }

    entry<TrackInfoDialogRoute>(metadata = OverlaySheetScene.overlaySheet()) { route ->
        val backStack = LocalBackStack.current
        TrackInfoDialog(
            mangaId = route.mangaId,
            mangaTitle = route.mangaTitle,
            sourceId = route.sourceId,
            onDismissRequest = { backStack.removeLastOrNull() },
        )
    }

    // KMK --> Smart scanlator preference (manga details)
    entry<ScanlatorPreferenceRoute> { route ->
        ScanlatorPreferenceScreen(route.mangaId)
    }
    // KMK <--

    // KMK --> Theme palette editor
    entry<PaletteRoute> { route ->
        PaletteScreen(route.seedColor)
    }
    // KMK <--

    // SY -->
    entry<MetadataViewRoute> { route ->
        MetadataViewScreen(
            mangaId = route.mangaId,
            sourceId = route.sourceId,
            seedColor = route.seedColor,
        )
    }

    entry<BatchAddRoute> {
        BatchAddScreen()
    }

    entry<MangaDexFollowsRoute> { route ->
        exh.md.follows.MangaDexFollowsScreen(route.sourceId)
    }

    entry<PagePreviewRoute> { route ->
        PagePreviewScreen(route.mangaId)
    }
    // SY <--

    // Library tab
    entry<GlobalSearchRoute> { route ->
        eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen(
            searchQuery = route.searchQuery,
            extensionFilter = route.extensionFilter,
        )
    }

    // Updates tab
    entry<UpcomingRoute> {
        UpcomingScreen()
    }

    // Browse tab
    entry<BrowseSourceRoute> { route ->
        eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen(
            sourceId = route.sourceId,
            listingQuery = route.listingQuery,
            filtersJson = route.filtersJson,
            savedSearch = route.savedSearch,
            smartSearchOrigTitle = route.smartSearchOrigTitle,
            smartSearchOrigMangaId = route.smartSearchOrigMangaId,
        )
    }

    // KMK --> Source feed ("new from this source")
    entry<SourceFeedRoute> { route ->
        eu.kanade.tachiyomi.ui.browse.source.feed.SourceFeedScreen(route.sourceId)
    }
    // KMK <--

    entry<ExtensionDetailsRoute> { route ->
        eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen(route.pkgName)
    }

    entry<ExtensionFilterRoute> {
        eu.kanade.tachiyomi.ui.browse.extension.ExtensionFilterScreen()
    }

    entry<ExtensionsRoute> { route ->
        ExtensionsScreen(route.searchSource)
    }

    entry<MigrateMangaRoute> { route ->
        eu.kanade.tachiyomi.ui.browse.migration.manga.MigrateMangaScreen(route.sourceId)
    }

    entry<MigrateSearchRoute> { route ->
        eu.kanade.tachiyomi.ui.browse.migration.search.MigrateSearchScreen(route.mangaId)
    }

    entry<MigrateSourceSearchRoute> { route ->
        eu.kanade.tachiyomi.ui.browse.migration.search.MigrateSourceSearchScreen(
            currentManga = route.currentManga,
            sourceId = route.sourceId,
            query = route.query,
        )
    }

    entry<MigrationConfigRoute> { route ->
        MigrationConfigScreen(route.mangaIds)
    }

    entry<MigrationListRoute> { route ->
        MigrationListScreen(route.mangaIds, route.extraSearchQuery)
    }

    entry<SourcesFilterRoute> {
        eu.kanade.tachiyomi.ui.browse.source.SourcesFilterScreen()
    }

    entry<SourcePreferencesRoute> { route ->
        eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen(route.sourceId)
    }

    entry<ExtensionStoresRoute> { route ->
        eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen(route.url)
    }

    // SY --> "Find in another source" smart search
    entry<SmartSearchRoute> { route ->
        exh.ui.smartsearch.SmartSearchScreen(
            sourceId = route.sourceId,
            origTitle = route.origTitle,
            origMangaId = route.origMangaId,
        )
    }

    entry<SmartSearchSourcesRoute> { route ->
        eu.kanade.tachiyomi.ui.browse.source.SourcesSearchScreen(
            smartSearchConfig = eu.kanade.tachiyomi.ui.browse.source.SmartSearchConfig(
                origTitle = route.origTitle,
                origMangaId = route.origMangaId,
            ),
        )
    }
    // SY <--

    // exh recs
    entry<RecommendsRoute> { route ->
        RecommendsScreen(
            mangaId = route.mangaId,
            sourceId = route.sourceId,
        )
    }
    entry<MergedRecommendsRoute> { route ->
        MergedRecommendsScreen(
            mergedResults = route.results,
        )
    }
    entry<exh.recs.BrowseRecommendsRoute> { route ->
        exh.recs.BrowseRecommendsScreen(
            mangaId = route.mangaId,
            sourceId = route.sourceId,
            recommendationSourceName = route.recommendationSourceName,
            isExternalSource = route.isExternalSource,
        )
    }
    entry<exh.recs.BrowseMergedRecommendsRoute> { route ->
        exh.recs.BrowseMergedRecommendsScreen(
            results = route.results,
            isExternalSource = route.isExternalSource,
        )
    }

    // More tab
    entry<CategoryRoute> {
        CategoryScreen()
    }

    entry<DownloadQueueRoute> {
        DownloadQueueScreen()
    }

    entry<SettingsRoute>(metadata = TwoPaneSettingsScene.listPane()) {
        SettingsScreen()
    }

    entry<StatsRoute> {
        StatsScreen()
    }

    // KMK -->
    entry<LibraryUpdateErrorRoute> {
        LibraryUpdateErrorScreen()
    }

    entry<DiscordLoginRoute> {
        DiscordLoginScreen()
    }
    // KMK <--

    // SY -->
    entry<SourceCategoryRoute> {
        SourceCategoryScreen()
    }

    entry<SortTagRoute> {
        SortTagScreen()
    }

    entry<BiometricTimesRoute> {
        BiometricTimesScreen()
    }

    entry<DuplicateMangaRoute> {
        DuplicateMangaScreen()
    }
    // SY <--

    // Settings
    entry<AboutRoute> {
        AboutScreen()
    }

    entry<AppLanguageRoute> {
        AppLanguageScreen()
    }

    entry<BackupSchemaRoute> {
        BackupSchemaScreen()
    }

    entry<ClearDatabaseRoute> {
        ClearDatabaseScreen()
    }

    // KMK -->
    entry<DownloadedContentRoute> {
        DownloadedContentScreen()
    }
    // KMK <--

    entry<CreateBackupRoute> {
        CreateBackupScreen()
    }

    entry<DebugInfoRoute> {
        DebugInfoScreen()
    }

    entry<OpenSourceLicensesRoute> {
        OpenSourceLicensesScreen()
    }

    // KMK --> Per-library license screen
    entry<OpenSourceLibraryLicenseRoute> { route ->
        OpenSourceLibraryLicenseScreen(
            name = route.name,
            website = route.website,
            license = route.license,
        )
    }

    entry<AppCustomThemeColorPickerRoute> {
        AppCustomThemeColorPickerScreen()
    }

    entry<SyncSettingsSelectorRoute> {
        SyncSettingsSelector()
    }

    entry<SyncTriggerOptionsRoute> {
        SyncTriggerOptionsScreen()
    }
    // KMK <--

    entry<RestoreBackupRoute> { route ->
        RestoreBackupScreen(route.uri)
    }

    entry<SettingsSearchRoute> {
        SettingsSearchScreen()
    }

    entry<WorkerInfoRoute> {
        WorkerInfoScreen()
    }

    // KMK --> Discord accounts (AM (CONNECTIONS))
    entry<eu.kanade.presentation.more.settings.screen.DiscordAccountsRoute> {
        eu.kanade.presentation.more.settings.screen.DiscordAccountsScreen()
    }
    // KMK <--

    // KMK --> MTL provider sub-screens
    entry<eu.kanade.presentation.more.settings.screen.SettingsMangaTranslatorAuthRoute> {
        eu.kanade.presentation.more.settings.screen.SettingsMangaTranslatorAuthScreen()
    }
    entry<eu.kanade.presentation.more.settings.screen.SettingsMangaTranslatorCacheRoute> {
        eu.kanade.presentation.more.settings.screen.SettingsMangaTranslatorCacheScreen()
    }
    entry<eu.kanade.presentation.more.settings.screen.SettingsTranslationSessionsRoute> {
        eu.kanade.presentation.more.settings.screen.SettingsTranslationSessionsScreen()
    }
    // KMK <--

    // Searchable settings
    entry<SettingsAppearanceRoute> {
        SettingsAppearanceRoute.Content()
    }
    entry<SettingsLibraryRoute> {
        SettingsLibraryRoute.Content()
    }
    entry<SettingsReaderRoute> {
        SettingsReaderRoute.Content()
    }
    entry<SettingsBookReadingRoute> {
        SettingsBookReadingRoute.Content()
    }
    entry<SettingsDownloadRoute> {
        SettingsDownloadRoute.Content()
    }
    entry<SettingsTrackingRoute> {
        SettingsTrackingRoute.Content()
    }
    entry<SettingsConnectionRoute> {
        SettingsConnectionRoute.Content()
    }
    entry<SettingsBrowseRoute> {
        SettingsBrowseRoute.Content()
    }
    entry<SettingsDataRoute> {
        SettingsDataRoute.Content()
    }
    entry<SettingsSecurityRoute> {
        SettingsSecurityRoute.Content()
    }
    entry<SettingsEhRoute> {
        SettingsEhRoute.Content()
    }
    entry<SettingsMangadexRoute> {
        SettingsMangadexRoute.Content()
    }
    entry<SettingsYakuyomiRoute> {
        SettingsYakuyomiRoute.Content()
    }
    entry<SettingsYakuyomiProviderRoute> {
        SettingsYakuyomiProviderRoute.Content()
    }
    entry<SettingsYakuyomiModelsRoute> {
        SettingsYakuyomiModelsRoute.Content()
    }
    entry<SettingsYakuyomiBehaviorRoute> {
        SettingsYakuyomiBehaviorRoute.Content()
    }
    entry<SettingsYakuyomiPromptRoute> {
        SettingsYakuyomiPromptRoute.Content()
    }
    entry<SettingsYakuyomiEngineAdvancedRoute> {
        SettingsYakuyomiEngineAdvancedRoute.Content()
    }
    entry<SettingsYakuyomiLlmAdvancedRoute> { route ->
        route.Content()
    }
    entry<SettingsUpscalerRoute> {
        SettingsUpscalerRoute.Content()
    }
    entry<SettingsWebhookRoute> {
        SettingsWebhookRoute.Content()
    }
    entry<SettingsAchievementsRoute> {
        SettingsAchievementsRoute.Content()
    }
    entry<SettingsAdvancedRoute> {
        SettingsAdvancedRoute.Content()
    }
    // KMK --> exh debug menu
    entry<SettingsDebugRoute> {
        exh.debug.SettingsDebugScreen()
    }
    // KMK <--
}
