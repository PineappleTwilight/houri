package mihon.app.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.library.MetadataUpdateJob
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
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
import kotlinx.serialization.json.Json
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.updates.service.UpdatesPreferences

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

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AppGraph
    }
}
