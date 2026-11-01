package mihon.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import mihon.core.navigation.domain.SettingsDestination
import tachiyomi.domain.manga.model.Manga

// Root / entry
@Serializable
data object HomeRoute : NavKey

// KMK --> Host pushed for a Library Search started from another screen (#55): renders the
// home tabs but carries its own back stack entry, so Back returns to the screen the search
// was started from instead of dropping out of Home.
@Serializable
data object LibrarySearchRoute : NavKey
// KMK <--

@Serializable
data class DeepLinkRoute(val query: String = "") : NavKey

@Serializable
data class WebViewRoute(
    val url: String,
    val initialTitle: String? = null,
    val sourceId: Long? = null,
) : NavKey, AssistContentRoute

@Serializable
data object OnboardingRoute : NavKey

@Serializable
data class NewUpdateRoute(
    val versionName: String,
    val changelogInfo: String,
    val releaseLink: String,
    val downloadLink: String,
) : NavKey

// KMK --> "What's coming" / roadmap / changelog screens
@Serializable
data class ComingUpdatesRoute(
    val versionName: String,
    val changelogInfo: String,
    val releaseLink: String,
    val downloadLink: String,
) : NavKey

@Serializable
data class WhatsNewRoute(
    val currentVersion: String,
    val versionName: String,
    val changelogInfo: String,
    val releaseLink: String,
) : NavKey

@Serializable
data object RoadmapRoute : NavKey
// KMK <--

// Entry
@Serializable
data class MangaNotesRoute(val manga: Manga) : NavKey

@Serializable
data class MangaRoute(
    val mangaId: Long,
    val fromSource: Boolean = false,
    // SY --> smart search ("find in another source") continuity
    val smartSearchOrigTitle: String? = null,
    val smartSearchOrigMangaId: Long? = null,
    // SY <--
) : NavKey, AssistContentRoute

@Serializable
data class TrackInfoDialogRoute(
    val mangaId: Long,
    val mangaTitle: String,
    val sourceId: Long,
) : NavKey

// KMK --> Smart scanlator preference (manga details)
@Serializable
data class ScanlatorPreferenceRoute(val mangaId: Long) : NavKey
// KMK <--

// KMK --> Theme palette editor
@Serializable
data class PaletteRoute(val seedColor: Int?) : NavKey
// KMK <--

// SY -->
@Serializable
data class MetadataViewRoute(
    val mangaId: Long,
    val sourceId: Long,
    // KMK -->
    val seedColor: Int? = null,
    // KMK <--
) : NavKey

@Serializable
data object BatchAddRoute : NavKey

@Serializable
data class MangaDexFollowsRoute(val sourceId: Long) : NavKey

@Serializable
data class PagePreviewRoute(val mangaId: Long) : NavKey
// SY <--

// Library tab
@Serializable
data class GlobalSearchRoute(
    val searchQuery: String = "",
    val extensionFilter: String? = null,
) : NavKey

// Updates tab
@Serializable
data object UpcomingRoute : NavKey

// Browse tab
@Serializable
data class BrowseSourceRoute(
    val sourceId: Long,
    val listingQuery: String?,
    // SY -->
    val filtersJson: String? = null,
    val savedSearch: Long? = null,
    val smartSearchOrigTitle: String? = null,
    val smartSearchOrigMangaId: Long? = null,
    // SY <--
) : NavKey, AssistContentRoute

// KMK --> Source feed ("new from this source")
@Serializable
data class SourceFeedRoute(val sourceId: Long) : NavKey
// KMK <--

@Serializable
data class ExtensionDetailsRoute(val pkgName: String) : NavKey

@Serializable
data object ExtensionFilterRoute : NavKey

@Serializable
data class MigrateMangaRoute(val sourceId: Long) : NavKey

@Serializable
data class MigrateSearchRoute(val mangaId: Long) : NavKey

@Serializable
data class MigrateSourceSearchRoute(
    val currentManga: Manga,
    val sourceId: Long,
    val query: String?,
) : NavKey

@Serializable
data class MigrationConfigRoute(val mangaIds: Collection<Long>) : NavKey {
    constructor(mangaId: Long) : this(listOf(mangaId))
}

@Serializable
data class MigrationListRoute(
    val mangaIds: Collection<Long>,
    val extraSearchQuery: String?,
) : NavKey

@Serializable
data object SourcesFilterRoute : NavKey

@Serializable
data class SourcePreferencesRoute(val sourceId: Long) : NavKey

@Serializable
data class ExtensionStoresRoute(val url: String? = null) : NavKey

// SY --> "Find in another source" smart search
@Serializable
data class SmartSearchRoute(
    val sourceId: Long,
    val origTitle: String,
    val origMangaId: Long? = null,
) : NavKey

@Serializable
data class SmartSearchSourcesRoute(
    val origTitle: String,
    val origMangaId: Long? = null,
) : NavKey
// SY <--

// More tab
@Serializable
data object CategoryRoute : NavKey

@Serializable
data object DownloadQueueRoute : NavKey

@Serializable
data class SettingsRoute(val settingsDestination: SettingsDestination? = null) : NavKey

@Serializable
data object StatsRoute : NavKey

// KMK -->
@Serializable
data object LibraryUpdateErrorRoute : NavKey

@Serializable
data object DiscordLoginRoute : NavKey
// KMK <--

// SY -->
@Serializable
data object SourceCategoryRoute : NavKey

@Serializable
data object SortTagRoute : NavKey

@Serializable
data object BiometricTimesRoute : NavKey

@Serializable
data object DuplicateMangaRoute : NavKey
// SY <--

// Settings
@Serializable
data object AboutRoute : NavKey

@Serializable
data object AppLanguageRoute : NavKey

@Serializable
data object BackupSchemaRoute : NavKey {
    const val TITLE = "Backup file schema"
}

@Serializable
data object ClearDatabaseRoute : NavKey

// KMK -->
@Serializable
data object DownloadedContentRoute : NavKey
// KMK <--

@Serializable
data object CreateBackupRoute : NavKey

@Serializable
data object DebugInfoRoute : NavKey

@Serializable
data object OpenSourceLicensesRoute : NavKey

@Serializable
data class RestoreBackupRoute(val uri: String) : NavKey

@Serializable
data object SettingsSearchRoute : NavKey

@Serializable
data object WorkerInfoRoute : NavKey {
    const val TITLE = "Worker info"
}
