package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.music.MusicTrack
import eu.kanade.tachiyomi.ui.reader.music.isAudioFile
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Loader used to load a chapter from a directory given on [file].
 */
internal class DirectoryPageLoader(val file: UniFile) : PageLoader() {

    override suspend fun getPages(): List<ReaderPage> {
        val (audioFiles, otherFiles) = file.listFiles().orEmpty()
            .filter { !it.isDirectory }
            .partition { isAudioFile(it.name.orEmpty()) }
        music = audioFiles.map { audioFile -> MusicTrack(audioFile.name!!) { audioFile.uri } }
        return otherFiles
            .filter { ImageUtil.isImage(it.name) { it.openInputStream() } }
            .sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
            .mapIndexed { i, file -> ReaderPage(i) { file.openInputStream() } }
    }
}
