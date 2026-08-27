package mihon.app.di

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import exh.pref.DelegateSourcePreferences
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.archive.CbzCrypto
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.OkHttpClient
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.AndroidStorageFolderProvider
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Manga_recommendations
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import xyz.nulldev.ts.api.http.serializer.FilterSerializer

// SY -->
private const val LEGACY_DATABASE_NAME = "tachiyomi.db"
// SY <--

@BindingContainer
object AppBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesSqlDriver(context: android.content.Context, securityPreferences: SecurityPreferences): SqlDriver {
        // SY -->
        if (securityPreferences.encryptDatabase().get()) {
            System.loadLibrary("sqlcipher")
        }
        // SY <--

        return AndroidSqliteDriver(
            schema = Database.Schema,
            context = context,
            // SY -->
            name = if (securityPreferences.encryptDatabase().get()) {
                CbzCrypto.DATABASE_NAME
            } else {
                LEGACY_DATABASE_NAME
            },
            factory = if (securityPreferences.encryptDatabase().get()) {
                SupportOpenHelperFactory(CbzCrypto.getDecryptedPasswordSql(), null, false, 25)
            } else if (isDebugBuildType && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // Support database inspector in Android Studio
                FrameworkSQLiteOpenHelperFactory()
            } else {
                RequerySQLiteOpenHelperFactory()
            },
            // SY <--
            callback = object : AndroidSqliteDriver.Callback(Database.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    setPragma(db, "foreign_keys = ON")
                    setPragma(db, "journal_mode = WAL")
                    setPragma(db, "synchronous = NORMAL")
                }
                private fun setPragma(db: SupportSQLiteDatabase, pragma: String) {
                    val cursor = db.query("PRAGMA $pragma")
                    cursor.moveToFirst()
                    cursor.close()
                }
            },
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesDatabase(driver: SqlDriver): Database {
        return Database(
            driver = driver,
            historyAdapter = History.Adapter(
                last_readAdapter = DateColumnAdapter,
            ),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
                // KMK -->
                scanlator_priorityAdapter = StringListColumnAdapter,
                blacklisted_chaptersAdapter = StringListColumnAdapter,
                scanlator_range_rulesAdapter = StringListColumnAdapter,
                // KMK <--
            ),
            chaptersAdapter = Chapters.Adapter(
                memoAdapter = MemoColumnAdapter,
            ),
            // KMK -->
            manga_recommendationsAdapter = Manga_recommendations.Adapter(
                resultsAdapter = MemoColumnAdapter,
            ),
            // KMK <--
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesXML(): XML = XML.v1 {
        policy {
            ignoreUnknownChildren()
            autoPolymorphic = true
        }
        xmlDeclMode = XmlDeclMode.Charset
        xmlVersion = XmlVersion.XML10
        setIndent(2)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesProtoBuf(): ProtoBuf = ProtoBuf

    @Provides
    @SingleIn(AppScope::class)
    fun providesPreferenceStore(context: Context): PreferenceStore {
        return AndroidPreferenceStore(context)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesFolderProvider(context: Context): FolderProvider {
        return AndroidStorageFolderProvider(context)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesDelegateSourcePreferences(preferenceStore: PreferenceStore): DelegateSourcePreferences {
        return DelegateSourcePreferences(preferenceStore)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesGoogleDriveSyncService(
        context: Context,
        json: Json,
        syncPreferences: eu.kanade.domain.sync.SyncPreferences,
    ): GoogleDriveSyncService {
        return GoogleDriveSyncService(context, json, syncPreferences)
    }

    // KMK -->
    @Provides
    @SingleIn(AppScope::class)
    fun providesApplication(context: Context): Application {
        return context as Application
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesFilterSerializer(): FilterSerializer {
        return FilterSerializer()
    }

    // KMK -->
    @Provides
    @SingleIn(AppScope::class)
    fun providesOkHttpClient(networkHelper: NetworkHelper): OkHttpClient {
        return networkHelper.client
    }
    // KMK <--
}
