package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.source.model.StubSource

interface SourceManager {

    suspend fun get(sourceKey: Long): Source?

    suspend fun getOrStub(sourceKey: Long): Source

    suspend fun getAll(): List<Source>

    suspend fun getStubSources(): List<StubSource>
}
