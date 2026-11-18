package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

/**
 * What the reader reads and where it keeps what reading changes: a series of the library, or a folder of directory
 * view, which the reader holds in memory only.
 */
sealed interface ReaderSession {

    /** Throws when it can't be read. */
    suspend fun loadManga(): Manga

    /** The chapters of [manga] to read in reading order, the current one among them. Throws when they can't be read. */
    suspend fun loadChapters(manga: Manga): List<ReaderChapter>

    /** Whether [chapter] is the one being read: the one opened, until [setCurrentChapter]. */
    fun isCurrentChapter(chapter: Chapter): Boolean

    /** Keeps [chapter] as the one being read, also for when the process was killed. */
    fun setCurrentChapter(chapter: Chapter)

    /** Saves the last page read of [chapter] and whether it was read. */
    suspend fun saveProgress(chapter: Chapter)

    /** Applies what else follows from reading [readerChapter] to its last page. */
    suspend fun onChapterCompleted(readerChapter: ReaderChapter)

    suspend fun saveHistory(chapter: Chapter, readAt: Instant, readDuration: Long)

    /** Saves [flag] as the reading mode of [manga], and returns the series as it then is. */
    suspend fun setReadingMode(manga: Manga, flag: Long): Manga?

    /** Saves [flag] as the orientation of [manga], and returns the series as it then is. */
    suspend fun setOrientation(manga: Manga, flag: Long): Manga?
}
