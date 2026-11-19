package tachiyomi.source.local.io

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
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

    @Test
    fun `the name of a file is the last segment of its file URI, decoded`() {
        comicFileName("file:///storage/emulated/0/Comics/A%20B/x%201.cbz") shouldBe "x 1.cbz"
        comicFileName("file:///storage/emulated/0/%E3%81%82%23%25%2B.cbz") shouldBe "あ#%+.cbz"
        comicFileName("file:///storage/emulated/0/A+B.cbz") shouldBe "A+B.cbz"
    }

    @Test
    fun `the name of a document is the last segment of its document id`() {
        comicFileName(
            "content://com.android.externalstorage.documents/tree/primary%3AComics" +
                "/document/primary%3AComics%2FA%2Fx.cbz",
        ) shouldBe "x.cbz"
        comicFileName(
            "content://com.android.externalstorage.documents/tree/primary%3AComics" +
                "/document/primary%3AComics%2F%E3%81%82%2F%E3%81%82%20%23%25%2B.cbz",
        ) shouldBe "あ #%+.cbz"
        comicFileName(
            "content://com.android.providers.downloads.documents/tree/downloads" +
                "/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fx%201.cbz",
        ) shouldBe "x 1.cbz"
    }

    @Test
    fun `the name of a document at the root of a volume follows its volume`() {
        comicFileName(
            "content://com.android.externalstorage.documents/tree/1234-5678%3A/document/1234-5678%3Ax.cbz",
        ) shouldBe "x.cbz"
    }

    @Test
    fun `a URI that doesn't tell the name of a comic file has none`() {
        comicFileName("content://com.android.providers.downloads.documents/tree/downloads/document/msf%3A42")
            .shouldBeNull()
        comicFileName("content://com.android.providers.downloads.documents/document/1234").shouldBeNull()
        comicFileName("content://media/external/file/12").shouldBeNull()
        comicFileName("file:///storage/emulated/0/Comics/A").shouldBeNull()
    }

    @Test
    fun `a malformed URI has no name`() {
        comicFileName("file:///storage/emulated/0/100%.cbz").shouldBeNull()
        comicFileName("file:///storage/emulated/0/%zz.cbz").shouldBeNull()
        comicFileName("content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3Ax%2.cbz")
            .shouldBeNull()
        comicFileName("file://").shouldBeNull()
        comicFileName("").shouldBeNull()
    }

    private fun file(name: String) = DirectoryEntry("file:///Comics/$name", name, false, 1, 1)

    private fun folder(name: String) = DirectoryEntry("file:///Comics/$name", name, true, 1, 0)
}
