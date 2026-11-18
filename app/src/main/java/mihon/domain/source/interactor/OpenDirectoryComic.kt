package mihon.domain.source.interactor

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.i18n.MR
import tachiyomi.source.local.DirectorySource
import kotlin.time.Clock

/**
 * Gets a comic file of directory view ready to read. Its folder is an entry of [DirectorySource] outside the library,
 * with the folder's comic files as chapters, so the reader goes on to the next file.
 */
@Inject
class OpenDirectoryComic(
    private val context: Context,
    private val directorySource: DirectorySource,
    private val networkToLocalManga: NetworkToLocalManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val getChapter: GetChapter,
    private val updateManga: UpdateManga,
) {

    /**
     * The chapter of the file [fileUri] in the folder [folderUri] named [folderName], once the folder's chapters
     * match its files and the file is the folder's cover. Throws, without storing anything, when the folder can't be
     * read or no longer has the file.
     */
    suspend fun await(folderUri: String, folderName: String, fileUri: String): Chapter {
        val chapters = directorySource.getChapterList(folderUri)
        if (chapters.none { it.url == fileUri }) {
            throw Exception(context.stringResource(MR.strings.chapter_not_found))
        }

        val manga = networkToLocalManga(
            Manga.create().copy(source = DirectorySource.ID, url = folderUri, title = folderName),
        )
        syncChaptersWithSource.await(chapters, manga)
        val chapter = getChapter.await(fileUri, manga.id)
            ?: throw Exception(context.stringResource(MR.strings.chapter_not_found))

        // History shows the folder with the thumbnail of the file opened last
        if (manga.thumbnailUrl != fileUri) {
            updateManga.await(
                MangaUpdate(manga.id) {
                    thumbnailUrl = fileUri
                    coverLastModified = Clock.System.now()
                },
            )
        }
        return chapter
    }
}
