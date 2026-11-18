package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class GetManga(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(id: Long): Manga? {
        return try {
            mangaRepository.getMangaById(id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    suspend fun await(url: String, sourceId: Long): Manga? {
        return try {
            mangaRepository.getMangaByUrlAndSourceId(url, sourceId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    fun subscribe(id: Long): Flow<Manga> {
        return mangaRepository.getMangaByIdAsFlow(id)
    }
}
