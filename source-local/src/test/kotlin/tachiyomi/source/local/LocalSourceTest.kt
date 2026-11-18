package tachiyomi.source.local

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class LocalSourceTest {

    @Test
    fun `series of every base directory are listed`() {
        val listing = mergeSeriesNames(listOf(listOf("One", "Two"), listOf("Three")))

        listing shouldBe SeriesListing(listOf("One", "Two", "Three"), complete = true)
    }

    @Test
    fun `a series name in several base directories is one series`() {
        val listing = mergeSeriesNames(listOf(listOf("One", "Two"), listOf("Two", "Three"), listOf("One")))

        listing shouldBe SeriesListing(listOf("One", "Two", "Three"), complete = true)
    }

    @Test
    fun `series names that differ in case are one series named as in the first base directory`() {
        val listing = mergeSeriesNames(listOf(listOf("One", "two"), listOf("one", "Two")))

        listing shouldBe SeriesListing(listOf("One", "two"), complete = true)
    }

    @Test
    fun `a base directory that could not be listed makes the listing incomplete`() {
        val listing = mergeSeriesNames(listOf(listOf("One"), null, listOf("Two")))

        listing shouldBe SeriesListing(listOf("One", "Two"), complete = false)
    }

    @Test
    fun `empty base directories are a complete listing without series`() {
        mergeSeriesNames(listOf(emptyList(), emptyList())) shouldBe SeriesListing(emptyList(), complete = true)
    }

    @Test
    fun `the chapters of a series are the union of its folders`() {
        val files = mergeSeriesFiles(
            listOf(
                listOf(SeriesFile("first", "Ch. 1.cbz"), SeriesFile("first", "Ch. 2.cbz")),
                listOf(SeriesFile("second", "Ch. 3.cbz")),
            ),
            SeriesFile::name,
        )

        files shouldContainExactly listOf(
            SeriesFile("first", "Ch. 1.cbz"),
            SeriesFile("first", "Ch. 2.cbz"),
            SeriesFile("second", "Ch. 3.cbz"),
        )
    }

    @Test
    fun `a file name in several folders is taken from the first folder`() {
        val files = mergeSeriesFiles(
            listOf(
                listOf(SeriesFile("first", "Ch. 2.cbz")),
                listOf(SeriesFile("second", "Ch. 1.cbz"), SeriesFile("second", "Ch. 2.cbz")),
                listOf(SeriesFile("third", "Ch. 1.cbz"), SeriesFile("third", "ch. 2.cbz")),
            ),
            SeriesFile::name,
        )

        files shouldContainExactly listOf(
            SeriesFile("first", "Ch. 2.cbz"),
            SeriesFile("second", "Ch. 1.cbz"),
            SeriesFile("third", "ch. 2.cbz"),
        )
    }

    @Test
    fun `folders without files give no files`() {
        mergeSeriesFiles(listOf(emptyList<SeriesFile>(), emptyList()), SeriesFile::name).shouldBeEmpty()
    }

    private data class SeriesFile(val folder: String, val name: String)
}
