package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.repository.DirectoryHistoryRepository

@Inject
class SetDirectoryFolderViewerFlags(
    private val repository: DirectoryHistoryRepository,
) {

    /** Sets the bits of [mask] in the viewer flags of the folder [folderUri] to those of [flag]. */
    suspend fun await(folderUri: String, flag: Long, mask: Long) {
        repository.setFolderViewerFlags(folderUri, flag, mask)
    }
}
