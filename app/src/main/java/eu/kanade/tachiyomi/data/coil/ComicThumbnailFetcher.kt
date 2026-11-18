package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.core.net.toUri
import ca.mpreg.imagedecoder.ImageDecoder
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.loader.archivePageNames
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mihon.core.archive.EpubReader
import mihon.core.archive.archiveReader
import tachiyomi.source.local.io.Format
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * A [Fetcher] that makes the [ComicThumbnail] of a comic file from the page the reader opens it on, and keeps it in a
 * disk cache of its own, so that thumbnails never push out other images and are trimmed to their own size limit.
 */
class ComicThumbnailFetcher(
    private val thumbnail: ComicThumbnail,
    private val options: Options,
    private val diskCacheLazy: Lazy<DiskCache>,
    private val diskCacheKey: String,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val diskCache = diskCacheLazy.value
        diskCache.openSnapshot(diskCacheKey)?.let { return it.toFetchResult(diskCache) }
        return concurrentRequests.apply(diskCacheKey) {
            (diskCache.openSnapshot(diskCacheKey) ?: generate(diskCache)).toFetchResult(diskCache)
        }
    }

    private suspend fun generate(diskCache: DiskCache): DiskCache.Snapshot {
        check(diskCacheKey !in failedKeys) { "No thumbnail for ${thumbnail.uri}" }
        val bitmap = generations.withPermit { decodeFirstPage() }
        try {
            val editor = diskCache.openEditor(diskCacheKey) ?: error("Thumbnail of ${thumbnail.uri} is being written")
            try {
                diskCache.fileSystem.write(editor.data) {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream())) {
                        "Thumbnail of ${thumbnail.uri} could not be encoded"
                    }
                }
                return editor.commitAndOpenSnapshot() ?: error("Thumbnail of ${thumbnail.uri} was not kept")
            } catch (e: Exception) {
                try {
                    editor.abort()
                } catch (ignored: Exception) {
                }
                throw e
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeFirstPage(): Bitmap {
        val context = options.context
        val file = UniFile.fromUri(context, thumbnail.uri.toUri()) ?: error("Invalid URI ${thumbnail.uri}")
        return when (val format = Format.valueOf(file)) {
            is Format.Archive -> format.file.archiveReader(context).use { reader ->
                rememberFailure {
                    val page = archivePageNames(reader).firstOrNull() ?: error("No pages in ${thumbnail.uri}")
                    reader.getInputStream(page)!!.use(::decodeThumbnail)
                }
            }
            // EpubReader reads the archive as it's made, so the mapping is released when the file isn't one
            is Format.Epub -> format.file.archiveReader(context).use { reader ->
                rememberFailure {
                    val epub = EpubReader(reader)
                    val page = epub.getFirstImage() ?: error("No pages in ${thumbnail.uri}")
                    epub.getInputStream(page)!!.use(::decodeThumbnail)
                }
            }
            is Format.Directory -> error("${thumbnail.uri} is a folder")
        }
    }

    /**
     * Runs [block], which reads the opened file, and remembers when it fails, so a broken file isn't read again each
     * time it's shown. A file that couldn't be opened isn't remembered, since its storage may come back.
     */
    private inline fun <T> rememberFailure(block: () -> T): T {
        try {
            return block()
        } catch (e: Exception) {
            // There may be memory for it the next time it's shown
            if (e !is ImageDecoder.OutOfMemoryException) failedKeys += diskCacheKey
            throw e
        }
    }

    private fun decodeThumbnail(stream: InputStream): Bitmap {
        val page = ImageDecoder.open(stream).use { decoder ->
            decoder.decodeToBitmap(null, thumbnailSampleSize(decoder.width, decoder.height))
        }
        val scale = min(1f, THUMBNAIL_WIDTH.toFloat() / page.width)
        // Covers show about 2:3 of a page, so long strips keep only their top
        val height = min(page.height, page.width * 2)
        if (scale == 1f && height == page.height) return page
        val matrix = Matrix().apply { setScale(scale, scale) }
        return Bitmap.createBitmap(page, 0, 0, page.width, height, matrix, true)
            .also { if (it !== page) page.recycle() }
    }

    private fun DiskCache.Snapshot.toFetchResult(diskCache: DiskCache): FetchResult {
        return SourceFetchResult(
            source = ImageSource(
                file = data,
                fileSystem = diskCache.fileSystem,
                diskCacheKey = diskCacheKey,
                closeable = this,
            ),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val diskCacheLazy: Lazy<DiskCache>) : Fetcher.Factory<ComicThumbnail> {

        override fun create(data: ComicThumbnail, options: Options, imageLoader: ImageLoader): Fetcher {
            return ComicThumbnailFetcher(data, options, diskCacheLazy, imageLoader.components.key(data, options)!!)
        }
    }
}

/**
 * The power of two a page of [width] by [height] pixels is divided by while it is decoded: the largest that leaves it
 * at least [THUMBNAIL_WIDTH] wide, or a larger one if the page would still take more than [MAX_DECODED_PIXELS].
 */
internal fun thumbnailSampleSize(width: Int, height: Int): Int {
    var sampleSize = 1
    while (width / (sampleSize * 2) >= THUMBNAIL_WIDTH) sampleSize *= 2
    while ((width / sampleSize).toLong() * (height / sampleSize) > MAX_DECODED_PIXELS) sampleSize *= 2
    return sampleSize
}

internal const val THUMBNAIL_WIDTH = 360

// About 16 MB as a bitmap, which long strips would otherwise far exceed
internal const val MAX_DECODED_PIXELS = 4_000_000L

private const val JPEG_QUALITY = 85

// Each generation maps a whole archive and decodes a page, and a folder asks for all its thumbnails at once
private val generations = Semaphore(2)

private val failedKeys = ConcurrentHashMap.newKeySet<String>()

private val concurrentRequests = DeDupeConcurrentRequests<FetchResult>()
