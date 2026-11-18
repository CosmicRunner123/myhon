package eu.kanade.tachiyomi.util.storage

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.storage.hasAllFilesAccess
import tachiyomi.i18n.MR

/**
 * [hasAllFilesAccess], checked again whenever the screen resumes, as the user comes back from granting it.
 */
@Composable
fun rememberAllFilesAccess(): Boolean {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(context.hasAllFilesAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasAccess = context.hasAllFilesAccess() }
    return hasAccess
}

/**
 * Asks for [hasAllFilesAccess]: on Android 11+ in the system settings, below with the permission dialog, or in the
 * app's settings once that was denied for good. Either way the screen resumes when the user is done.
 */
@Composable
fun rememberAllFilesAccessRequest(): () -> Unit {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return remember(context) {
            {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    "package:${context.packageName}".toUri(),
                )
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (e: ActivityNotFoundException) {
                        context.toast(MR.strings.battery_optimization_setting_activity_not_found)
                    }
                }
            }
        }
    }
    val activity = LocalActivity.current
    val permission = Manifest.permission.READ_EXTERNAL_STORAGE
    var rationaleBefore by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // After "Don't ask again" the dialog is denied without showing, and only then no rationale is due before nor
        // after the request. A rationale due before means the dialog showed and the user ticked it just now.
        if (granted || activity == null || rationaleBefore ||
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        ) {
            return@rememberLauncherForActivityResult
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
            )
        } catch (e: ActivityNotFoundException) {
            context.toast(MR.strings.battery_optimization_setting_activity_not_found)
        }
    }
    return remember(launcher, activity) {
        {
            rationaleBefore =
                activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            launcher.launch(permission)
        }
    }
}
