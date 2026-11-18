package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.more.settings.screen.data.StorageInfo
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.backup.create.BackupCreateWorker
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.data.export.LibraryExporter
import eu.kanade.tachiyomi.data.export.LibraryExporter.ExportOptions
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.util.storage.rememberAllFilesAccess
import eu.kanade.tachiyomi.util.storage.rememberAllFilesAccessRequest
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Help
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.minusAssign
import tachiyomi.core.common.preference.plusAssign
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsDataScreen : SearchableSettings {

    val restorePreferenceKeyString = MR.strings.label_backup
    const val HELP_URL = "https://github.com/CosmicRunner123/myhon#storage"

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_data_storage

    @Composable
    override fun RowScope.AppBarAction() {
        val uriHandler = LocalUriHandler.current
        IconButton(onClick = { uriHandler.openUri(HELP_URL) }) {
            Icon(
                imageVector = MaterialSymbols.AutoMirroredRounded.Help,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val backupPreferences = remember { context.appGraph.backupPreferences }
        val storagePreferences = remember { context.appGraph.storagePreferences }
        val directoryView by storagePreferences.directoryView.collectAsState()

        return listOf(
            getStorageLocationPref(storagePreferences = storagePreferences),
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_storage_location_usage_info)),

            Preference.PreferenceItem.SwitchPreference(
                preference = storagePreferences.directoryView,
                title = stringResource(MR.strings.pref_directory_view),
                subtitle = stringResource(MR.strings.pref_directory_view_summary),
            ),
            getAllFilesAccessPref(visible = directoryView),
            getSeriesFoldersGroup(storagePreferences = storagePreferences, visible = !directoryView),
            getBackupAndRestoreGroup(backupPreferences = backupPreferences),
            getDataGroup(),
            getExportGroup(),
        )
    }

    @Composable
    fun folderPicker(onPick: (String) -> Unit): ManagedActivityResultLauncher<Uri?, Uri?> {
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
                    onPick(it.uri.toString())
                }
            }
        }
    }

    /**
     * Picks the storage location, whose "local" folder is the first series folder.
     */
    @Composable
    fun storageLocationPicker(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): ManagedActivityResultLauncher<Uri?, Uri?> {
        val updateLibrary = libraryUpdater()

        return folderPicker { location ->
            if (location != storageDirPref.get()) {
                storageDirPref.set(location)
                updateLibrary()
            }
        }
    }

    /**
     * Starts an update of every library entry. After a folder change, only a library update adds or drops the
     * chapters of the series that stay in the library, including those kept out of updates, and finds or makes their
     * covers again. Does nothing in directory view, which keeps no library.
     */
    @Composable
    private fun libraryUpdater(): () -> Unit {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val directoryViewPref = remember { context.appGraph.storagePreferences.directoryView }

        return {
            if (!directoryViewPref.get()) {
                scope.launch { LibraryUpdateWorker.startNow(context.workManager, allEntries = true) }
            }
        }
    }

    @Composable
    fun storageLocationText(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): String {
        val context = LocalContext.current
        val storageDir by storageDirPref.collectAsState()

        if (storageDir == storageDirPref.defaultValue()) {
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
        val pickStorageLocation = storageLocationPicker(storagePreferences.baseStorageDirectory)

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_storage_location),
            subtitle = storageLocationText(storagePreferences.baseStorageDirectory),
            onClick = {
                try {
                    pickStorageLocation.launch(null)
                } catch (e: ActivityNotFoundException) {
                    context.toast(MR.strings.file_picker_error)
                }
            },
        )
    }

    /**
     * Whether directory view may read the whole device, which it browses.
     */
    @Composable
    private fun getAllFilesAccessPref(visible: Boolean): Preference.PreferenceItem.TextPreference {
        val hasAccess = rememberAllFilesAccess()
        val requestAccess = rememberAllFilesAccessRequest()

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_all_files_access),
            subtitle = if (hasAccess) {
                stringResource(MR.strings.pref_all_files_access_allowed)
            } else {
                stringResource(MR.strings.pref_all_files_access_not_allowed)
            },
            visible = visible,
            onClick = requestAccess,
        )
    }

    @Composable
    private fun getSeriesFoldersGroup(
        storagePreferences: StoragePreferences,
        visible: Boolean,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current

        val foldersPref = storagePreferences.localSourceDirectories
        val folders by foldersPref.collectAsState()
        val folderPaths = remember(folders) {
            folders.sorted().associateWith { UniFile.fromUri(context, it.toUri())?.displayablePath ?: it }
        }
        var folderToRemove by rememberSaveable { mutableStateOf<String?>(null) }

        val updateLibrary = libraryUpdater()

        val pickFolder = folderPicker { folder ->
            if (folder !in foldersPref.get()) {
                foldersPref += folder
                updateLibrary()
            }
        }

        folderToRemove?.let { folder ->
            AlertDialog(
                onDismissRequest = { folderToRemove = null },
                title = {
                    Text(text = stringResource(MR.strings.series_folder_remove_title))
                },
                text = {
                    Text(text = stringResource(MR.strings.series_folder_remove_message, folderPaths[folder] ?: folder))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            foldersPref -= folder
                            releaseFolderPermission(context, storagePreferences, folder)
                            updateLibrary()
                            folderToRemove = null
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { folderToRemove = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_series_folders),
            visible = visible,
            preferenceItems = buildList {
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_series_folders_default),
                        subtitle = stringResource(MR.strings.pref_series_folders_default_summary),
                    ),
                )
                folderPaths.forEach { (folder, path) ->
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = path,
                            onClick = { folderToRemove = folder },
                        ),
                    )
                }
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_series_folders_add),
                        onClick = {
                            try {
                                pickFolder.launch(null)
                            } catch (e: ActivityNotFoundException) {
                                context.toast(MR.strings.file_picker_error)
                            }
                        },
                    ),
                )
                add(Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_series_folders_info)))
                add(getCoverFolderPref(storagePreferences = storagePreferences))
            },
        )
    }

    @Composable
    private fun getCoverFolderPref(storagePreferences: StoragePreferences): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val updateLibrary = libraryUpdater()

        val coverFolderPref = storagePreferences.localCoverDirectory
        val coverFolder by coverFolderPref.collectAsState()
        val coverFolderPath = remember(coverFolder) {
            coverFolder.ifEmpty { null }?.let { UniFile.fromUri(context, it.toUri())?.displayablePath ?: it }
        }

        val changeCoverFolder = { folder: String ->
            val previousFolder = coverFolderPref.get()
            if (folder != previousFolder) {
                coverFolderPref.set(folder)
                if (previousFolder.isNotEmpty()) {
                    releaseFolderPermission(context, storagePreferences, previousFolder)
                }
                updateLibrary()
            }
        }
        val pickCoverFolder = folderPicker(changeCoverFolder)

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_cover_folder),
            subtitle = coverFolderPath ?: stringResource(MR.strings.pref_cover_folder_default),
            widget = if (coverFolderPath != null) {
                {
                    IconButton(onClick = { changeCoverFolder("") }) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Close,
                            contentDescription = stringResource(MR.strings.pref_cover_folder_reset),
                        )
                    }
                }
            } else {
                null
            },
            onClick = {
                try {
                    pickCoverFolder.launch(null)
                } catch (e: ActivityNotFoundException) {
                    context.toast(MR.strings.file_picker_error)
                }
            },
        )
    }

    /**
     * Releases the permission taken on [folder] when it was picked, unless the storage location, a series folder or
     * the cover folder is still that folder.
     */
    private fun releaseFolderPermission(context: Context, storagePreferences: StoragePreferences, folder: String) {
        val foldersInUse = storagePreferences.localSourceDirectories.get() +
            storagePreferences.baseStorageDirectory.get() +
            storagePreferences.localCoverDirectory.get()
        if (folder in foldersInUse) return

        val uri = folder.toUri()
        val treeUri = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        // Thrown when no permission is held, as on the devices where the picker could not take one
        try {
            context.contentResolver.releasePersistableUriPermission(treeUri, flags)
        } catch (e: SecurityException) {
            logcat(LogPriority.ERROR, e)
        }
    }

    @Composable
    private fun getBackupAndRestoreGroup(backupPreferences: BackupPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        val lastAutoBackup by backupPreferences.lastAutoBackupTimestamp.collectAsState()

        val chooseBackup = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) {
            if (it == null) {
                context.toast(MR.strings.file_null_uri_error)
                return@rememberLauncherForActivityResult
            }

            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                logcat(LogPriority.ERROR, e)
            }

            navigator.push(RestoreBackupScreen(it.toString()))
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_backup),
            preferenceItems = listOf(
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
                                    onCheckedChange = { navigator.push(CreateBackupScreen()) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) {
                                    Text(stringResource(MR.strings.pref_create_backup))
                                }
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = {
                                        scope.launch {
                                            if (!BackupRestoreWorker.isRunning(context.workManager)) {
                                                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                                                    context.toast(MR.strings.restore_miui_warning)
                                                }
                                                chooseBackup.launch(arrayOf("*/*"))
                                            } else {
                                                context.toast(MR.strings.restore_in_progress)
                                            }
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
                    preference = backupPreferences.backupInterval,
                    entries = mapOf(
                        0 to stringResource(MR.strings.off),
                        6 to stringResource(MR.strings.update_6hour),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(MR.strings.pref_backup_interval),
                    onValueChanged = {
                        BackupCreateWorker.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(MR.strings.backup_info) + "\n\n" +
                        stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup)),
                ),
            ),
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_storage_usage),
            preferenceItems = listOf(
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
        val getFavorites = remember { context.appGraph.getFavorites }
        var favorites by remember { mutableStateOf<List<Manga>>(emptyList()) }
        LaunchedEffect(Unit) {
            favorites = getFavorites.await()
        }

        val saveFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            uri?.let {
                scope.launch {
                    LibraryExporter.exportToCsv(
                        context = context,
                        uri = it,
                        favorites = favorites,
                        options = exportOptions,
                        onExportComplete = {
                            scope.launch(Dispatchers.Main) {
                                context.toast(MR.strings.library_exported)
                            }
                        },
                    )
                }
            }
        }

        if (showDialog) {
            ColumnSelectionDialog(
                options = exportOptions,
                onConfirm = { options ->
                    exportOptions = options
                    saveFileLauncher.launch("myhon_library.csv")
                },
                onDismissRequest = { showDialog = false },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.export),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.library_list),
                    onClick = { showDialog = true },
                ),
            ),
        )
    }

    @Composable
    private fun ColumnSelectionDialog(
        options: ExportOptions,
        onConfirm: (ExportOptions) -> Unit,
        onDismissRequest: () -> Unit,
    ) {
        var titleSelected by remember { mutableStateOf(options.includeTitle) }
        var authorSelected by remember { mutableStateOf(options.includeAuthor) }
        var artistSelected by remember { mutableStateOf(options.includeArtist) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
            },
            text = {
                Column {
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
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm(
                            ExportOptions(
                                includeTitle = titleSelected,
                                includeAuthor = authorSelected,
                                includeArtist = artistSelected,
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
}
