package tachiyomi.domain.history.model

import kotlin.time.Instant

/**
 * A comic file read in directory view, which keeps no series or chapters: the file is known by its URI and by what its
 * folder listed.
 *
 * @param uri the file's URI as its folder listed it.
 * @param name the file's name with its extension, as listed. Older entries can hold its name without extension, or
 * the name of its chapter from when directory view kept hidden series.
 * @param lastModified as listed, 0 when unknown.
 * @param size in bytes as listed, 0 when unknown.
 */
data class DirectoryHistory(
    val uri: String,
    val folderUri: String,
    val folderName: String,
    val name: String,
    val lastModified: Long,
    val size: Long,
    val lastPageRead: Int,
    val read: Boolean,
    val lastRead: Instant,
)
