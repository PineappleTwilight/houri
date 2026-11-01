package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavKey
import com.google.zxing.client.android.Intents
import com.hippo.unifile.UniFile
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.presentation.category.hierarchicalVisualName
import eu.kanade.presentation.category.sortedByHierarchy
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SettingsSecurityRoute.PasswordDialog
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.more.settings.screen.data.StorageInfo
import eu.kanade.presentation.more.settings.screen.data.SyncSettingsSelector
import eu.kanade.presentation.more.settings.screen.data.SyncSettingsSelectorRoute
import eu.kanade.presentation.more.settings.screen.data.SyncTriggerOptionsRoute
import eu.kanade.presentation.more.settings.screen.data.SyncTriggerOptionsScreen
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.more.settings.widget.TrailingWidgetBuffer
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.database.DatabaseExporter
import eu.kanade.tachiyomi.data.export.LibraryExporter
import eu.kanade.tachiyomi.data.export.LibraryExporter.ExportOptions
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import exh.log.xLogE
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.navigation.CreateBackupRoute
import mihon.core.navigation.DownloadedContentRoute
import mihon.core.navigation.RestoreBackupRoute
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.Category.Companion.UNCATEGORIZED_ID
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.storage.service.StorageManager.Companion.allowAccessStorage
import tachiyomi.domain.storage.service.StorageManager.Companion.directoryAccessible
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.io.IOException

@Serializable
data object SettingsDataRoute : NavKey, SearchableRoute {
    @Suppress("unused")
    val restorePreferenceKeyString = MR.strings.label_backup
    const val HELP_URL = "https://komikku-app.github.io/docs/faq/storage"

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_data_storage

    @Composable
    override fun RowScope.AppBarAction() {
        val uriHandler = LocalUriHandler.current
        IconButton(onClick = { uriHandler.openUri(HELP_URL) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val backupPreferences = globalAppGraph.backupPreferences
        val storagePreferences = globalAppGraph.storagePreferences

        val syncPreferences = remember { globalAppGraph.syncPreferences }
        val syncService by syncPreferences.syncService().collectAsState()

        return persistentListOf(
            getStorageLocationPref(storagePreferences = storagePreferences),
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_storage_location_info)),

            getBackupAndRestoreGroup(backupPreferences = backupPreferences),
            getDataGroup(),
            getExportGroup(),
        ) +
            // SY -->
            getSyncPreferences(syncPreferences = syncPreferences, syncService = syncService)
        // SY <--
    }

    @Composable
    fun storageLocationPicker(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): ManagedActivityResultLauncher<Uri?, Uri?> {
        val context = LocalContext.current

        return rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                // For some reason InkBook devices do not implement the SAF properly. Persistable URI grants do not
                // work. However, simply retrieving the URI and using it works fine for these devices. Access is not
                // revoked after the app is closed or the device is restarted.
                // This also holds for some Samsung devices. Thus, we simply execute inside of a try-catch block and
                // ignore the exception if it is thrown.
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: SecurityException) {
                    logcat(LogPriority.ERROR, e)
                    context.toast(MR.strings.file_picker_uri_permission_unsupported)
                }

                UniFile.fromUri(context, uri)?.let {
                    storageDirPref.set("") // Trigger recompose
                    storageDirPref.set(it.uri.toString())
                }
            }
        }
    }

    @Composable
    fun storageLocationText(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): String {
        val context = LocalContext.current
        val storageDir by storageDirPref.collectAsState()

        // KMK -->
        var locationValid by remember(storageDir) {
            mutableStateOf(directoryAccessible(context, storageDir))
        }

        LaunchedEffect(storageDir) {
            storageDirPref.changes()
                .collectLatest {
                    locationValid = directoryAccessible(context, storageDir)
                }
        }

        if (!locationValid) {
            // KMK <--
            return stringResource(MR.strings.no_location_set)
        }

        return remember(storageDir) {
            val file = UniFile.fromUri(context, storageDir.toUri())
            file?.displayablePath
        } ?: stringResource(MR.strings.invalid_location, storageDir)
    }

    @Composable
    private fun getStorageLocationPref(
        storagePreferences: StoragePreferences,
    ): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val pickStorageLocation = storageLocationPicker(storagePreferences.baseStorageDirectory())

        // KMK -->
        val storagePref = storagePreferences.baseStorageDirectory()
        // KMK <--

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_storage_location),
            subtitle = storageLocationText(/* KMK --> */storagePref/* KMK <-- */),
            onClick = {
                try {
                    // KMK -->
                    allowAccessStorage(context, storagePref) {
                        // KMK <--
                        pickStorageLocation.launch(null)
                    }
                } catch (_: Exception) {
                    context.toast(MR.strings.file_picker_error)
                }
            },
        )
    }

    @Composable
    private fun getBackupAndRestoreGroup(backupPreferences: BackupPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val backStack = LocalBackStack.current

        val lastAutoBackup by backupPreferences.lastAutoBackupTimestamp().collectAsState()

        val chooseBackup = rememberLauncherForActivityResult(
            object : ActivityResultContracts.GetContent() {
                override fun createIntent(context: Context, input: String): Intent {
                    val intent = super.createIntent(context, input)
                    return Intent.createChooser(intent, context.stringResource(MR.strings.file_select_backup))
                }
            },
        ) {
            if (it == null) {
                context.toast(MR.strings.file_null_uri_error)
                return@rememberLauncherForActivityResult
            }

            backStack.add(RestoreBackupRoute(it.toString()))
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_backup),
            preferenceItems = persistentListOf(
                // Manual actions
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(restorePreferenceKeyString),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            MultiChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(intrinsicSize = IntrinsicSize.Min)
                                    .padding(horizontal = PrefsHorizontalPadding),
                            ) {
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = { backStack.add(CreateBackupRoute) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) {
                                    Text(stringResource(MR.strings.pref_create_backup))
                                }
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = {
                                        if (!BackupRestoreJob.isRunning(context)) {
                                            if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                                                context.toast(MR.strings.restore_miui_warning)
                                            }

                                            // no need to catch because it's wrapped with a chooser
                                            chooseBackup.launch("*/*")
                                        } else {
                                            context.toast(MR.strings.restore_in_progress)
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                ) {
                                    Text(stringResource(MR.strings.pref_restore_backup))
                                }
                            }
                        },
                    )
                },

                // Automatic backups
                Preference.PreferenceItem.ListPreference(
                    preference = backupPreferences.backupInterval(),
                    entries = persistentMapOf(
                        0 to stringResource(MR.strings.off),
                        1 to stringResource(MR.strings.update_1hour),
                        3 to stringResource(MR.strings.update_3hour),
                        6 to stringResource(MR.strings.update_6hour),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(MR.strings.pref_backup_interval),
                    onValueChanged = {
                        BackupCreateJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(MR.strings.backup_info) + "\n\n" +
                        stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup)),
                ),
                // KMK -->
                Preference.PreferenceItem.SwitchPreference(
                    preference = backupPreferences.showRestoringProgressBanner(),
                    title = stringResource(KMR.strings.pref_show_restoring_progress_banner),
                ),
                // KMK <--
            ),
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val libraryPreferences = remember { globalAppGraph.libraryPreferences }

        // KMK -->
        val backStack = LocalBackStack.current
        // KMK <--

        val chapterCache = remember { globalAppGraph.chapterCache }
        var cacheReadableSizeSema by remember { mutableIntStateOf(0) }
        val cacheReadableSize = remember(cacheReadableSizeSema) { chapterCache.readableSize }

        // SY -->
        val pagePreviewCache = remember { globalAppGraph.pagePreviewCache }
        var pagePreviewReadableSizeSema by remember { mutableIntStateOf(0) }
        val pagePreviewReadableSize = remember(pagePreviewReadableSizeSema) { pagePreviewCache.readableSize }
        // SY <--

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_storage_usage),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_storage_usage),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            StorageInfo(
                                modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                            )
                        },
                    )
                },

                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_chapter_cache),
                    subtitle = stringResource(MR.strings.used_cache, cacheReadableSize),
                    onClick = {
                        scope.launchNonCancellable {
                            try {
                                val deletedFiles = chapterCache.clear()
                                withUIContext {
                                    context.toast(context.stringResource(MR.strings.cache_deleted, deletedFiles))
                                    cacheReadableSizeSema++
                                }
                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e)
                                withUIContext { context.toast(MR.strings.cache_delete_error) }
                            }
                        }
                    },
                ),
                // SY -->
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(SYMR.strings.pref_clear_page_preview_cache),
                    subtitle = stringResource(MR.strings.used_cache, pagePreviewReadableSize),
                    onClick = {
                        scope.launchNonCancellable {
                            try {
                                val deletedFiles = pagePreviewCache.clear()
                                withUIContext {
                                    context.toast(context.stringResource(MR.strings.cache_deleted, deletedFiles))
                                    pagePreviewReadableSizeSema++
                                }
                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e)
                                withUIContext { context.toast(MR.strings.cache_delete_error) }
                            }
                        }
                    },
                ),
                // SY <--
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.autoClearChapterCache(),
                    title = stringResource(MR.strings.pref_auto_clear_chapter_cache),
                ),
                // KMK -->
                Preference.PreferenceItem.ListPreference(
                    preference = libraryPreferences.chapterCacheRetention(),
                    entries = persistentMapOf(
                        0 to stringResource(KMR.strings.chapter_cache_retention_off),
                        12 to stringResource(KMR.strings.chapter_cache_retention_12h),
                        24 to stringResource(KMR.strings.chapter_cache_retention_24h),
                        72 to stringResource(KMR.strings.chapter_cache_retention_3d),
                        168 to stringResource(KMR.strings.chapter_cache_retention_7d),
                        720 to stringResource(KMR.strings.chapter_cache_retention_30d),
                    ),
                    title = stringResource(KMR.strings.chapter_cache_retention_title),
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(KMR.strings.chapter_cache_retention_support),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_downloaded_content),
                    subtitle = stringResource(KMR.strings.pref_downloaded_content_support),
                    onClick = { backStack.add(DownloadedContentRoute) },
                ),
                // KMK <--
            ),
        )
    }

    @Composable
    private fun getExportGroup(): Preference.PreferenceGroup {
        var showDialog by remember { mutableStateOf(false) }
        var exportOptions by remember {
            mutableStateOf(
                ExportOptions(
                    includeTitle = true,
                    includeAuthor = true,
                    includeArtist = true,
                ),
            )
        }

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val getFavorites = remember { globalAppGraph.getFavorites }
        val getCategories = remember { globalAppGraph.getCategories }
        val mangaRepository = remember { globalAppGraph.mangaRepository }
        var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
        // KMK --> Loaded when the dialog opens, so the list reflects the current categories
        // without paying for a query while the settings screen merely sits here.
        LaunchedEffect(showDialog) {
            if (showDialog) categories = withIOContext { getCategories.await() }
        }
        // KMK <--

        val saveFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                // KMK --> Favorites and memberships are fetched here rather than when the
                // settings screen opened, so the file reflects the library as of the moment
                // the user confirmed the export. An entry with no membership is "Default";
                // selecting Default exports those entries alongside the named categories.
                val favorites = withIOContext { getFavorites.await() }
                val scopedFavorites = exportOptions.includedCategoryIds
                    ?.let { selectedIds ->
                        val memberships = withIOContext { mangaRepository.getFavoriteCategoryPairs() }
                        favorites.filter { manga ->
                            val mangaCategories = memberships[manga.id].orEmpty()
                            (UNCATEGORIZED_ID in selectedIds && mangaCategories.isEmpty()) ||
                                mangaCategories.any(selectedIds::contains)
                        }
                    }
                    ?: favorites
                // KMK <--
                LibraryExporter.exportToCsv(
                    context = context,
                    uri = uri,
                    favorites = scopedFavorites,
                    options = exportOptions,
                    onExportComplete = {
                        scope.launch(Dispatchers.Main) {
                            context.toast(MR.strings.library_exported)
                        }
                    },
                )
            }
        }

        val databaseExporter = remember { globalAppGraph.databaseExporter }
        val exportDbLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/vnd.sqlite3"),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                // Subject bound to a val so the failure branch smart-casts: ktlint rejects the typed
                // branch-lambda alternative, and `when` subjects have no implicit `it`.
                when (val result = databaseExporter.export(uri)) {
                    DatabaseExporter.Result.Success -> context.toast(KMR.strings.database_export_success)
                    // The checkpoint is what keeps recent writes out of the exported file, so
                    // refusing is the only honest outcome here.
                    DatabaseExporter.Result.SnapshotUnavailable ->
                        context.toast(KMR.strings.database_export_checkpoint_failed)

                    is DatabaseExporter.Result.Failed -> {
                        xLogE("Database export failed", result.cause)
                        context.toast(KMR.strings.database_export_failed)
                    }
                }
            }
        }

        if (showDialog) {
            ExportOptionsDialog(
                options = exportOptions,
                categories = categories,
                onConfirm = { options ->
                    exportOptions = options
                    saveFileLauncher.launch("houri_library.csv")
                },
                onDismissRequest = { showDialog = false },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.export),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.library_list),
                    onClick = { showDialog = true },
                ),
                // KMK --> Snapshot export: DatabaseExporter checkpoints the WAL on the live
                // connection first, so the file carries every commit rather than whatever
                // happened to be folded into the main file at that instant.
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_export_database),
                    subtitle = stringResource(KMR.strings.pref_export_database_summary),
                    onClick = { exportDbLauncher.launch(databaseExporter.suggestedFileName) },
                ),
                // KMK <--
            ),
        )
    }

    // KMK --> Extended from the column-only picker: it also scopes the export to categories.
    // Default (uncategorized) is listed alongside the named categories and selects the
    // entries with no category membership, matching how the library groups them.
    @Composable
    private fun ExportOptionsDialog(
        options: ExportOptions,
        categories: List<Category>,
        onConfirm: (ExportOptions) -> Unit,
        onDismissRequest: () -> Unit,
    ) {
        var titleSelected by remember { mutableStateOf(options.includeTitle) }
        var authorSelected by remember { mutableStateOf(options.includeAuthor) }
        var artistSelected by remember { mutableStateOf(options.includeArtist) }
        val categoryIds = remember(categories) { categories.map { it.id } }
        var selectedCategories by remember(categories) {
            mutableStateOf(options.includedCategoryIds ?: categoryIds.toSet())
        }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = titleSelected,
                            onCheckedChange = { checked ->
                                titleSelected = checked
                                if (!checked) {
                                    authorSelected = false
                                    artistSelected = false
                                }
                            },
                        )
                        Text(text = stringResource(MR.strings.title))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = authorSelected,
                            onCheckedChange = { authorSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.author))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = artistSelected,
                            onCheckedChange = { artistSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.artist))
                    }

                    if (categories.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(MR.strings.categories),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        categories.sortedByHierarchy().forEach { category ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = category.id in selectedCategories,
                                    onCheckedChange = { checked ->
                                        selectedCategories = if (checked) {
                                            selectedCategories + category.id
                                        } else {
                                            selectedCategories - category.id
                                        }
                                    },
                                )
                                Text(text = category.hierarchicalVisualName)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = categories.isEmpty() || selectedCategories.isNotEmpty(),
                    onClick = {
                        onConfirm(
                            ExportOptions(
                                includeTitle = titleSelected,
                                includeAuthor = authorSelected,
                                includeArtist = artistSelected,
                                // All categories checked means no scoping: the file matches
                                // the pre-scoping export and stays reload-compatible.
                                includedCategoryIds = selectedCategories
                                    .takeUnless { categoryIds.isEmpty() || it.size == categoryIds.size },
                            ),
                        )
                        onDismissRequest()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
    // KMK <--

    // SY -->
    @Composable
    private fun getSyncPreferences(syncPreferences: SyncPreferences, syncService: Int): List<Preference> {
        val context = LocalContext.current
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(SYMR.strings.pref_sync_service_category),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = syncPreferences.syncService(),
                        entries = persistentMapOf(
                            SyncManager.SyncService.NONE.value to stringResource(MR.strings.off),
                            SyncManager.SyncService.SYNCYOMI.value to stringResource(SYMR.strings.syncyomi),
                            SyncManager.SyncService.GOOGLE_DRIVE.value to stringResource(SYMR.strings.google_drive),
                            // KMK -->
                            SyncManager.SyncService.WEB_DAV.value to stringResource(KMR.strings.web_dav),
                            // KMK <--
                        ),
                        title = stringResource(SYMR.strings.pref_sync_service),
                        onValueChanged = {
                            // KMK -->
                            if (it != SyncManager.SyncService.NONE.value) {
                                SyncDataJob.setupTask(context)
                            } else {
                                SyncDataJob.setupTask(context, prefInterval = 0)
                            }
                            // KMK <--
                            true
                        },
                    ),
                ),
            ),
        ) + getSyncServicePreferences(syncPreferences, syncService)
    }

    @Composable
    private fun getSyncServicePreferences(syncPreferences: SyncPreferences, syncService: Int): List<Preference> {
        val syncServiceType = SyncManager.SyncService.fromInt(syncService)

        val basePreferences = getBasePreferences(syncServiceType, syncPreferences)

        return if (syncServiceType != SyncManager.SyncService.NONE) {
            basePreferences + getAdditionalPreferences(syncPreferences)
        } else {
            basePreferences
        }
    }

    @Composable
    private fun getBasePreferences(
        syncServiceType: SyncManager.SyncService,
        syncPreferences: SyncPreferences,
    ): List<Preference> {
        val backStack = LocalBackStack.current
        val preferences = when (syncServiceType) {
            SyncManager.SyncService.NONE -> emptyList()
            SyncManager.SyncService.SYNCYOMI -> getSelfHostPreferences(syncPreferences)
            SyncManager.SyncService.GOOGLE_DRIVE -> getGoogleDrivePreferences()
            // KMK -->
            SyncManager.SyncService.WEB_DAV -> getWebDavPreferences(syncPreferences)
            // KMK <--
        }

        return if (syncServiceType != SyncManager.SyncService.NONE) {
            preferences + Preference.PreferenceItem.TextPreference(
                title = stringResource(SYMR.strings.pref_choose_what_to_sync),
                onClick = {
                    backStack.add(SyncSettingsSelectorRoute)
                },
            )
        } else {
            preferences
        }
    }

    @Composable
    private fun getAdditionalPreferences(syncPreferences: SyncPreferences): List<Preference> {
        return listOf(
            getSyncNowPref(),
            getAutomaticSyncGroup(syncPreferences),
            // KMK -->
            Preference.PreferenceItem.SwitchPreference(
                preference = syncPreferences.showSyncingProgressBanner(),
                title = stringResource(KMR.strings.pref_show_syncing_progress_banner),
            ),
            // KMK <--
        )
    }

    @Composable
    private fun getGoogleDrivePreferences(): List<Preference> {
        val context = LocalContext.current
        val googleDriveSync = globalAppGraph.googleDriveService
        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(SYMR.strings.pref_google_drive_sign_in),
                onClick = {
                    // KMK --> a build without the client_secrets.json asset (CI injects it)
                    // used to crash the screen; surface it as a toast instead.
                    try {
                        context.startActivity(googleDriveSync.getSignInIntent())
                    } catch (e: IOException) {
                        context.toast(
                            context.stringResource(SYMR.strings.google_drive_login_failed, e.message.orEmpty()),
                            Toast.LENGTH_LONG,
                        )
                    } catch (e: ActivityNotFoundException) {
                        // no browser on the device: startActivity throws before the
                        // OAuth code even runs, and an uncaught ActivityNotFoundException
                        // crashes here. Same handling as the asset failure, through the same
                        // login-failed message (SY #1372).
                        context.toast(
                            context.stringResource(SYMR.strings.google_drive_login_failed, e.message.orEmpty()),
                            Toast.LENGTH_LONG,
                        )
                    }
                    // KMK <--
                },
            ),
            getGoogleDrivePurge(),
        )
    }

    @Composable
    private fun getGoogleDrivePurge(): Preference.PreferenceItem.TextPreference {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val googleDriveSync = remember { GoogleDriveSyncService(context) }
        var showPurgeDialog by remember { mutableStateOf(false) }

        if (showPurgeDialog) {
            PurgeConfirmationDialog(
                onConfirm = {
                    showPurgeDialog = false
                    scope.launch {
                        val result = googleDriveSync.deleteSyncDataFromGoogleDrive()
                        when (result) {
                            GoogleDriveSyncService.DeleteSyncDataStatus.NOT_INITIALIZED -> context.toast(
                                SYMR.strings.google_drive_not_signed_in,
                                duration = 5000,
                            )
                            GoogleDriveSyncService.DeleteSyncDataStatus.NO_FILES -> context.toast(
                                SYMR.strings.google_drive_sync_data_not_found,
                                duration = 5000,
                            )
                            GoogleDriveSyncService.DeleteSyncDataStatus.SUCCESS -> context.toast(
                                SYMR.strings.google_drive_sync_data_purged,
                                duration = 5000,
                            )
                            GoogleDriveSyncService.DeleteSyncDataStatus.ERROR -> context.toast(
                                SYMR.strings.google_drive_sync_data_purge_error,
                                duration = 10000,
                            )
                        }
                    }
                },
                onDismissRequest = { showPurgeDialog = false },
            )
        }

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.pref_google_drive_purge_sync_data),
            onClick = { showPurgeDialog = true },
        )
    }

    @Composable
    private fun PurgeConfirmationDialog(
        onConfirm: () -> Unit,
        onDismissRequest: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = stringResource(SYMR.strings.pref_purge_confirmation_title)) },
            text = { Text(text = stringResource(SYMR.strings.pref_purge_confirmation_message)) },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
        )
    }

    @Composable
    private fun getSelfHostPreferences(syncPreferences: SyncPreferences): List<Preference> {
        val scope = rememberCoroutineScope()

        val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) {
            if (it.contents != null && it.contents.isNotEmpty()) {
                syncPreferences.clientAPIKey().set(it.contents)
            }
        }
        val context = LocalContext.current
        val scanOptions = remember {
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setOrientationLocked(false)
                setPrompt(SYMR.strings.scan_qr_code.getString(context))
                addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN)
            }
        }

        return listOf(
            Preference.PreferenceItem.EditTextPreference(
                preference = syncPreferences.clientHost(),
                title = stringResource(SYMR.strings.pref_sync_host),
                subtitle = stringResource(SYMR.strings.pref_sync_host_summ),
                onValueChanged = { newValue ->
                    scope.launch {
                        // Trim spaces at the beginning and end, then remove trailing slash if present
                        val trimmedValue = newValue.trim()
                        val modifiedValue = trimmedValue.trimEnd { it == '/' }
                        syncPreferences.clientHost().set(modifiedValue)
                    }
                    true
                },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(SYMR.strings.pref_sync_api_key),
            ) {
                val values by syncPreferences.clientAPIKey().collectAsState()
                EditTextPreferenceWidget(
                    title = stringResource(SYMR.strings.pref_sync_api_key),
                    subtitle = stringResource(SYMR.strings.pref_sync_api_key_summ),
                    onConfirm = {
                        scope.launch {
                            syncPreferences.clientAPIKey().set(it)
                        }
                        true
                    },
                    icon = null,
                    value = values,
                    widget = {
                        IconButton(
                            onClick = { qrScanLauncher.launch(scanOptions) },
                            modifier = Modifier.padding(start = TrailingWidgetBuffer),
                        ) {
                            Icon(
                                Icons.Filled.QrCodeScanner,
                                contentDescription = stringResource(SYMR.strings.scan_qr_code),
                            )
                        }
                    },
                )
            },
        )
    }

    // KMK -->
    @Composable
    private fun getWebDavPreferences(syncPreferences: SyncPreferences): List<Preference> {
        val scope = rememberCoroutineScope()

        return listOf(
            Preference.PreferenceItem.EditTextPreference(
                preference = syncPreferences.webDavUrl(),
                title = stringResource(KMR.strings.pref_webdav_url),
                subtitle = stringResource(KMR.strings.pref_webdav_url_summ),
                onValueChanged = { newValue ->
                    scope.launch {
                        syncPreferences.webDavUrl().set(newValue.trim())
                    }
                    true
                },
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = syncPreferences.webDavUsername(),
                title = stringResource(KMR.strings.pref_webdav_username),
                subtitle = stringResource(KMR.strings.pref_webdav_username_summ),
                onValueChanged = { newValue ->
                    scope.launch {
                        syncPreferences.webDavUsername().set(newValue.trim())
                    }
                    true
                },
            ),
            run {
                var dialogOpen by remember { mutableStateOf(false) }
                if (dialogOpen) {
                    PasswordDialog(
                        onDismissRequest = { dialogOpen = false },
                        onReturnPassword = { password ->
                            dialogOpen = false
                            scope.launch {
                                syncPreferences.webDavPassword().set(password.replace("\n", ""))
                            }
                        },
                        title = KMR.strings.pref_webdav_password,
                    )
                }
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_webdav_password),
                    subtitle = stringResource(KMR.strings.pref_webdav_password_summ),
                    onClick = {
                        dialogOpen = true
                    },
                )
            },
            Preference.PreferenceItem.EditTextPreference(
                preference = syncPreferences.webDavFolder(),
                title = stringResource(KMR.strings.pref_webdav_folder),
                subtitle = stringResource(KMR.strings.pref_webdav_folder_summ),
                onValueChanged = { newValue ->
                    scope.launch {
                        syncPreferences.webDavFolder().set(newValue.trim())
                    }
                    true
                },
            ),
        )
    }
    // KMK <--

    @Composable
    private fun getSyncNowPref(): Preference.PreferenceGroup {
        val context = LocalContext.current
        return Preference.PreferenceGroup(
            title = stringResource(SYMR.strings.pref_sync_now_group_title),
            preferenceItems = persistentListOf(
                getSyncOptionsPref(),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(SYMR.strings.pref_sync_now),
                    subtitle = stringResource(SYMR.strings.pref_sync_now_subtitle),
                    onClick = {
                        if (!SyncDataJob.isRunning(context)) {
                            SyncDataJob.startNow(context, manual = true)
                        } else {
                            context.toast(SYMR.strings.sync_in_progress)
                        }
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getSyncOptionsPref(): Preference.PreferenceItem.TextPreference {
        val backStack = LocalBackStack.current
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.pref_sync_options),
            subtitle = stringResource(SYMR.strings.pref_sync_options_summ),
            onClick = { backStack.add(SyncTriggerOptionsRoute) },
        )
    }

    @Composable
    private fun getAutomaticSyncGroup(syncPreferences: SyncPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val syncIntervalPref = syncPreferences.syncInterval()
        val lastSync by syncPreferences.lastSyncTimestamp().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(SYMR.strings.pref_sync_automatic_category),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = syncIntervalPref,
                    entries = persistentMapOf(
                        0 to stringResource(MR.strings.off),
                        30 to stringResource(SYMR.strings.update_30min),
                        60 to stringResource(SYMR.strings.update_1hour),
                        180 to stringResource(SYMR.strings.update_3hour),
                        360 to stringResource(MR.strings.update_6hour),
                        720 to stringResource(MR.strings.update_12hour),
                        1440 to stringResource(MR.strings.update_24hour),
                        2880 to stringResource(MR.strings.update_48hour),
                        10080 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(SYMR.strings.pref_sync_interval),
                    onValueChanged = {
                        SyncDataJob.setupTask(context, prefInterval = it)
                        true
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(SYMR.strings.last_synchronization, relativeTimeSpanString(lastSync)),
                ),
            ),
        )
    }
    // SY <--
}
