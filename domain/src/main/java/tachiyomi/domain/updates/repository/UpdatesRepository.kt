package tachiyomi.domain.updates.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations
import kotlin.time.Instant

interface UpdatesRepository {

    suspend fun awaitWithRead(read: Boolean, after: Instant, limit: Long): List<UpdatesWithRelations>

    fun subscribeWithRead(read: Boolean, after: Instant, limit: Long): Flow<List<UpdatesWithRelations>>
}
