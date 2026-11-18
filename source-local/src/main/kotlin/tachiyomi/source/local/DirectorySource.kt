package tachiyomi.source.local

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.DirectoryEntry
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.directoryEntryOrder
import tachiyomi.source.local.io.isEpub
import tachiyomi.source.local.io.listDirectory

/**
 * The comics of directory view: a series is a folder, with its URI as url, and its chapters are the folder's comic
 * files, with their URIs as urls. It never writes into a folder.
 */
@Inject
@SingleIn(AppScope::class)
class DirectorySource(
    private val context: Context,
) : Source {

    override val name: String = context.stringResource(MR.strings.directory_source)

    override val id: Long = ID

    override val lang: String = "other"

    override fun toString() = name

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        return SMangaUpdate(manga, if (fetchChapters) getChapterList(manga.url) else chapters)
    }

    /**
     * The comic files of the folder [folderUri] as chapters. Throws when the folder can't be read, as the chapter
     * sync deletes the chapters that are not listed.
     */
    suspend fun getChapterList(folderUri: String): List<SChapter> {
        val entries = listDirectory(context, folderUri.toUri())
            ?: throw Exception(context.stringResource(MR.strings.directory_folder_unreadable))
        return directoryChapters(entries)
    }

    fun getFormat(chapter: SChapter): Format {
        val file = UniFile.fromUri(context, chapter.url.toUri())?.takeIf { it.isFile }
            ?: throw Exception(context.stringResource(MR.strings.chapter_not_found))
        val name = file.name.orEmpty()
        return when {
            isEpub(name) -> Format.Epub(file)
            Archive.isSupported(name) -> Format.Archive(file)
            else -> throw Exception(context.stringResource(MR.strings.local_invalid_format))
        }
    }

    companion object {
        const val ID = 1L
    }
}

/**
 * The files of a folder listing as chapters, last first as sources give them, so the reader goes through them in the
 * order the folder shows them.
 */
internal fun directoryChapters(entries: List<DirectoryEntry>): List<SChapter> {
    return entries
        .filterNot { it.isDirectory }
        .sortedWith(directoryEntryOrder.reversed())
        .map { file ->
            SChapter.create().apply {
                url = file.uri
                name = file.title
                // The chapter sync stores the current time for 0, and then finds every chapter changed on each sync
                date_upload = file.lastModified.takeIf { it > 0 } ?: UNKNOWN_DATE_UPLOAD
            }
        }
}

private const val UNKNOWN_DATE_UPLOAD = 1L
