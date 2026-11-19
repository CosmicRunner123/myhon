package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.repository.DirectoryHistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class ResetViewerFlags(
    private val mangaRepository: MangaRepository,
    private val directoryHistoryRepository: DirectoryHistoryRepository,
) {

    suspend fun await(): Boolean {
        val mangaReset = mangaRepository.resetViewerFlags()
        val foldersReset = directoryHistoryRepository.resetFolderViewerFlags()
        return mangaReset && foldersReset
    }
}
