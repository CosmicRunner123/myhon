package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.interactor.GetManga
import kotlin.math.max

@Inject
class GetNextChapters(
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getManga: GetManga,
) {

    suspend fun await(mangaId: Long, fromChapterId: Long): List<Chapter> {
        val manga = getManga.await(mangaId) ?: return emptyList()
        val chapters = getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)
            .sortedWith(getChapterSort(manga, sortDescending = false))
        val currChapterIndex = chapters.indexOfFirst { it.id == fromChapterId }
        val nextChapters = chapters.subList(max(0, currChapterIndex), chapters.size)

        // The "next chapter" is either:
        // - The current chapter if it isn't completely read
        // - The chapters after the current chapter if the current one is completely read
        val fromChapter = chapters.getOrNull(currChapterIndex)
        return if (fromChapter != null && !fromChapter.read) {
            nextChapters
        } else {
            nextChapters.drop(1)
        }
    }
}
