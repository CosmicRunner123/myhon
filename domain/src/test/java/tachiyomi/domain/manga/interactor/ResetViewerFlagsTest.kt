package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.repository.DirectoryHistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository

class ResetViewerFlagsTest {

    private lateinit var mangaRepository: MangaRepository
    private lateinit var directoryHistoryRepository: DirectoryHistoryRepository
    private lateinit var resetViewerFlags: ResetViewerFlags

    @BeforeEach
    fun beforeEach() {
        mangaRepository = mockk()
        directoryHistoryRepository = mockk()

        resetViewerFlags = ResetViewerFlags(mangaRepository, directoryHistoryRepository)
    }

    @Test
    fun `Resets both series and directory folder flags`() = runTest {
        coEvery { mangaRepository.resetViewerFlags() } returns true
        coEvery { directoryHistoryRepository.resetFolderViewerFlags() } returns true

        resetViewerFlags.await() shouldBe true

        coVerify(exactly = 1) { mangaRepository.resetViewerFlags() }
        coVerify(exactly = 1) { directoryHistoryRepository.resetFolderViewerFlags() }
    }

    @Test
    fun `Resets directory folder flags even when the series reset fails`() = runTest {
        coEvery { mangaRepository.resetViewerFlags() } returns false
        coEvery { directoryHistoryRepository.resetFolderViewerFlags() } returns true

        resetViewerFlags.await() shouldBe false

        coVerify(exactly = 1) { directoryHistoryRepository.resetFolderViewerFlags() }
    }

    @Test
    fun `Reports failure when the directory folder reset fails`() = runTest {
        coEvery { mangaRepository.resetViewerFlags() } returns true
        coEvery { directoryHistoryRepository.resetFolderViewerFlags() } returns false

        resetViewerFlags.await() shouldBe false
    }
}
