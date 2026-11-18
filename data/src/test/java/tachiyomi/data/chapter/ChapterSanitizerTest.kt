package tachiyomi.data.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.data.chapter.ChapterSanitizer.sanitize

@Execution(ExecutionMode.CONCURRENT)
class ChapterSanitizerTest {

    @Test
    fun `the series title and the separators after it are removed`() {
        " Akira - Vol. 1 ".sanitize("Akira") shouldBe "Vol. 1"
    }

    @Test
    fun `a name that is only the series title is kept`() {
        "Akira".sanitize("Akira") shouldBe "Akira"
    }
}
