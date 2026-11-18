package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.model.DirectoryHistory
import tachiyomi.domain.history.repository.DirectoryHistoryRepository

@Inject
class UpsertDirectoryHistory(
    private val repository: DirectoryHistoryRepository,
) {

    suspend fun await(history: DirectoryHistory) {
        repository.upsert(history)
    }
}
