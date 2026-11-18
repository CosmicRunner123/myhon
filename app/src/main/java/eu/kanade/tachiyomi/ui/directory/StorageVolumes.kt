package eu.kanade.tachiyomi.ui.directory

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.runtime.Immutable
import androidx.core.content.getSystemService
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.DiskUtil

/**
 * A mounted storage volume of the device, listed at the top level of directory view.
 *
 * @param uri the file URI of its root folder.
 * @param isPrimary whether it is the internal storage.
 */
@Immutable
data class DirectoryVolume(
    val uri: String,
    val name: String,
    val isPrimary: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

/**
 * The mounted storage volumes of the device, in [storageVolumeOrder].
 */
fun listStorageVolumes(context: Context): List<DirectoryVolume> {
    val storageManager = context.getSystemService<StorageManager>()!!
    val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        storageManager.storageVolumes.mapNotNull { volume -> volume.directory?.let { volume to it } }
    } else {
        // Volumes have no public directory before Android 11, so their roots are found from the app's folder on each
        DiskUtil.getExternalStorages(context).mapNotNull { directory ->
            storageManager.getStorageVolume(directory)?.let { it to directory }
        }
    }
    return volumes
        .filter { (volume, _) ->
            volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
        }
        .mapNotNull { (volume, directory) ->
            // Thrown when the volume was removed since
            val stat = try {
                StatFs(directory.path)
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }
            DirectoryVolume(
                uri = Uri.fromFile(directory).toString(),
                name = volume.getDescription(context),
                isPrimary = volume.isPrimary,
                freeBytes = stat.availableBytes,
                totalBytes = stat.totalBytes,
            )
        }
        .sortedWith(storageVolumeOrder)
}

/**
 * Internal storage first, then the others (SD cards, USB drives) by name, like a file explorer.
 */
internal val storageVolumeOrder: Comparator<DirectoryVolume> = compareBy<DirectoryVolume> { !it.isPrimary }
    .thenComparator { v1, v2 -> v1.name.compareToCaseInsensitiveNaturalOrder(v2.name) }
    // Removable volumes of the same kind can have the same name
    .thenBy { it.uri }
