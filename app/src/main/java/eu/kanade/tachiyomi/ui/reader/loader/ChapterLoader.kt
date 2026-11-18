package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.source.local.DirectorySource
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val source: Source,
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withContext(Dispatchers.IO) {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            var loader: PageLoader? = null
            try {
                loader = getPageLoader(chapter)
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read) {
                    chapter.requestedPage = chapter.chapter.lastPageRead
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
            } catch (e: Throwable) {
                loader?.recycle()
                if (chapter.pageLoader === loader) chapter.pageLoader = null
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val format = when (source) {
            is LocalSource -> source.getFormat(chapter.chapter.toSChapter())
            is DirectorySource -> source.getFormat(chapter.chapter.toSChapter())
            else -> error(context.stringResource(MR.strings.source_not_installed, source.toString()))
        }
        return when (format) {
            is Format.Directory -> DirectoryPageLoader(format.file)
            is Format.Archive -> ArchivePageLoader(format.file, context)
            is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
        }
    }
}
