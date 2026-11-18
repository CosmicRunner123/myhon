package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.DirectorySource
import tachiyomi.source.local.LocalSource

@Execution(ExecutionMode.CONCURRENT)
class SyncChaptersWithSourceTest {

    @Test
    fun `a new file in a directory view folder does not take the state of a removed file with the same number`() =
        runTest {
            val added = replaceReadChapter(DirectorySource.ID)

            added.read shouldBe false
            added.bookmark shouldBe false
        }

    @Test
    fun `a new chapter takes the state of a removed chapter with the same number`() = runTest {
        val added = replaceReadChapter(LocalSource.ID)

        added.read shouldBe true
        added.bookmark shouldBe true
    }

    /** Syncs a list holding only "Superman 001" for a series whose read and bookmarked "Batman 001" is gone. */
    private suspend fun replaceReadChapter(source: Long): Chapter {
        val manga = Manga.create().copy(id = 1, source = source, title = "Downloads")
        val removed = Chapter.create().copy(
            id = 1,
            mangaId = manga.id,
            url = "file:///Downloads/Batman%20001.cbz",
            name = "Batman 001",
            chapterNumber = 1.0,
            read = true,
            bookmark = true,
        )
        val sourceChapter = SChapter.create().apply {
            url = "file:///Downloads/Superman%20001.cbz"
            name = "Superman 001"
            date_upload = 1L
        }

        val toAdd = slot<List<Chapter>>()
        val chapterRepository = mockk<ChapterRepository> {
            coEvery { getChapterByMangaId(manga.id) } returns listOf(removed)
            coEvery { updateFromRemote(listOf(removed.id), capture(toAdd), emptyList()) } answers { toAdd.captured }
        }
        val getExcludedScanlators = mockk<GetExcludedScanlators> {
            coEvery { await(manga.id) } returns emptySet()
        }
        val libraryPreferences = mockk<LibraryPreferences> {
            every { markDuplicateReadChapterAsRead.get() } returns emptySet()
        }
        val syncChaptersWithSource = SyncChaptersWithSource(
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = mockk<UpdateManga>(relaxed = true),
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )

        syncChaptersWithSource.await(listOf(sourceChapter), manga)

        return toAdd.captured.single().also { it.chapterNumber shouldBe 1.0 }
    }
}
