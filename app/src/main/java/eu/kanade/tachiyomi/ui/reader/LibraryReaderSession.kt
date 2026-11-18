package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.DirectorySource
import kotlin.time.Instant

/**
 * Reads the series [mangaId] of the library from its chapter [initialChapterId] on.
 */
@AssistedInject
class LibraryReaderSession(
    @Assisted private val savedState: SavedStateHandle,
    @Assisted private val mangaId: Long,
    @Assisted initialChapterId: Long,
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val context: Context,
    private val readerPreferences: ReaderPreferences,
    private val trackPreferences: TrackPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val trackChapter: TrackChapter,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val upsertHistory: UpsertHistory,
    private val setMangaViewerFlags: SetMangaViewerFlags,
) : ReaderSession {

    @AssistedFactory
    fun interface Factory {
        fun create(savedState: SavedStateHandle, mangaId: Long, initialChapterId: Long): LibraryReaderSession
    }

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: initialChapterId
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    private var unfilteredChapterList: List<Chapter>? = null

    private suspend fun getUnfilteredChapterList(): List<Chapter> {
        return unfilteredChapterList
            ?: getChaptersByMangaId.await(mangaId, applyScanlatorFilter = false)
                .also { unfilteredChapterList = it }
    }

    override suspend fun loadManga(): Manga {
        return getManga.await(mangaId) ?: error("Requested manga of id $mangaId not found")
    }

    override suspend fun loadChapters(manga: Manga): List<ReaderChapter> {
        val chapters = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
            .let { filtered ->
                if (filtered.any { it.id == chapterId }) {
                    filtered
                } else {
                    filtered + getUnfilteredChapterList().filter { it.id == chapterId }
                }
            }

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead.get() && it.read -> true
                        readerPreferences.skipFiltered.get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        // The numbers in the names of files in directory view are not chapter numbers
        val hasChapterNumbers = manga.source != DirectorySource.ID
        return chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe.get() && hasChapterNumbers) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .map { ReaderChapter(it, hasChapterNumber = hasChapterNumbers) }
    }

    override fun isCurrentChapter(chapter: Chapter): Boolean {
        return chapter.id == chapterId
    }

    override fun setCurrentChapter(chapter: Chapter) {
        chapterId = chapter.id
    }

    override suspend fun saveProgress(chapter: Chapter) {
        updateChapter.await(
            ChapterUpdate(chapter.id) {
                read = chapter.read
                lastPageRead = chapter.lastPageRead
            },
        )
    }

    override suspend fun onChapterCompleted(readerChapter: ReaderChapter) {
        updateTrackChapterRead(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead || !readerChapter.hasChapterNumber) return

        val duplicateUnreadChapters = getUnfilteredChapterList()
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber == readerChapter.chapter.chapterNumber
                ) {
                    ChapterUpdate(chapter.id) { read = true }
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (!trackPreferences.autoUpdateTrack.get()) return

        // Reading the last page and leaving right away is common, so it outlives the reader
        appScope.launch {
            trackChapter.await(context, mangaId, readerChapter.chapter.chapterNumber)
        }
    }

    override suspend fun saveHistory(chapter: Chapter, readAt: Instant, readDuration: Long) {
        upsertHistory.await(HistoryUpdate(chapter.id, readAt, readDuration))
    }

    suspend fun setBookmark(chapter: Chapter, bookmarked: Boolean) {
        updateChapter.await(
            ChapterUpdate(chapter.id) {
                bookmark = bookmarked
            },
        )
    }

    override suspend fun setReadingMode(manga: Manga, flag: Long): Manga? {
        setMangaViewerFlags.awaitSetReadingMode(manga.id, flag)
        return getManga.await(manga.id)
    }

    override suspend fun setOrientation(manga: Manga, flag: Long): Manga? {
        setMangaViewerFlags.awaitSetOrientation(manga.id, flag)
        return getManga.await(manga.id)
    }
}
