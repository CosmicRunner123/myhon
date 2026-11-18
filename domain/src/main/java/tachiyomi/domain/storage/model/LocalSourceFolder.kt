package tachiyomi.domain.storage.model

import com.hippo.unifile.UniFile

/**
 * A folder series are read from.
 *
 * @param uri the URI the folder was chosen with; for the "local" folder, the storage location's.
 * @param directory the folder, null while it can't be reached.
 * @param isDefault whether it is the "local" folder of the storage location.
 */
data class LocalSourceFolder(
    val uri: String,
    val directory: UniFile?,
    val isDefault: Boolean,
)
