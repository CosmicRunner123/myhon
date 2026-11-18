package tachiyomi.source.local.io

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class DirectoryListingTest {

    @Test
    fun `archives and epubs are comic files`() {
        listOf("a.zip", "a.cbz", "a.rar", "a.cbr", "a.7z", "a.cb7", "a.tar", "a.cbt", "a.epub")
            .filterNot(::isComicFile)
            .shouldContainExactly()
    }

    @Test
    fun `extensions are matched ignoring case`() {
        listOf("a.CBZ", "a.Zip", "a.EPUB", "a.Cb7").filterNot(::isComicFile).shouldContainExactly()
    }

    @Test
    fun `other files are not comic files`() {
        listOf("a.jpg", "a.pdf", "a.txt", "ComicInfo.xml", "a.cbz.part", "a")
            .filter(::isComicFile)
            .shouldContainExactly()
    }

    @Test
    fun `hidden files are not comic files`() {
        isComicFile(".a.cbz") shouldBe false
    }

    @Test
    fun `a listing shows folders, whatever their names, and comic files, but nothing hidden`() {
        val shown = shownEntries(
            listOf(
                file("Ch. 1.cbz"),
                file("cover.jpg"),
                file(".Ch. 2.cbz"),
                folder("Extras"),
                folder("Ch. 3.cbz"),
                folder(".thumbnails"),
                file("Ch. 4.epub"),
            ),
        )

        shown.map { it.name } shouldContainExactly listOf("Ch. 3.cbz", "Extras", "Ch. 1.cbz", "Ch. 4.epub")
    }

    @Test
    fun `folders come before files, each in natural order ignoring case`() {
        val shown = shownEntries(
            listOf(
                file("ch 10.cbz"),
                folder("Vol 10"),
                file("Ch 2.cbz"),
                folder("vol 9"),
                file("Ch 1.zip"),
            ),
        )

        shown.map { it.name } shouldContainExactly listOf("vol 9", "Vol 10", "Ch 1.zip", "Ch 2.cbz", "ch 10.cbz")
    }

    @Test
    fun `files are ordered by their names without extension`() {
        val shown = shownEntries(listOf(file("Vol 1 - Extra.cbz"), file("Vol 1.cbz")))

        shown.map { it.name } shouldContainExactly listOf("Vol 1.cbz", "Vol 1 - Extra.cbz")
    }

    @Test
    fun `files with the same name without extension are ordered by their whole names`() {
        val shown = shownEntries(listOf(file("Vol 1.zip"), file("Vol 1.cbz"), file("Vol 1.epub")))

        shown.map { it.name } shouldContainExactly listOf("Vol 1.cbz", "Vol 1.epub", "Vol 1.zip")
    }

    @Test
    fun `a file's title is its name without extension and a folder's is its whole name`() {
        file("Vol. 1.cbz").title shouldBe "Vol. 1"
        folder("Vol. 1").title shouldBe "Vol. 1"
    }

    private fun file(name: String) = DirectoryEntry("file:///Comics/$name", name, false, 1, 1)

    private fun folder(name: String) = DirectoryEntry("file:///Comics/$name", name, true, 1, 0)
}
