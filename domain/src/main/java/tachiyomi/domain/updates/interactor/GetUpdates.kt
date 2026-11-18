package tachiyomi.domain.updates.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository
import kotlin.time.Instant

@Inject
class GetUpdates(
    private val repository: UpdatesRepository,
) {

    suspend fun await(read: Boolean, after: Instant): List<UpdatesWithRelations> {
        return repository.awaitWithRead(read, after, limit = 500)
    }

    fun subscribe(read: Boolean, after: Instant): Flow<List<UpdatesWithRelations>> {
        return repository.subscribeWithRead(read, after, limit = 500)
    }
}
