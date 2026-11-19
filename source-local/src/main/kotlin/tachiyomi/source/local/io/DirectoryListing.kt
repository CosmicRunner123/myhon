package tachiyomi.source.local.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.storage.hasAllFilesAccess
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.net.URLDecoder

/**
 * A subfolder or comic file of a folder.
 *
 * @param uri the URI it was listed with, which identifies it as long as the folder's access lasts.
 * @param lastModified 0 when unknown.
 * @param size in bytes, 0 when unknown.
 */
data class DirectoryEntry(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val size: Long,
) {
    /**
     * The name it is shown and ordered by: a file's name without its extension, a folder's whole name.
     */
    val title: String
        get() = if (isDirectory) name else name.substringBeforeLast('.')
}

/**
 * The order a folder's entries are shown in: folders, then files, each in natural order of their title. The reader
 * goes through a folder's files in this order too, so the next chapter is the next file shown.
 */
internal val directoryEntryOrder: Comparator<DirectoryEntry> = compareBy<DirectoryEntry> { !it.isDirectory }
    .thenComparator { e1, e2 -> e1.title.compareToCaseInsensitiveNaturalOrder(e2.title) }
    // Names are unique in a folder, so this makes the order total and the same on every listing
    .thenBy { it.name }

/**
 * Whether [name] is that of a comic file: an archive or an epub that isn't hidden.
 */
internal fun isComicFile(name: String): Boolean {
    return !name.startsWith('.') && (Archive.isSupported(name) || isEpub(name))
}

internal fun isEpub(name: String): Boolean {
    return name.substringAfterLast('.').equals("epub", ignoreCase = true)
}

/**
 * The name, with its extension, of the comic file listed with [uri], or null when [uri] doesn't tell it, as an opaque
 * document id such as `msf:123` doesn't.
 */
fun comicFileName(uri: String): String? {
    val name = if (uri.startsWith("file://")) {
        decodeUriPart(uri.substringAfterLast('/')) ?: return null
    } else {
        // A readable document id is a path, such as primary:Comics/Akira v01.cbz or primary:Akira v01.cbz at the root
        val documentId = decodeUriPart(uri.substringAfterLast("/document/", missingDelimiterValue = "")) ?: return null
        if ('/' in documentId) documentId.substringAfterLast('/') else documentId.substringAfter(':')
    }
    // Only comic files are listed, so a name that isn't one comes from an opaque id
    return name.takeIf(::isComicFile)
}

/**
 * [part] of a URI with its escapes decoded, or null when it has a malformed one.
 */
fun decodeUriPart(part: String): String? {
    return try {
        // URLDecoder decodes form data, where a + stands for a space, but in a URI it is a plus
        URLDecoder.decode(part.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        // A % that doesn't start an escape
        null
    }
}

/**
 * The subfolders and comic files of the folder [uri], in [directoryEntryOrder], or null when it can't be read.
 * [uri] is a file URI or a document URI of a folder tree, such as a [com.hippo.unifile.UniFile]'s.
 */
suspend fun listDirectory(context: Context, uri: Uri): List<DirectoryEntry>? = withContext(Dispatchers.IO) {
    val entries = try {
        when {
            uri.scheme != ContentResolver.SCHEME_FILE -> listDocuments(context, uri)
            // Without it, Android 11+ lists a folder without other apps' files instead of failing
            context.hasAllFilesAccess() -> listFiles(uri)
            else -> null
        }
    } catch (e: Exception) {
        // A SecurityException once the folder's access is released
        logcat(LogPriority.WARN, e) { "Failed to list $uri" }
        null
    }
    entries?.let(::shownEntries)
}

internal fun shownEntries(entries: List<DirectoryEntry>): List<DirectoryEntry> {
    return entries
        .filter { if (it.isDirectory) !it.name.startsWith('.') else isComicFile(it.name) }
        .sortedWith(directoryEntryOrder)
}

private fun listFiles(uri: Uri): List<DirectoryEntry>? {
    val directory = File(uri.path ?: return null)
    return directory.listFiles()?.map { file ->
        DirectoryEntry(
            uri = Uri.fromFile(file).toString(),
            name = file.name,
            isDirectory = file.isDirectory,
            lastModified = file.lastModified(),
            size = file.length(),
        )
    }
}

// One query for the whole folder, where UniFile makes one per file and property and answers a failure with no files
private fun listDocuments(context: Context, uri: Uri): List<DirectoryEntry>? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
    return context.contentResolver.query(childrenUri, DOCUMENT_COLUMNS, null, null, null)?.use { cursor ->
        val entries = ArrayList<DirectoryEntry>(cursor.count)
        while (cursor.moveToNext()) {
            entries += DirectoryEntry(
                uri = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)).toString(),
                name = cursor.getString(1),
                isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                lastModified = cursor.getLong(3),
                size = cursor.getLong(4),
            )
        }
        entries
    }
}

private val DOCUMENT_COLUMNS = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    DocumentsContract.Document.COLUMN_SIZE,
)
