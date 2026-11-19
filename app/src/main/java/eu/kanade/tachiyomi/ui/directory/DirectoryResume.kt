package eu.kanade.tachiyomi.ui.directory

import android.content.Context
import androidx.core.net.toUri
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.history.model.DirectoryHistory
import tachiyomi.i18n.MR
import tachiyomi.source.local.io.DirectoryEntry
import tachiyomi.source.local.io.listDirectory

/**
 * The file to go on reading from where [history] was left: that file if it wasn't read to the end, otherwise the file
 * after it in its folder, null when there is none. Throws when the folder can't be read or no longer has the file.
 */
suspend fun findFileToResume(context: Context, history: DirectoryHistory): DirectoryEntry? {
    val files = listDirectory(context, history.folderUri.toUri())
        ?.filterNot { it.isDirectory }
        ?: throw Exception(context.stringResource(MR.strings.directory_folder_unreadable))
    val index = files.indexOfFirst { it.uri == history.uri }
    if (index == -1) throw Exception(context.stringResource(MR.strings.chapter_not_found))
    return if (history.read) files.getOrNull(index + 1) else files[index]
}
