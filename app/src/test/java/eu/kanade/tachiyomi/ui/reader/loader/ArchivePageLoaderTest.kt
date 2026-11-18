package eu.kanade.tachiyomi.ui.reader.loader

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class ArchivePageLoaderTest {

    private val isImage: (String) -> Boolean = { it.endsWith(".jpg") || it.endsWith(".png") }

    @Test
    fun `pages are in natural order, ignoring case`() {
        archivePageNames(listOf("page10.jpg", "Page2.jpg", "page1.png", "PAGE3.jpg"), isImage).toList()
            .shouldContainExactly("page1.png", "Page2.jpg", "PAGE3.jpg", "page10.jpg")
    }

    @Test
    fun `audio files and files that are not images are not pages`() {
        val tested = mutableListOf<String>()
        archivePageNames(listOf("2.jpg", "music.mp3", "ComicInfo.xml", "1.jpg")) {
            tested += it
            isImage(it)
        }
            .toList()
            .shouldContainExactly("1.jpg", "2.jpg")
        tested shouldNotContain "music.mp3"
    }

    @Test
    fun `the first page only tests the files sorted before it`() {
        val tested = mutableListOf<String>()
        archivePageNames(listOf("c.png", "b.jpg", "a.txt", "d.jpg")) {
            tested += it
            isImage(it)
        }
            .firstOrNull() shouldBe "b.jpg"
        tested.shouldContainExactly("a.txt", "b.jpg")
    }

    @Test
    fun `an archive without images has no pages`() {
        archivePageNames(listOf("readme.txt", "track.flac"), isImage).toList().shouldBeEmpty()
        archivePageNames(emptyList(), isImage).firstOrNull() shouldBe null
    }
}
