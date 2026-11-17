package mihon.domain.source.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Makes the library mirror the series folders of the local source. Scoped so every caller shares the mutex.
 */
@Inject
@SingleIn(AppScope::class)
class SyncLocalLibrary(
    private val localSource: LocalSource,
    private val getFavorites: GetFavorites,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateManga: UpdateManga,
    private val libraryPreferences: LibraryPreferences,
) {
    private val mutex = Mutex()

    suspend operator fun invoke() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val changes = localLibraryChanges(localSource.getSeriesNames(), getFavorites.await())

            if (changes.toRemove.isNotEmpty()) {
                updateManga.awaitAll(changes.toRemove.map { MangaUpdate(it.id) { favoriteAt = null } })
            }

            if (changes.toAdd.isNotEmpty()) {
                val categoryId = defaultCategoryId(libraryPreferences.defaultCategory.get(), getCategories.await())
                changes.toAdd.forEach { name ->
                    try {
                        add(name, categoryId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to add $name to the library" }
                    }
                }
            }
        }
    }

    private suspend fun add(name: String, categoryId: Long?) {
        val series = SManga.create().apply {
            url = name
            title = name
        }
        val manga = networkToLocalManga(series.toDomainManga(LocalSource.ID))

        // Fetched while outside the library, so the chapters found now don't count as updates
        val update = updateMangaFromRemote(manga, fetchDetails = true, fetchChapters = true)
        // The fetch reports a cancellation as a failure
        currentCoroutineContext().ensureActive()
        if (update.isFailure) {
            logcat(LogPriority.WARN) { "Adding $name to the library without its details and chapters" }
        }

        setMangaDefaultChapterFlags.await(manga)
        if (categoryId != null && getCategories.await(manga.id).isEmpty()) {
            setMangaCategories.await(manga.id, listOf(categoryId))
        }

        val addedAt = addedAt(Clock.System.now(), getChaptersByMangaId.await(manga.id))
        updateManga.await(MangaUpdate(manga.id) { favoriteAt = addedAt })
    }
}

internal data class LocalLibraryChanges(
    val toAdd: List<String>,
    val toRemove: List<Manga>,
)

/**
 * @param seriesNames the series folders of the local source, or null when they could not be listed.
 */
internal fun localLibraryChanges(seriesNames: List<String>?, favorites: List<Manga>): LocalLibraryChanges {
    if (seriesNames == null) return LocalLibraryChanges(toAdd = emptyList(), toRemove = emptyList())

    val localFavorites = favorites.filter { it.isLocal() }
    val favoriteNames = localFavorites.mapTo(HashSet()) { it.url }
    val listedNames = seriesNames.toHashSet()
    return LocalLibraryChanges(
        toAdd = seriesNames.filterNot { it in favoriteNames },
        toRemove = localFavorites.filterNot { it.url in listedNames },
    )
}

/**
 * New chapters get fetch dates slightly ahead of the clock, and chapters fetched after the entry was added
 * count as updates.
 */
internal fun addedAt(now: Instant, chapters: List<Chapter>): Instant {
    return chapters.maxOfOrNull { it.dateFetch }?.coerceAtLeast(now) ?: now
}

/**
 * The category new library entries go into, or null when [preference] doesn't name a category the user made.
 */
internal fun defaultCategoryId(preference: Int, categories: List<Category>): Long? {
    return categories.find { it.id == preference.toLong() && !it.isSystemCategory }?.id
}
