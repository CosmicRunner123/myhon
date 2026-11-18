package eu.kanade.tachiyomi.ui.directory

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class StorageVolumesTest {

    @Test
    fun `internal storage comes first, then the others by name in natural order`() {
        val internal = volume("file:///storage/emulated/0", "Internal shared storage", isPrimary = true)
        val usb10 = volume("file:///storage/AAAA-0010", "USB drive 10")
        val usb2 = volume("file:///storage/AAAA-0002", "USB drive 2")
        val sdCard = volume("file:///storage/1234-ABCD", "sd card")

        listOf(usb10, sdCard, internal, usb2).sortedWith(storageVolumeOrder)
            .shouldContainExactly(internal, sdCard, usb2, usb10)
    }

    @Test
    fun `volumes with the same name keep one order`() {
        val first = volume("file:///storage/1111-1111", "USB drive")
        val second = volume("file:///storage/2222-2222", "USB drive")

        listOf(second, first).sortedWith(storageVolumeOrder).shouldContainExactly(first, second)
        listOf(first, second).sortedWith(storageVolumeOrder).shouldContainExactly(first, second)
    }

    private fun volume(uri: String, name: String, isPrimary: Boolean = false) = DirectoryVolume(
        uri = uri,
        name = name,
        isPrimary = isPrimary,
        freeBytes = 1,
        totalBytes = 2,
    )
}
