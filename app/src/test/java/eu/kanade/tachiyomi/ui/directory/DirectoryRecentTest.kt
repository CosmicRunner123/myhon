package eu.kanade.tachiyomi.ui.directory

import eu.kanade.tachiyomi.ui.directory.DirectoryViewModel.DirectoryFolder
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class DirectoryRecentTest {

    private val internal = volume("file:///storage/emulated/0", "Internal shared storage", isPrimary = true)
    private val sdCard = volume("file:///storage/1234-ABCD", "SD card")
    private val volumes = listOf(internal, sdCard)

    @Test
    fun `location is the volume name and the path in it`() {
        recentFolderLocation("file:///storage/emulated/0/Comics/Otomo/Akira", volumes) shouldBe
            "Internal shared storage/Comics/Otomo/Akira"
        recentFolderLocation("file:///storage/1234-ABCD/Manga", volumes) shouldBe "SD card/Manga"
    }

    @Test
    fun `location of a volume root is the volume name`() {
        recentFolderLocation("file:///storage/1234-ABCD", volumes) shouldBe "SD card"
    }

    @Test
    fun `location is decoded`() {
        recentFolderLocation("file:///storage/emulated/0/My%20Comics/C%2B%2B%20%E6%BC%AB%E7%94%BB", volumes) shouldBe
            "Internal shared storage/My Comics/C++ 漫画"
    }

    @Test
    fun `location off the volumes is the whole path`() {
        recentFolderLocation("file:///storage/9999-9999/My%20Comics", volumes) shouldBe "/storage/9999-9999/My Comics"
        // Only starts like the path of the internal storage
        recentFolderLocation("file:///storage/emulated/01/Comics", volumes) shouldBe "/storage/emulated/01/Comics"
        recentFolderLocation("file:///storage/emulated/0/Comics", emptyList()) shouldBe "/storage/emulated/0/Comics"
    }

    @Test
    fun `location of a document folder is its document id`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AComics" +
            "/document/primary%3AComics%2FAkira%20Vol.%201"
        recentFolderLocation(uri, volumes) shouldBe "primary:Comics/Akira Vol. 1"
        recentFolderLocation("content://com.android.externalstorage.documents/tree/primary%3AComics", volumes) shouldBe
            "primary:Comics"
    }

    @Test
    fun `location with a malformed escape is left encoded`() {
        recentFolderLocation("file:///storage/emulated/0/100%", volumes) shouldBe "/storage/emulated/0/100%"
    }

    @Test
    fun `path goes from the volume down to the folder`() {
        recentFolderPath("file:///storage/emulated/0/Comics/Otomo/Akira", "Akira", volumes).shouldContainExactly(
            DirectoryFolder("file:///storage/emulated/0", "Internal shared storage"),
            DirectoryFolder("file:///storage/emulated/0/Comics", "Comics"),
            DirectoryFolder("file:///storage/emulated/0/Comics/Otomo", "Otomo"),
            DirectoryFolder("file:///storage/emulated/0/Comics/Otomo/Akira", "Akira"),
        )
    }

    @Test
    fun `path of a volume root is the volume`() {
        recentFolderPath("file:///storage/1234-ABCD", "1234-ABCD", volumes)
            .shouldContainExactly(DirectoryFolder("file:///storage/1234-ABCD", "SD card"))
    }

    @Test
    fun `path folders are named and encoded like listed ones`() {
        val uri = "file:///storage/1234-ABCD/My%20Comics/" +
            "C%2B%2B%20%5Bv1%5D%20%23%20100%25%20(_-!.~'*)%20%E6%BC%AB%E7%94%BB%20%F0%9F%93%9A"
        recentFolderPath(uri, "C++", volumes).shouldContainExactly(
            DirectoryFolder("file:///storage/1234-ABCD", "SD card"),
            DirectoryFolder("file:///storage/1234-ABCD/My%20Comics", "My Comics"),
            DirectoryFolder(uri, "C++ [v1] # 100% (_-!.~'*) 漫画 📚"),
        )
    }

    @Test
    fun `path off the volumes is the folder alone`() {
        recentFolderPath("file:///storage/9999-9999/Comics", "Comics", volumes)
            .shouldContainExactly(DirectoryFolder("file:///storage/9999-9999/Comics", "Comics"))
        val documentUri = "content://com.android.externalstorage.documents/tree/primary%3AComics" +
            "/document/primary%3AComics%2FAkira"
        recentFolderPath(documentUri, "Akira", volumes).shouldContainExactly(DirectoryFolder(documentUri, "Akira"))
    }

    private fun volume(uri: String, name: String, isPrimary: Boolean = false) = DirectoryVolume(
        uri = uri,
        name = name,
        isPrimary = isPrimary,
        freeBytes = 1,
        totalBytes = 2,
    )
}
