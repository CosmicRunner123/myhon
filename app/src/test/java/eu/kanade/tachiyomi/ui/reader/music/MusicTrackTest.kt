package eu.kanade.tachiyomi.ui.reader.music

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class MusicTrackTest {

    @Test
    fun `audio files are recognised by their extension, ignoring case`() {
        listOf("a.mp3", "b.M4A", "c.flac", "d.wav", "e.Ogg", "f.opus", "g.mid", "h.aiff", "i.wma", "dir/j.aac")
            .forEach { isAudioFile(it) shouldBe true }
        listOf("a.mp4", "b.mkv", "c.webm", "d.jpg", "e.png", "mp3", "f.mp3.txt", "")
            .forEach { isAudioFile(it) shouldBe false }
    }

    @Test
    fun `two numbers are the start and stop images`() {
        MusicCue.parse("05-12.mp3") shouldBe MusicCue(trackNumber = null, images = 5..12)
        MusicCue.parse("battle 3 to 7.ogg") shouldBe MusicCue(trackNumber = null, images = 3..7)
    }

    @Test
    fun `three numbers are the track number, start and stop images`() {
        MusicCue.parse("2 - 10 - 20.flac") shouldBe MusicCue(trackNumber = 2, images = 10..20)
    }

    @Test
    fun `numbers after the third are ignored`() {
        MusicCue.parse("1_4_9_16_25.mp3") shouldBe MusicCue(trackNumber = 1, images = 4..9)
    }

    @Test
    fun `one number is a track number for the whole chapter`() {
        MusicCue.parse("Theme 2.mp3") shouldBe MusicCue(trackNumber = 2, images = null)
    }

    @Test
    fun `no number is the whole chapter`() {
        MusicCue.parse("Theme.mp3") shouldBe MusicCue(trackNumber = null, images = null)
    }

    @Test
    fun `numbers in the extension and the folders don't count`() {
        MusicCue.parse("Theme.mp3") shouldBe MusicCue(trackNumber = null, images = null)
        MusicCue.parse("Theme.m4a") shouldBe MusicCue(trackNumber = null, images = null)
        MusicCue.parse("Volume 1/Chapter 2/3-4.mp3") shouldBe MusicCue(trackNumber = null, images = 3..4)
        MusicCue.parse("Volume 1\\5.mp3") shouldBe MusicCue(trackNumber = 5, images = null)
    }

    @Test
    fun `a start after the stop is swapped`() {
        MusicCue.parse("9-3.mp3") shouldBe MusicCue(trackNumber = null, images = 3..9)
        MusicCue.parse("1 9 3.mp3") shouldBe MusicCue(trackNumber = 1, images = 3..9)
    }

    @Test
    fun `numbers too large for an Int are clamped`() {
        MusicCue.parse("3-99999999999.mp3") shouldBe MusicCue(trackNumber = null, images = 3..Int.MAX_VALUE)
        MusicCue.parse("99999999999.mp3") shouldBe MusicCue(trackNumber = Int.MAX_VALUE, images = null)
        MusicCue.parse("0000000000000000000007.mp3") shouldBe MusicCue(trackNumber = 7, images = null)
    }

    @Test
    fun `a range covers its start and stop images`() {
        val tracks = listOf(track("3-5.mp3"))

        tracks.playlistAt(2).shouldBeEmpty()
        tracks.playlistAt(3).names() shouldContainExactly listOf("3-5.mp3")
        tracks.playlistAt(5).names() shouldContainExactly listOf("3-5.mp3")
        tracks.playlistAt(6).shouldBeEmpty()
    }

    @Test
    fun `no tracks play nothing`() {
        emptyList<MusicTrack>().playlistAt(1).shouldBeEmpty()
    }

    @Test
    fun `a whole-chapter track covers every image`() {
        val tracks = listOf(track("Theme.mp3"))

        tracks.playlistAt(1).names() shouldContainExactly listOf("Theme.mp3")
        tracks.playlistAt(500).names() shouldContainExactly listOf("Theme.mp3")
    }

    @Test
    fun `a range overrides a whole-chapter track`() {
        val tracks = listOf(track("Theme.mp3"), track("1-4.mp3"))

        tracks.playlistAt(1).names() shouldContainExactly listOf("1-4.mp3")
        tracks.playlistAt(5).names() shouldContainExactly listOf("Theme.mp3")
    }

    @Test
    fun `a later range overrides an earlier overlapping one`() {
        val tracks = listOf(track("1-10.mp3"), track("5-6.mp3"), track("6-20.mp3"))

        tracks.playlistAt(4).names() shouldContainExactly listOf("1-10.mp3")
        tracks.playlistAt(5).names() shouldContainExactly listOf("5-6.mp3")
        tracks.playlistAt(6).names() shouldContainExactly listOf("6-20.mp3")
        tracks.playlistAt(11).names() shouldContainExactly listOf("6-20.mp3")
    }

    @Test
    fun `tracks with the same start play in track number order, unnumbered last`() {
        val tracks = listOf(track("b 1-9.mp3"), track("2 1 9.mp3"), track("a 1-5.mp3"), track("10 1 9.mp3"))

        tracks.playlistAt(1).names() shouldContainExactly listOf("2 1 9.mp3", "10 1 9.mp3", "a 1-5.mp3", "b 1-9.mp3")
    }

    @Test
    fun `whole-chapter tracks play in track number order, then in natural name order`() {
        val tracks = listOf(track("intro.mp3"), track("Song 10.mp3"), track("Ending.mp3"), track("Song 2.mp3"))

        tracks.playlistAt(1).names() shouldContainExactly listOf("Song 2.mp3", "Song 10.mp3", "Ending.mp3", "intro.mp3")
    }

    @Test
    fun `tracks without a track number play in natural, case-insensitive name order`() {
        val tracks = listOf(track("b/1-3.mp3"), track("a10/1-3.mp3"), track("A2/1-3.mp3"))

        tracks.playlistAt(2).names() shouldContainExactly listOf("A2/1-3.mp3", "a10/1-3.mp3", "b/1-3.mp3")
    }

    private fun track(name: String) = MusicTrack(name) { error("Not played in tests") }

    private fun List<MusicTrack>.names() = map { it.name }
}
