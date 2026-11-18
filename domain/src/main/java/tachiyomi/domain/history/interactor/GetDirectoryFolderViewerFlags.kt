package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.repository.DirectoryHistoryRepository

@Inject
class GetDirectoryFolderViewerFlags(
    private val repository: DirectoryHistoryRepository,
) {

    /** The reader settings of the folder [folderUri] in the layout of a series' viewer flags, 0 when none were set. */
    suspend fun await(folderUri: String): Long {
        return repository.getFolderViewerFlags(folderUri)
    }
}
