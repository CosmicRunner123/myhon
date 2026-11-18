package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.music.MusicTrack
import eu.kanade.tachiyomi.ui.reader.music.isAudioFile
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil
import java.io.File

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(private val file: UniFile, private val context: Context) : PageLoader() {
    private val reader = file.archiveReader(context)

    override suspend fun getPages(): List<ReaderPage> {
        val fileNames = archiveFileNames(reader)
        music = fileNames.filter(::isAudioFile).map { name ->
            MusicTrack(name) { cacheDir -> extractEntry(context, file, name, cacheDir) }
        }
        return archivePageNames(reader, fileNames)
            .mapIndexed { i, name -> ReaderPage(i) { reader.getInputStream(name)!! } }
            .toList()
    }

    override fun recycle() {
        reader.close()
    }
}

/**
 * The names of the files in [reader]'s archive, in the archive's order.
 */
internal fun archiveFileNames(reader: ArchiveReader): List<String> {
    return reader.useEntries { entries -> entries.filter { it.isFile }.map { it.name }.toList() }
}

/**
 * The names of the pages of [reader]'s archive, whose files are [fileNames], in reading order. Comic thumbnails take
 * the first one, so they show the page the reader opens on.
 */
internal fun archivePageNames(
    reader: ArchiveReader,
    fileNames: List<String> = archiveFileNames(reader),
): Sequence<String> {
    return archivePageNames(fileNames) { name -> ImageUtil.isImage(name) { reader.getInputStream(name)!! } }
}

/**
 * The files of [fileNames] that aren't audio, in natural order, that [isImage] accepts. [isImage] is called only as
 * the sequence reaches each file, as it may have to decode it.
 */
internal fun archivePageNames(fileNames: List<String>, isImage: (String) -> Boolean): Sequence<String> {
    return fileNames
        .filterNot(::isAudioFile)
        .sortedWith { f1, f2 -> f1.compareToCaseInsensitiveNaturalOrder(f2) }
        .asSequence()
        .filter(isImage)
}

/**
 * Copies [entryName] of [archive] into [cacheDir], unless an earlier call already did, and returns the copy's URI.
 * It reads the archive with its own reader, since the page loader's reader is closed when the chapter is recycled.
 */
private suspend fun extractEntry(context: Context, archive: UniFile, entryName: String, cacheDir: File): Uri {
    return withContext(Dispatchers.IO) {
        val extension = entryName.substringAfterLast('.')
        val target = File(cacheDir, "${DiskUtil.hashKeyForDisk("${archive.uri}/$entryName")}.$extension")
        if (!target.exists()) {
            cacheDir.mkdirs()
            // Written aside first, so an interrupted copy is never taken for a finished one
            val partial = File.createTempFile("music", null, cacheDir)
            try {
                archive.archiveReader(context).use { reader ->
                    reader.getInputStream(entryName)!!.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                partial.renameTo(target)
            } finally {
                partial.delete()
            }
        }
        target.toUri()
    }
}
