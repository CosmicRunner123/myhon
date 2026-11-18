package eu.kanade.tachiyomi.ui.reader.music

import android.net.Uri
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import java.io.File

/**
 * An audio file of a chapter. [name] is its file name, or its path inside an archive. [uri] returns a URI the
 * player can read, copying the file into the given cache folder first if it has to.
 */
class MusicTrack(
    val name: String,
    val uri: suspend (cacheDir: File) -> Uri,
) {
    val cue = MusicCue.parse(name)
}

/**
 * When a track plays, read from its file name. [images] are the images, counted from 1, it plays on, or null for
 * the whole chapter. [trackNumber] orders the tracks that play one after the other.
 */
data class MusicCue(
    val trackNumber: Int?,
    val images: IntRange?,
) {
    companion object {
        private val NUMBER = Regex("[0-9]+")

        fun parse(name: String): MusicCue {
            val baseName = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            val numbers = NUMBER.findAll(baseName)
                .map { it.value.toIntOrNull() ?: Int.MAX_VALUE }
                .toList()
            return when (numbers.size) {
                0 -> MusicCue(trackNumber = null, images = null)
                1 -> MusicCue(trackNumber = numbers[0], images = null)
                2 -> MusicCue(trackNumber = null, images = imageRange(numbers[0], numbers[1]))
                else -> MusicCue(trackNumber = numbers[0], images = imageRange(numbers[1], numbers[2]))
            }
        }

        private fun imageRange(start: Int, stop: Int) = minOf(start, stop)..maxOf(start, stop)
    }
}

private val AUDIO_EXTENSIONS = setOf(
    "3ga", "aac", "ac3", "aif", "aifc", "aiff", "alac", "amr", "ape", "au", "awb", "caf", "dts", "eac3", "flac",
    "imy", "m4a", "m4b", "m4r", "mid", "midi", "mka", "mp2", "mp3", "mpa", "mpc", "mpga", "mxmf", "oga", "ogg",
    "opus", "ota", "ra", "rtttl", "rtx", "snd", "spx", "tta", "wav", "wave", "weba", "wma", "wv", "xmf",
)

fun isAudioFile(name: String): Boolean {
    // Hidden files include the stubs macOS writes beside each file (._x.mp3, __MACOSX/._x.mp3), which hold no audio
    return !name.substringAfterLast('/').substringAfterLast('\\').startsWith('.') &&
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
}

// Whole-chapter tracks rank below every range
private val MusicCue.start: Int
    get() = images?.first ?: -1

private val playlistOrder = compareBy<MusicTrack, Int?>(nullsLast()) { it.cue.trackNumber }
    .thenComparator { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }

/**
 * The tracks to play, one after the other, while the reader shows [image] (counted from 1): of the tracks that
 * cover it, those with the latest start.
 */
fun List<MusicTrack>.playlistAt(image: Int): List<MusicTrack> {
    val candidates = filter { it.cue.images?.contains(image) ?: true }
    val start = candidates.maxOfOrNull { it.cue.start } ?: return emptyList()
    return candidates
        .filter { it.cue.start == start }
        .sortedWith(playlistOrder)
}
