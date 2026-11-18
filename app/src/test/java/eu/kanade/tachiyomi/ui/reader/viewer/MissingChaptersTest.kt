package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter

@Execution(ExecutionMode.CONCURRENT)
class MissingChaptersTest {

    @Test
    fun `a gap between chapter numbers counts the chapters missing`() {
        calculateChapterGap(readerChapter(10.0), readerChapter(7.0)) shouldBe 2
    }

    @Test
    fun `numbers that are not chapter numbers have no gap`() {
        calculateChapterGap(readerChapter(10.0, hasChapterNumber = false), readerChapter(7.0)) shouldBe 0
        calculateChapterGap(readerChapter(10.0), readerChapter(7.0, hasChapterNumber = false)) shouldBe 0
    }

    private fun readerChapter(number: Double, hasChapterNumber: Boolean = true) = ReaderChapter(
        Chapter.create().copy(chapterNumber = number),
        hasChapterNumber = hasChapterNumber,
    )
}
