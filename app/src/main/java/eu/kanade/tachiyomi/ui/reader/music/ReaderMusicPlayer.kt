package eu.kanade.tachiyomi.ui.reader.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.UUID

/**
 * Plays the music of the image the reader last reported. Used from the main thread only; [scope] must run on it.
 */
class ReaderMusicPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    // A reader reopened at once can create its player before the previous one is released
    private val cacheDir = File(context.cacheDir, "reader_music")
        .apply { deleteRecursively() }
        .resolve(UUID.randomUUID().toString())

    private val audioManager = context.getSystemService<AudioManager>()!!

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AUDIO_ATTRIBUTES)
        .setOnAudioFocusChangeListener(::onAudioFocusChange, Handler(Looper.getMainLooper()))
        .setAcceptsDelayedFocusGain(true)
        .build()

    private var enabled = false
    private var loop = false

    private var chapterId = -1L
    private var tracks = emptyList<MusicTrack>()
    private var image = 0

    private var activeSet: Pair<Long, List<String>>? = null
    private var playlist = emptyList<MusicTrack>()
    private var position = 0
    private val failed = mutableSetOf<Int>()
    private var cachedChapterId: Long? = null

    private var player: MediaPlayer? = null
    private var preparing: Job? = null

    private var paused = false
    private var focusLost = false
    private var outputLost = false

    // Headphones or a Bluetooth speaker went away; keep the music from moving to the phone's speaker
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            outputLost = true
            updatePlayback()
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun onPageSelected(page: ReaderPage) {
        chapterId = page.chapter.chapter.id
        tracks = page.chapter.pageLoader?.music.orEmpty()
        image = page.number
        update()
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        update()
    }

    fun setLoop(loop: Boolean) {
        this.loop = loop
        player?.isLooping = isLoopingTrack()
        // The playlist ended while loop was off
        if (loop && player == null && preparing == null) playlist.indices.firstOrNull { it !in failed }?.let(::play)
    }

    fun pause() {
        paused = true
        updatePlayback()
        abandonFocus()
    }

    fun resume() {
        if (!paused) return
        paused = false
        updatePlayback()
    }

    fun release() {
        context.unregisterReceiver(noisyReceiver)
        releasePlayer()
        abandonFocus()
        cacheDir.deleteRecursively()
    }

    private fun update() {
        val playlist = if (enabled) tracks.playlistAt(image) else emptyList()
        val set = playlist.takeIf { it.isNotEmpty() }?.let { chapterId to it.map(MusicTrack::name) }
        if (set == activeSet) return

        releasePlayer()
        activeSet = set
        this.playlist = playlist
        failed.clear()
        if (playlist.isEmpty()) {
            abandonFocus()
        } else {
            // Neither a permanent focus loss nor a lost output reports its end, so new music starts afresh
            focusLost = false
            outputLost = false
            play(0)
        }
    }

    private fun play(index: Int) {
        releasePlayer()
        position = index
        val track = playlist[index]
        val chapterId = chapterId
        preparing = scope.launch {
            // Only the chapter being read keeps its copies; deleted before extracting so the two never overlap
            if (chapterId != cachedChapterId) {
                withContext(Dispatchers.IO) { cacheDir.listFiles()?.forEach(File::delete) }
                cachedChapterId = chapterId
            }
            player = try {
                prepare(track)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Couldn't play ${track.name}" }
                failed += index
                playNext()
                return@launch
            }
            updatePlayback()
        }
    }

    private suspend fun prepare(track: MusicTrack): MediaPlayer {
        val uri = track.uri(cacheDir)
        // Created on the main thread, so its listeners are called there
        val player = MediaPlayer()
        try {
            withContext(Dispatchers.IO) {
                player.setAudioAttributes(AUDIO_ATTRIBUTES)
                player.setDataSource(context, uri)
                player.prepare()
            }
        } catch (e: Throwable) {
            player.release()
            throw e
        }
        player.isLooping = isLoopingTrack()
        player.setOnCompletionListener { playNext() }
        player.setOnErrorListener { _, what, extra ->
            logcat(LogPriority.WARN) { "Couldn't play ${track.name} ($what, $extra)" }
            failed += position
            playNext()
            true
        }
        return player
    }

    private fun isLoopingTrack() = loop && playlist.size == 1

    private fun playNext() {
        val following = (position + 1..playlist.lastIndex) + if (loop) 0..position else IntRange.EMPTY
        val next = following.firstOrNull { it !in failed }
        if (next != null) {
            play(next)
        } else {
            releasePlayer()
            abandonFocus()
        }
    }

    private fun updatePlayback() {
        val player = player ?: return
        if (!paused && !focusLost && !outputLost && requestFocus()) {
            player.start()
        } else if (player.isPlaying) {
            player.pause()
        }
    }

    private fun releasePlayer() {
        preparing?.cancel()
        preparing = null
        player?.release()
        player = null
    }

    private fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> focusLost = false
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusLost = true
            // The system ducks the volume itself
            else -> return
        }
        updatePlayback()
    }

    private fun requestFocus(): Boolean {
        return when (audioManager.requestAudioFocus(focusRequest)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> true
            // Granted through AUDIOFOCUS_GAIN once the call ends
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                focusLost = true
                false
            }
            else -> false
        }
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        // An abandoned request gets no AUDIOFOCUS_GAIN
        focusLost = false
    }

    companion object {
        private val AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
