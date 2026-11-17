package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.Source

interface SourceManager {

    suspend fun get(sourceKey: Long): Source?

    suspend fun getOrStub(sourceKey: Long): Source

    suspend fun getAll(): List<Source>
}
