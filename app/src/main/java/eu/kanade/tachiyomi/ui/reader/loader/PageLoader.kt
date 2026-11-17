package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

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
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    open fun recycle() {}
}
