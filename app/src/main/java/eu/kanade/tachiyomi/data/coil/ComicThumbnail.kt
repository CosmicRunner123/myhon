package eu.kanade.tachiyomi.data.coil

import coil3.key.Keyer
import coil3.request.Options

/**
 * The first page of the comic file [uri], made smaller and cached by [ComicThumbnailFetcher].
 *
 * [lastModified] and [size] are the file's, as its folder listed them, so a changed file gets a new thumbnail.
 */
data class ComicThumbnail(
    val uri: String,
    val lastModified: Long,
    val size: Long,
)

class ComicThumbnailKeyer : Keyer<ComicThumbnail> {
    override fun key(data: ComicThumbnail, options: Options): String {
        return "${data.uri};${data.lastModified};${data.size}"
    }
}
