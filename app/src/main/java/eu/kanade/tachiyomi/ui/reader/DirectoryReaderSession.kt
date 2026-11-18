package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetDirectoryFolderViewerFlags
import tachiyomi.domain.history.interactor.GetDirectoryHistory
import tachiyomi.domain.history.interactor.SetDirectoryFolderViewerFlags
import tachiyomi.domain.history.interactor.UpsertDirectoryHistory
import tachiyomi.domain.history.model.DirectoryHistory
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.source.local.DirectorySource
import tachiyomi.source.local.io.DirectoryEntry
import tachiyomi.source.local.io.listDirectory
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Reads the comic files of the folder [folderUri] named [folderName] of directory view from the file
 * [initialFileUri] on. The folder and its files are a series and chapters held in memory only, with ids no row has;
 * their progress goes to the directory history and the reader settings to the folder's own.
 */
@AssistedInject
class DirectoryReaderSession(
    @Assisted private val savedState: SavedStateHandle,
    @Assisted private val folderUri: String,
    @Assisted private val folderName: String,
    @Assisted initialFileUri: String,
    private val context: Context,
    private val getDirectoryHistory: GetDirectoryHistory,
    private val upsertDirectoryHistory: UpsertDirectoryHistory,
    private val getDirectoryFolderViewerFlags: GetDirectoryFolderViewerFlags,
    private val setDirectoryFolderViewerFlags: SetDirectoryFolderViewerFlags,
) : ReaderSession {

    @AssistedFactory
    fun interface Factory {
        fun create(
            savedState: SavedStateHandle,
            folderUri: String,
            folderName: String,
            initialFileUri: String,
        ): DirectoryReaderSession
    }

    /**
     * The URI of the file read. Used to restore from process kill.
     */
    private var fileUri = savedState.get<String>("file_uri") ?: initialFileUri
        set(value) {
            savedState["file_uri"] = value
            field = value
        }

    /** The folder's comic files by URI, as listed for its chapters. */
    private var files: Map<String, DirectoryEntry> = emptyMap()

    // Progress and history each read the file's entry and write it whole, which must not interleave
    private val writes = Mutex()

    override suspend fun loadManga(): Manga {
        // Keeps the id -1 of a new series, which no stored series has
        return Manga.create().copy(
            source = DirectorySource.ID,
            url = folderUri,
            title = folderName,
            viewerFlags = getDirectoryFolderViewerFlags.await(folderUri),
        )
    }

    override suspend fun loadChapters(manga: Manga): List<ReaderChapter> {
        val files = listDirectory(context, folderUri.toUri())
            ?.filterNot { it.isDirectory }
            ?: throw Exception(context.stringResource(MR.strings.directory_folder_unreadable))
        if (files.none { it.uri == fileUri }) throw Exception(context.stringResource(MR.strings.chapter_not_found))
        this.files = files.associateBy { it.uri }

        val history = getDirectoryHistory.subscribeByFolder(folderUri).first().associateBy { it.uri }
        return files.mapIndexed { index, file ->
            val chapter = Chapter.create().copy(
                id = -1L - index,
                mangaId = manga.id,
                url = file.uri,
                name = file.title,
                read = history[file.uri]?.read ?: false,
                lastPageRead = history[file.uri]?.lastPageRead ?: 0,
            )
            // The numbers in the names of files are not chapter numbers
            ReaderChapter(chapter, hasChapterNumber = false)
        }
    }

    override fun isCurrentChapter(chapter: Chapter): Boolean {
        return chapter.url == fileUri
    }

    override fun setCurrentChapter(chapter: Chapter) {
        fileUri = chapter.url
    }

    override suspend fun saveProgress(chapter: Chapter) {
        writes.withLock {
            // The reader saves no progress in incognito mode, so a file not in the history is being read just now
            val lastRead = getDirectoryHistory.await(chapter.url)?.lastRead ?: Clock.System.now()
            upsertDirectoryHistory.await(history(chapter, chapter.read, chapter.lastPageRead, lastRead))
        }
    }

    // Files have no tracks, and the numbers in their names are not chapter numbers to find duplicates by
    override suspend fun onChapterCompleted(readerChapter: ReaderChapter) = Unit

    override suspend fun saveHistory(chapter: Chapter, readAt: Instant, readDuration: Long) {
        writes.withLock {
            // Progress saved already may be newer than that of [chapter], which was taken before the lock
            val saved = getDirectoryHistory.await(chapter.url)
            upsertDirectoryHistory.await(
                history(chapter, saved?.read ?: chapter.read, saved?.lastPageRead ?: chapter.lastPageRead, readAt),
            )
        }
    }

    private fun history(chapter: Chapter, read: Boolean, lastPageRead: Int, lastRead: Instant): DirectoryHistory {
        val file = files.getValue(chapter.url)
        return DirectoryHistory(
            uri = file.uri,
            folderUri = folderUri,
            folderName = folderName,
            name = file.title,
            lastModified = file.lastModified,
            size = file.size,
            lastPageRead = lastPageRead,
            read = read,
            lastRead = lastRead,
        )
    }

    override suspend fun setReadingMode(manga: Manga, flag: Long): Manga {
        return setViewerFlags(manga, flag, ReadingMode.MASK.toLong())
    }

    override suspend fun setOrientation(manga: Manga, flag: Long): Manga {
        return setViewerFlags(manga, flag, ReaderOrientation.MASK.toLong())
    }

    private suspend fun setViewerFlags(manga: Manga, flag: Long, mask: Long): Manga {
        setDirectoryFolderViewerFlags.await(folderUri, flag, mask)
        return manga.copy(viewerFlags = getDirectoryFolderViewerFlags.await(folderUri))
    }
}
