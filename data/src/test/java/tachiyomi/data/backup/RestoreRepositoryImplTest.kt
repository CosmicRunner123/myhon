package tachiyomi.data.backup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter
import kotlin.time.Instant

@Execution(ExecutionMode.CONCURRENT)
class RestoreRepositoryImplTest {

    @Test
    fun `a chapter the backup has unchanged keeps its earlier fetch date`() {
        val merged = mergeChapter(backup.copy(dateFetch = Instant.fromEpochMilliseconds(100)), stored)

        merged?.id shouldBe stored.id
        merged?.dateFetch shouldBe Instant.fromEpochMilliseconds(100)
    }

    @Test
    fun `a chapter the backup has unchanged and fetched later is skipped`() {
        mergeChapter(backup.copy(dateFetch = Instant.fromEpochMilliseconds(2_000)), stored) shouldBe null
    }

    @Test
    fun `a chapter with progress in the backup keeps the stored fetch date when it is earlier`() {
        val merged = mergeChapter(
            backup.copy(read = true, dateFetch = Instant.fromEpochMilliseconds(2_000)),
            stored,
        )

        merged?.read shouldBe true
        merged?.dateFetch shouldBe stored.dateFetch
    }

    private val stored = Chapter.create().copy(
        id = 7,
        mangaId = 3,
        url = "Series/Chapter 1.cbz",
        name = "Chapter 1",
        dateFetch = Instant.fromEpochMilliseconds(1_000),
    )

    private val backup = stored.copy(id = -1, mangaId = 3)
}
