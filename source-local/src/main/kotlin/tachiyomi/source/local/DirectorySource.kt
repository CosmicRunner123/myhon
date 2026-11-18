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
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.isEpub

/**
 * The comics of directory view, which the reader reads without storing them: a series is a folder, with its URI as
 * url, and its chapters are the folder's comic files, with their URIs as urls. It never writes into a folder.
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

    // Directory view stores no series, so there are none to update
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        return SMangaUpdate(manga, chapters)
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
