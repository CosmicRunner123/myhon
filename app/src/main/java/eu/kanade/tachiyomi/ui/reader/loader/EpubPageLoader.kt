package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.core.archive.EpubReader

/**
 * Loader used to load a chapter from a .epub file.
 */
internal class EpubPageLoader(private val reader: EpubReader) : PageLoader() {

    override suspend fun getPages(): List<ReaderPage> {
        return reader.getImagesFromPages().mapIndexed { i, path -> ReaderPage(i) { reader.getInputStream(path)!! } }
    }

    override fun recycle() {
        reader.close()
    }
}
