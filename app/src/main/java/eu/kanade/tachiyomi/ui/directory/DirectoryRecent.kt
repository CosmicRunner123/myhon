package eu.kanade.tachiyomi.ui.directory

import tachiyomi.source.local.io.decodeUriPart

/**
 * Where the folder [folderUri] is, in words: the name of the volume of [volumes] that holds it, then its path in that
 * volume. Its whole path when no volume holds it.
 */
fun recentFolderLocation(folderUri: String, volumes: List<DirectoryVolume>): String {
    val location = locateFolder(folderUri, volumes) ?: return readablePath(folderUri)
    return (listOf(location.volume.name) + location.names).joinToString("/")
}

/**
 * The path that shows the folder [folderUri] as if it had been browsed to from the top level: the volume of [volumes]
 * that holds it, then each folder down to it. Only the folder when no volume holds it.
 */
fun recentFolderPath(
    folderUri: String,
    folderName: String,
    volumes: List<DirectoryVolume>,
): List<DirectoryViewModel.DirectoryFolder> {
    val location = locateFolder(folderUri, volumes)
        ?: return listOf(DirectoryViewModel.DirectoryFolder(folderUri, folderName))
    val folders = location.names
        .scan(location.volumePath) { parent, name -> "$parent/$name" }
        .drop(1)
        .zip(location.names) { path, name -> DirectoryViewModel.DirectoryFolder(fileUri(path), name) }
    return listOf(DirectoryViewModel.DirectoryFolder(location.volume.uri, location.volume.name)) + folders
}

/**
 * @param names the names of the folders from the root of [volume] down to the folder, none for the root itself.
 */
private class FolderLocation(val volume: DirectoryVolume, val volumePath: String, val names: List<String>)

private fun locateFolder(folderUri: String, volumes: List<DirectoryVolume>): FolderLocation? {
    val folderPath = filePath(folderUri) ?: return null
    return volumes.firstNotNullOfOrNull { volume ->
        val volumePath = filePath(volume.uri)
        when {
            volumePath == null -> null
            folderPath == volumePath -> FolderLocation(volume, volumePath, emptyList())
            folderPath.startsWith("$volumePath/") -> {
                FolderLocation(volume, volumePath, folderPath.removePrefix("$volumePath/").split('/'))
            }
            else -> null
        }
    }
}

/**
 * The absolute path of the file URI [uri], null for another URI.
 */
private fun filePath(uri: String): String? {
    return if (uri.startsWith(FILE_URI_PREFIX)) decodeUriPart(uri.removePrefix(FILE_URI_PREFIX)) else null
}

// The document id of a document or tree URI is its last segment, and a path for most storage, as primary:Comics/Akira
private fun readablePath(uri: String): String {
    val path = if (uri.startsWith(FILE_URI_PREFIX)) uri.removePrefix(FILE_URI_PREFIX) else uri.substringAfterLast('/')
    return decodeUriPart(path) ?: path
}

// The form Uri.fromFile gives and the listing uses, so the folders opened are the ones browsed to and read in
private fun fileUri(path: String): String {
    return buildString {
        append(FILE_URI_PREFIX)
        path.encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in UNESCAPED_CHARS) {
                append(char)
            } else {
                append('%').append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0xF])
            }
        }
    }
}

private const val FILE_URI_PREFIX = "file://"

// Those Uri.encode leaves as they are in a path
private const val UNESCAPED_CHARS = "_-!.~'()*/"

private const val HEX_DIGITS = "0123456789ABCDEF"
