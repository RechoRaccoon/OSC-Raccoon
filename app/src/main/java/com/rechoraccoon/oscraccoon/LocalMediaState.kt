package com.rechoraccoon.oscraccoon

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.*

data class QueueItem(val track: LocalTrack, val isManual: Boolean = false)

object LocalMediaState {
    var tracks = mutableStateListOf<LocalTrack>()
    var playQueue = mutableStateListOf<LocalTrack>()
    var manualQueue = mutableStateListOf<LocalTrack>()
    var currentIndex by mutableStateOf(0)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var volume by mutableStateOf(1f)
    var isShuffle by mutableStateOf(false)
    var isLoop by mutableStateOf(false)
    var folderUri by mutableStateOf("")
    var currentPlaylistId by mutableStateOf(ALL_TRACKS_ID)

    private var mediaPlayer: MediaPlayer? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (mediaPlayer == null) mediaPlayer = MediaPlayer()
    }

    fun release() {
        mediaPlayer?.release(); mediaPlayer = null
    }

    val currentTrack: LocalTrack? get() = playQueue.getOrNull(currentIndex)

    fun loadTracks(newTracks: List<LocalTrack>) {
        tracks.clear(); tracks.addAll(newTracks)
        if (playQueue.isEmpty()) { playQueue.clear(); playQueue.addAll(newTracks) }
    }

    /** Load these tracks as the active queue and start playing immediately. */
    fun loadAndPlayPlaylist(newTracks: List<LocalTrack>) {
        if (newTracks.isEmpty()) return
        playQueue.clear(); playQueue.addAll(newTracks)
        currentIndex = 0; playTrack(0)
    }

    fun playTrack(index: Int) {
        val ctx = appContext ?: return
        val track = playQueue.getOrNull(index) ?: return
        currentIndex = index
        try {
            mediaPlayer?.reset()
            mediaPlayer?.setDataSource(ctx, track.uri)
            mediaPlayer?.prepare()
            mediaPlayer?.setVolume(volume, volume)
            mediaPlayer?.setOnCompletionListener { onTrackComplete() }
            mediaPlayer?.start()
            isPlaying = true
            durationMs = mediaPlayer?.duration?.toLong() ?: 0L
            // nowPlaying is derived from currentTrack in OSCRaccoonApp
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun playPause() {
        val mp = mediaPlayer ?: return
        if (isPlaying) { mp.pause(); isPlaying = false }
        else {
            if (!mp.isPlaying && currentTrack != null) playTrack(currentIndex)
            else { mp.start(); isPlaying = true }
        }
    }

    fun next() {
        if (manualQueue.isNotEmpty()) {
            val track = manualQueue.removeAt(0)
            playQueue.add(currentIndex + 1, track)
        }
        val next = if (isShuffle) (0 until playQueue.size).random()
        else (currentIndex + 1) % playQueue.size.coerceAtLeast(1)
        playTrack(next)
    }

    fun prev() {
        val prev = if (currentIndex > 0) currentIndex - 1 else playQueue.size - 1
        playTrack(prev)
    }

    fun seek(ms: Long) { mediaPlayer?.seekTo(ms.toInt()); positionMs = ms }

    fun changeVolume(v: Float) { volume = v; mediaPlayer?.setVolume(v, v) }

    fun updatePosition() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            positionMs = mp.currentPosition.toLong()
            durationMs = mp.duration.toLong().coerceAtLeast(0L)
        }
    }

    fun addToQueue(track: LocalTrack) { manualQueue.add(track) }
    fun removeFromQueue(index: Int) { if (index < manualQueue.size) manualQueue.removeAt(index) }

    fun toggleShuffle(enabled: Boolean) {
        isShuffle = enabled
        if (enabled) {
            val current = currentTrack
            val shuffled = playQueue.toMutableList()
            shuffled.shuffle()
            current?.let { ct -> shuffled.remove(ct); shuffled.add(0, ct) }
            playQueue.clear(); playQueue.addAll(shuffled); currentIndex = 0
        }
    }

    /** Update a track's metadata everywhere it appears in state. */
    fun updateTrackInfo(oldUri: Uri, newTrack: LocalTrack) {
        val ti = tracks.indexOfFirst { it.uri == oldUri }
        if (ti >= 0) tracks[ti] = newTrack
        val qi = playQueue.indexOfFirst { it.uri == oldUri }
        if (qi >= 0) playQueue[qi] = newTrack
        val mi = manualQueue.indexOfFirst { it.uri == oldUri }
        if (mi >= 0) manualQueue[mi] = newTrack
    }

    private fun onTrackComplete() {
        if (isLoop) { mediaPlayer?.seekTo(0); mediaPlayer?.start() } else next()
    }
}
