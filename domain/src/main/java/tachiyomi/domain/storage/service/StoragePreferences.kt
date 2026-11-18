package tachiyomi.domain.storage.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.FolderProvider

@Inject
@SingleIn(AppScope::class)
class StoragePreferences(
    folderProvider: FolderProvider,
    preferenceStore: PreferenceStore,
) {

    val baseStorageDirectory: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("storage_dir"),
        folderProvider.path(),
    )

    val localSourceDirectories: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("local_source_dirs"),
        emptySet(),
    )

    /**
     * Whether comics are opened by browsing the series folders like a file explorer, instead of from the library.
     */
    val directoryView: Preference<Boolean> = preferenceStore.getBoolean("directory_view", false)

    /**
     * The folder the app saves the covers and details of series to, so that it writes nothing into series folders.
     * Empty to save them into the series folders.
     */
    val localCoverDirectory: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("local_cover_dir"),
        "",
    )
}
