package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.music.MusicTrack

/**
 * A loader used to load pages into the reader. Any open resources must be cleaned up when the
 * method [recycle] is called.
 */
abstract class PageLoader {

    /**
     * Returns the list of pages of a chapter.
     */
    abstract suspend fun getPages(): List<ReaderPage>

    /**
     * The chapter's audio files, found by [getPages].
     */
    var music: List<MusicTrack> = emptyList()
        protected set

    /**
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    open fun recycle() {}
}
