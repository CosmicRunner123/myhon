package tachiyomi.source.local

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.source.local.io.DirectoryEntry

@Execution(ExecutionMode.CONCURRENT)
class DirectorySourceTest {

    @Test
    fun `chapters are the files of a folder, last first`() {
        val chapters = directoryChapters(
            listOf(
                DirectoryEntry("uri/Extras", "Extras", true, 5, 0),
                DirectoryEntry("uri/Ch 2", "Ch 2.cbz", false, 2, 10),
                DirectoryEntry("uri/Ch 10", "Ch 10.epub", false, 3, 10),
                DirectoryEntry("uri/Ch 1", "Ch 1.zip", false, 1, 10),
            ),
        )

        chapters.map { it.url } shouldContainExactly listOf("uri/Ch 10", "uri/Ch 2", "uri/Ch 1")
        chapters.map { it.name } shouldContainExactly listOf("Ch 10", "Ch 2", "Ch 1")
        chapters.map { it.date_upload } shouldContainExactly listOf(3L, 2L, 1L)
    }

    @Test
    fun `chapters are the reverse of the order the folder shows its files in`() {
        val chapters = directoryChapters(
            listOf(
                DirectoryEntry("uri/b", "Vol 1.zip", false, 1, 10),
                DirectoryEntry("uri/a", "Vol 1 - Extra.cbz", false, 1, 10),
                DirectoryEntry("uri/c", "Vol 1.cbz", false, 1, 10),
            ),
        )

        chapters.map { it.url } shouldContainExactly listOf("uri/a", "uri/b", "uri/c")
    }

    @Test
    fun `a chapter whose file has no modification date gets a fixed one rather than none`() {
        val chapters = directoryChapters(listOf(DirectoryEntry("uri/a", "a.cbz", false, 0, 10)))

        chapters.map { it.date_upload } shouldContainExactly listOf(1L)
    }

    @Test
    fun `a folder without files has no chapters`() {
        directoryChapters(listOf(DirectoryEntry("uri/a", "a", true, 1, 0))).shouldContainExactly()
    }
}
