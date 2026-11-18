package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.source.local.DirectorySource
import tachiyomi.source.local.LocalSource

@Execution(ExecutionMode.CONCURRENT)
class BackupRestorerTest {

    @Test
    fun `series of the local source are restored`() {
        BackupManga(source = LocalSource.ID, url = "Series").isRestorable() shouldBe true
    }

    @Test
    fun `series of directory view are skipped`() {
        BackupManga(source = DirectorySource.ID, url = "file:///storage/Comics").isRestorable() shouldBe false
    }

    @Test
    fun `series of any other source are skipped`() {
        BackupManga(source = 2499283573021220255, url = "/manga/1").isRestorable() shouldBe false
    }
}
