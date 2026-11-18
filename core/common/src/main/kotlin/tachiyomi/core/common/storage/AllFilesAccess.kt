package tachiyomi.core.common.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/**
 * Whether the app may read every file of the device's storage volumes, as directory view needs to browse them: since
 * Android 11 the folder picker refuses the root of internal storage.
 */
fun Context.hasAllFilesAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}
