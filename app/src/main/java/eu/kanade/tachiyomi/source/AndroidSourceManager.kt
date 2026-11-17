package eu.kanade.tachiyomi.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import java.util.concurrent.ConcurrentHashMap

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidSourceManager(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val sourceRepository: StubSourceRepository,
    private val localSource: LocalSource,
) : SourceManager {

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    init {
        scope.launch {
            sourceRepository.subscribeAll()
                .collectLatest { sources ->
                    sources.forEach {
                        stubSourcesMap[it.id] = it
                    }
                }
        }
    }

    override suspend fun get(sourceKey: Long): Source? {
        return localSource.takeIf { sourceKey == LocalSource.ID }
    }

    override suspend fun getOrStub(sourceKey: Long): Source {
        return get(sourceKey) ?: stubSourcesMap.getOrPut(sourceKey) {
            createStubSource(sourceKey)
        }
    }

    private suspend fun createStubSource(id: Long): StubSource {
        return sourceRepository.getStubSource(id) ?: StubSource(id = id, lang = "", name = "")
    }
}
