package mihon.app.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.connections.service.WebhookPreferences
import eu.kanade.domain.extension.interactor.GetExtensionLanguages
import eu.kanade.domain.extension.interactor.GetExtensionSources
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.manga.interactor.CompleteRereadIfNeeded
import eu.kanade.domain.manga.interactor.CreateSortTag
import eu.kanade.domain.manga.interactor.DeleteSortTag
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.GetPagePreviews
import eu.kanade.domain.manga.interactor.GetSortTag
import eu.kanade.domain.manga.interactor.MergeMangaBySmartSearch
import eu.kanade.domain.manga.interactor.ReorderSortTag
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.StartRereading
import eu.kanade.domain.manga.interactor.StopRereading
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.CreateSourceCategory
import eu.kanade.domain.source.interactor.DeleteSourceCategory
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.interactor.GetExhSavedSearch
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.interactor.GetLanguagesWithSources
import eu.kanade.domain.source.interactor.GetShowLatest
import eu.kanade.domain.source.interactor.GetSourceCategories
import eu.kanade.domain.source.interactor.GetSourcesWithFavoriteCount
import eu.kanade.domain.source.interactor.RenameSourceCategory
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.domain.source.interactor.SetSourceCategories
import eu.kanade.domain.source.interactor.ToggleExcludeFromDataSaver
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.interactor.ToggleLanguage
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.BackupRestoreStatus
import eu.kanade.tachiyomi.data.LibraryUpdateStatus
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.library.MetadataUpdateJob
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.webhook.WebhookNotifier
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.util.ExtensionInstallActivity
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegateImpl
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.setting.track.BaseOAuthLoginActivity
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.CrashLogUtil
import exh.eh.EHentaiUpdateHelper
import exh.pref.DelegateSourcePreferences
import exh.search.SearchEngine
import exh.source.ExhPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.RemoveExtensionStore
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.repository.ExtensionStoreRepository
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.upcoming.interactor.GetUpcomingManga
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.HideCategory
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.interactor.UpdateCategory
import tachiyomi.domain.chapter.interactor.DeleteChapters
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChapterByUrl
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.RemoveResettedHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.libraryUpdateError.interactor.DeleteLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.interactor.GetLibraryUpdateErrorWithRelations
import tachiyomi.domain.libraryUpdateError.interactor.InsertLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.GetLibraryUpdateErrorMessages
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.InsertLibraryUpdateErrorMessages
import tachiyomi.domain.manga.interactor.DeleteFavoriteEntries
import tachiyomi.domain.manga.interactor.DeleteMergeById
import tachiyomi.domain.manga.interactor.DeleteNonLibraryManga
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFavoriteEntries
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetIdsOfFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetMergedMangaForDownloading
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.GetReadMangaNotInLibraryView
import tachiyomi.domain.manga.interactor.GetSearchMetadata
import tachiyomi.domain.manga.interactor.GetSearchTags
import tachiyomi.domain.manga.interactor.GetSearchTitles
import tachiyomi.domain.manga.interactor.InsertFavoriteEntries
import tachiyomi.domain.manga.interactor.InsertFavoriteEntryAlternative
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.interactor.UpdateMergedSettings
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.recommendation.interactor.DeleteCachedRecommendations
import tachiyomi.domain.recommendation.interactor.GetCachedRecommendations
import tachiyomi.domain.recommendation.interactor.InsertCachedRecommendations
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.source.interactor.CountFeedSavedSearchBySourceId
import tachiyomi.domain.source.interactor.CountFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.DeleteFeedSavedSearchById
import tachiyomi.domain.source.interactor.DeleteSavedSearchById
import tachiyomi.domain.source.interactor.GetFeedSavedSearchBySourceId
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceId
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceIdFeed
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.interactor.GetSourcesWithNonLibraryManga
import tachiyomi.domain.source.interactor.InsertFeedSavedSearch
import tachiyomi.domain.source.interactor.InsertSavedSearch
import tachiyomi.domain.source.interactor.ReorderFeed
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem

@DependencyGraph(
    scope = AppScope::class,
    bindingContainers = [AppBindings::class],
)
interface AppGraph : ViewModelGraph {
    fun inject(app: App)
    fun inject(mainActivity: MainActivity)
    fun inject(readerActivity: ReaderActivity)
    fun inject(webViewActivity: WebViewActivity)
    fun inject(baseOAuthLoginActivity: BaseOAuthLoginActivity)
    fun inject(libraryUpdateJob: LibraryUpdateJob)
    fun inject(metadataUpdateJob: MetadataUpdateJob)
    fun inject(backupRestoreJob: BackupRestoreJob)
    fun inject(backupCreateJob: BackupCreateJob)
    fun inject(notificationReceiver: NotificationReceiver)
    fun inject(secureActivityDelegateImpl: SecureActivityDelegateImpl)
    fun inject(extensionInstallActivity: ExtensionInstallActivity)

    val context: Context

    val viewModelFactory: MetroViewModelFactory

    // Preferences
    val basePreferences: BasePreferences
    val uiPreferences: UiPreferences
    val readerPreferences: ReaderPreferences
    val networkPreferences: NetworkPreferences
    val libraryPreferences: LibraryPreferences
    val sourcePreferences: SourcePreferences
    val trackPreferences: TrackPreferences
    val backupPreferences: BackupPreferences
    val storagePreferences: StoragePreferences
    val privacyPreferences: PrivacyPreferences
    val securityPreferences: SecurityPreferences
    val downloadPreferences: DownloadPreferences
    val updatesPreferences: UpdatesPreferences

    // Infrastructure
    val crashLogUtil: CrashLogUtil
    val downloadManager: DownloadManager
    val updateChecker: AppUpdateChecker
    val sourceManager: SourceManager
    val trackerManager: TrackerManager
    val extensionManager: ExtensionManager
    val chapterCache: ChapterCache
    val downloadCache: DownloadCache
    val networkHelper: NetworkHelper
    val json: Json
    val storageManager: StorageManager
    val imageSaver: ImageSaver
    val coverCache: CoverCache
    val connectionsManager: ConnectionsManager
    val delayedTrackingStore: DelayedTrackingStore

    // KMK -->
    val googleDriveService: GoogleDriveService
    val webhookPreferences: WebhookPreferences
    val webhookNotifier: WebhookNotifier
    // KMK <--

    // KMK -->
    // Accessors added while migrating remaining call sites off Injekt
    val protoBuf: ProtoBuf
    val databaseHandler: DatabaseHandler
    val database: Database
    val mangaRepository: MangaRepository
    val preferenceStore: PreferenceStore
    val backupRestoreStatus: BackupRestoreStatus
    // KMK <--

    // Domain
    val getFavorites: GetFavorites
    val getCategories: GetCategories
    val resetViewerFlags: ResetViewerFlags
    val resetCategoryFlags: ResetCategoryFlags
    val addTracks: AddTracks
    val insertTrack: InsertTrack
    val trackChapter: TrackChapter
    val refreshTracks: RefreshTracks
    val getRemoteManga: GetRemoteManga
    val fetchInterval: FetchInterval
    val networkToLocalManga: NetworkToLocalManga
    val updateMangaFromRemote: UpdateMangaFromRemote
    val getExtensionStoreCountAsFlow: GetExtensionStoreCountAsFlow

    // KMK -->
    // Accessors added while migrating remaining call sites off Injekt
    val getChaptersByMangaId: GetChaptersByMangaId
    val getHistory: GetHistory
    val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId
    val getTracks: GetTracks
    val getCustomMangaInfo: GetCustomMangaInfo
    val setCustomMangaInfo: SetCustomMangaInfo
    val insertFlatMetadata: InsertFlatMetadata
    val getFlatMetadataById: GetFlatMetadataById
    val getExtensionStores: GetExtensionStores
    val getMergedManga: GetMergedManga
    val updateManga: UpdateManga
    val addExtensionStore: AddExtensionStore
    val connectionsPreferences: ConnectionsPreferences
    val countFeedSavedSearchBySourceId: CountFeedSavedSearchBySourceId
    val countFeedSavedSearchGlobal: CountFeedSavedSearchGlobal
    val createCategoryWithName: CreateCategoryWithName
    val createSortTag: CreateSortTag
    val createSourceCategory: CreateSourceCategory
    val delegateSourcePreferences: DelegateSourcePreferences
    val deleteCategory: DeleteCategory
    val deleteChapters: DeleteChapters
    val deleteFavoriteEntries: DeleteFavoriteEntries
    val deleteFeedSavedSearchById: DeleteFeedSavedSearchById
    val deleteLibraryUpdateErrors: DeleteLibraryUpdateErrors
    val deleteMergeById: DeleteMergeById
    val deleteNonLibraryManga: DeleteNonLibraryManga
    val deleteSavedSearchById: DeleteSavedSearchById
    val deleteSortTag: DeleteSortTag
    val deleteSourceCategory: DeleteSourceCategory
    val deleteTrack: DeleteTrack
    val downloadProvider: DownloadProvider
    val exhPreferences: ExhPreferences
    val filterChaptersForDownload: FilterChaptersForDownload
    val getAllManga: GetAllManga
    val getAvailableScanlators: GetAvailableScanlators
    val getBookmarkedChaptersByMangaId: GetBookmarkedChaptersByMangaId
    val getChapter: GetChapter
    val getChapterByUrlAndMangaId: GetChapterByUrlAndMangaId
    val getDuplicateLibraryManga: GetDuplicateLibraryManga
    val getEnabledSources: GetEnabledSources
    val getExcludedScanlators: GetExcludedScanlators
    val getExhSavedSearch: GetExhSavedSearch
    val getExtensionLanguages: GetExtensionLanguages
    val getExtensionsByType: GetExtensionsByType
    val getExtensionSources: GetExtensionSources
    val getFavoriteEntries: GetFavoriteEntries
    val getFeedSavedSearchBySourceId: GetFeedSavedSearchBySourceId
    val getFeedSavedSearchGlobal: GetFeedSavedSearchGlobal
    val getIdsOfFavoriteMangaWithMetadata: GetIdsOfFavoriteMangaWithMetadata
    val getIncognitoState: GetIncognitoState
    val getLanguagesWithSources: GetLanguagesWithSources
    val getLibraryManga: GetLibraryManga
    val getLibraryUpdateErrorMessages: GetLibraryUpdateErrorMessages
    val getLibraryUpdateErrorWithRelations: GetLibraryUpdateErrorWithRelations
    val getManga: GetManga
    val getMangaWithChapters: GetMangaWithChapters
    val getMergedChaptersByMangaId: GetMergedChaptersByMangaId
    val getMergedMangaById: GetMergedMangaById
    val getMergedMangaForDownloading: GetMergedMangaForDownloading
    val getMergedReferencesById: GetMergedReferencesById
    val getNextChapters: GetNextChapters
    val getPagePreviews: GetPagePreviews
    val getReadMangaNotInLibraryView: GetReadMangaNotInLibraryView
    val getSavedSearchBySourceId: GetSavedSearchBySourceId
    val getSavedSearchBySourceIdFeed: GetSavedSearchBySourceIdFeed
    val getSavedSearchGlobalFeed: GetSavedSearchGlobalFeed
    val getSearchTags: GetSearchTags
    val getSearchTitles: GetSearchTitles
    val getShowLatest: GetShowLatest
    val getSortTag: GetSortTag
    val getSourceCategories: GetSourceCategories
    val getSourcesWithFavoriteCount: GetSourcesWithFavoriteCount
    val getSourcesWithNonLibraryManga: GetSourcesWithNonLibraryManga
    val getTotalReadDuration: GetTotalReadDuration
    val getTracksPerManga: GetTracksPerManga
    val getUpcomingManga: GetUpcomingManga
    val getUpdates: GetUpdates
    val hideCategory: HideCategory
    val historyPreferences: HistoryPreferences
    val insertFavoriteEntries: InsertFavoriteEntries
    val insertFeedSavedSearch: InsertFeedSavedSearch
    val insertLibraryUpdateErrorMessages: InsertLibraryUpdateErrorMessages
    val insertLibraryUpdateErrors: InsertLibraryUpdateErrors
    val insertSavedSearch: InsertSavedSearch
    val libraryUpdateStatus: LibraryUpdateStatus
    val mergeMangaBySmartSearch: MergeMangaBySmartSearch
    val migrateMangaUseCase: MigrateMangaUseCase
    val pagePreviewCache: PagePreviewCache
    val removeExtensionStore: RemoveExtensionStore
    val removeHistory: RemoveHistory
    val removeResettedHistory: RemoveResettedHistory
    val renameCategory: RenameCategory
    val renameSourceCategory: RenameSourceCategory
    val reorderCategory: ReorderCategory
    val reorderFeed: ReorderFeed
    val reorderSortTag: ReorderSortTag
    val searchEngine: SearchEngine
    val setDisplayMode: SetDisplayMode
    val setExcludedScanlators: SetExcludedScanlators
    val setMangaCategories: SetMangaCategories
    val setMangaChapterFlags: SetMangaChapterFlags
    val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags
    val setMigrateSorting: SetMigrateSorting
    val completeRereadIfNeeded: CompleteRereadIfNeeded
    val startRereading: StartRereading
    val stopRereading: StopRereading
    val setReadStatus: SetReadStatus
    val setSortModeForCategory: SetSortModeForCategory
    val setSourceCategories: SetSourceCategories
    val syncPreferences: SyncPreferences
    val syncStatus: SyncStatus
    val toggleExcludeFromDataSaver: ToggleExcludeFromDataSaver
    val toggleIncognito: ToggleIncognito
    val toggleLanguage: ToggleLanguage
    val toggleSource: ToggleSource
    val toggleSourcePin: ToggleSourcePin
    val trustExtension: TrustExtension
    val updateChapter: UpdateChapter
    val updateExtensionStores: UpdateExtensionStores
    val updateMangaNotes: UpdateMangaNotes
    val updateMergedSettings: UpdateMergedSettings
    val xml: XML
    val localCoverManager: LocalCoverManager
    val localSourceFileSystem: LocalSourceFileSystem
    val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata
    val chapterRepository: ChapterRepository
    val deleteCachedRecommendations: DeleteCachedRecommendations
    val eHentaiUpdateHelper: EHentaiUpdateHelper
    val extensionStoreRepository: ExtensionStoreRepository
    val getApplicationRelease: GetApplicationRelease
    val getCachedRecommendations: GetCachedRecommendations
    val getChapterByUrl: GetChapterByUrl
    val getSearchMetadata: GetSearchMetadata
    val insertCachedRecommendations: InsertCachedRecommendations
    val insertFavoriteEntryAlternative: InsertFavoriteEntryAlternative
    val updateCategory: UpdateCategory
    val upsertHistory: UpsertHistory
    // KMK <--

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AppGraph
    }
}
