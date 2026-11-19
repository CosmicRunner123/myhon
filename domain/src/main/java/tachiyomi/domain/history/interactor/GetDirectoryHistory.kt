package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.DirectoryHistory
import tachiyomi.domain.history.repository.DirectoryHistoryRepository

@Inject
class GetDirectoryHistory(
    private val repository: DirectoryHistoryRepository,
) {

    suspend fun await(uri: String): DirectoryHistory? {
        return repository.getByUri(uri)
    }

    /** The files read in the folder [folderUri]. */
    fun subscribeByFolder(folderUri: String): Flow<List<DirectoryHistory>> {
        return repository.getByFolderUriAsFlow(folderUri)
    }

    fun subscribeLast(): Flow<DirectoryHistory?> {
        return repository.getLastAsFlow()
    }

    /** The files read, read last first. */
    fun subscribe(): Flow<List<DirectoryHistory>> {
        return repository.getHistory()
    }
}
