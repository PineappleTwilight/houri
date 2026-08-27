package mihon.app.di.injekt

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import exh.eh.EHentaiUpdateHelper
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.repository.MangaMetadataRepository
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton

/**
 * Bridges Metro-provided singletons into the Injekt registry.
 * This allows extensions and legacy code that use Injekt.get<T>() to
 * continue working while the codebase migrates to Metro.
 *
 * As old Injekt modules are removed, their registrations move here.
 */
@Inject
class MetroInteropModule(
    // Serialization (from AppBindings)
    private val json: Json,
    private val protoBuf: ProtoBuf,
    private val xml: XML,

    // Network
    private val networkHelper: NetworkHelper,
    private val javaScriptEngine: JavaScriptEngine,

    // Preferences
    private val preferenceStore: PreferenceStore,
    private val delegateSourcePreferences: DelegateSourcePreferences,
    private val exhPreferences: ExhPreferences,

    // Sources & Extensions
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,

    // Tracking
    private val trackerManager: TrackerManager,

    // Storage
    private val storageManager: StorageManager,

    // EH
    private val eHentaiUpdateHelper: EHentaiUpdateHelper,
    private val mangaMetadataRepository: MangaMetadataRepository,
    private val sourceRepository: SourceRepository,

    // Komikku-specific
    private val connectionsManager: ConnectionsManager,
    private val googleDriveSyncService: GoogleDriveSyncService,
    private val imageSaver: ImageSaver,
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        // Serialization
        addSingleton(json)
        addSingleton(protoBuf)
        addSingleton(xml)

        // Network
        addSingleton(networkHelper)
        addSingleton(javaScriptEngine)

        // Preferences
        addSingleton(preferenceStore)
        addSingleton(delegateSourcePreferences)
        addSingleton(exhPreferences)

        // Sources & Extensions
        addSingleton(sourceManager)
        addSingleton(extensionManager)

        // Tracking
        addSingleton(trackerManager)

        // Storage
        addSingleton(storageManager)
        addSingleton(eHentaiUpdateHelper)
        addSingleton(mangaMetadataRepository)
        addSingleton(sourceRepository)

        // Komikku-specific
        addSingleton(connectionsManager)
        addSingleton(googleDriveSyncService)
        addSingleton(imageSaver)
    }
}
