package mihon.domain.source.interactor

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import kotlin.time.Instant

@Execution(ExecutionMode.CONCURRENT)
class SyncLocalLibraryTest {

    @Test
    fun `series without a library entry are added`() {
        val changes = localLibraryChanges(
            seriesNames = listOf("One", "Two", "Three"),
            favorites = listOf(entry(1, "Two")),
        )

        changes.toAdd shouldContainExactly listOf("One", "Three")
        changes.toRemove.shouldBeEmpty()
    }

    @Test
    fun `library entries whose folder is gone are removed`() {
        val gone = entry(2, "Two")

        val changes = localLibraryChanges(
            seriesNames = listOf("One"),
            favorites = listOf(entry(1, "One"), gone),
        )

        changes.toAdd.shouldBeEmpty()
        changes.toRemove shouldContainExactly listOf(gone)
    }

    @Test
    fun `an empty local folder removes every local entry`() {
        val favorites = listOf(entry(1, "One"), entry(2, "Two"))

        val changes = localLibraryChanges(seriesNames = emptyList(), favorites = favorites)

        changes.toAdd.shouldBeEmpty()
        changes.toRemove shouldContainExactly favorites
    }

    @Test
    fun `nothing changes when the series could not be listed`() {
        val changes = localLibraryChanges(
            seriesNames = null,
            favorites = listOf(entry(1, "One"), entry(2, "Two")),
        )

        changes.toAdd.shouldBeEmpty()
        changes.toRemove.shouldBeEmpty()
    }

    @Test
    fun `a library that mirrors the folders needs no change`() {
        val changes = localLibraryChanges(
            seriesNames = listOf("One", "Two"),
            favorites = listOf(entry(2, "Two"), entry(1, "One")),
        )

        changes.toAdd.shouldBeEmpty()
        changes.toRemove.shouldBeEmpty()
    }

    @Test
    fun `entries of other sources are left alone`() {
        val changes = localLibraryChanges(
            seriesNames = listOf("One"),
            favorites = listOf(entry(1, "One", source = 42), entry(2, "Two", source = 42)),
        )

        changes.toAdd shouldContainExactly listOf("One")
        changes.toRemove.shouldBeEmpty()
    }

    @Test
    fun `an entry is added no earlier than its chapters were fetched`() {
        val now = Instant.fromEpochMilliseconds(1_000)
        val chapters = listOf(900L, 1_200L, 1_100L).map {
            Chapter.create().copy(dateFetch = Instant.fromEpochMilliseconds(it))
        }

        addedAt(now, chapters) shouldBe Instant.fromEpochMilliseconds(1_200)
        addedAt(now, chapters.take(1)) shouldBe now
        addedAt(now, emptyList()) shouldBe now
    }

    @Test
    fun `new entries get the category the user chose as default`() {
        defaultCategoryId(preference = 2, categories = categories) shouldBe 2L
    }

    @Test
    fun `new entries get no category without a chosen default`() {
        defaultCategoryId(preference = -1, categories = categories) shouldBe null
        defaultCategoryId(preference = 0, categories = categories) shouldBe null
    }

    @Test
    fun `a default category that no longer exists is ignored`() {
        defaultCategoryId(preference = 3, categories = categories) shouldBe null
    }

    private val categories = listOf(
        Category(id = Category.UNCATEGORIZED_ID, name = "", order = 0, flags = 0),
        Category(id = 1, name = "Reading", order = 1, flags = 0),
        Category(id = 2, name = "Finished", order = 2, flags = 0),
    )

    private fun entry(id: Long, folder: String, source: Long = LocalSource.ID): Manga {
        return Manga.create().copy(
            id = id,
            source = source,
            url = folder,
            title = folder,
            favoriteAt = Instant.fromEpochMilliseconds(id),
        )
    }
}
