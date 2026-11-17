package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.model.StubSource

interface SourceManager {

    val sources: Flow<List<Source>>

    suspend fun get(sourceKey: Long): Source?

    suspend fun getOrStub(sourceKey: Long): Source

    suspend fun getAll(): List<Source>

    suspend fun getStubSources(): List<StubSource>
}
