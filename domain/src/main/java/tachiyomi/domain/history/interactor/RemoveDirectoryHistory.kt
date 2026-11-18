package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.repository.DirectoryHistoryRepository

@Inject
class RemoveDirectoryHistory(
    private val repository: DirectoryHistoryRepository,
) {

    suspend fun awaitAll(): Boolean {
        return repository.deleteAll()
    }

    suspend fun await(uri: String) {
        repository.delete(uri)
    }
}
