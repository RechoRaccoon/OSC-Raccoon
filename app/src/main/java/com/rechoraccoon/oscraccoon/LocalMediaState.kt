package com.rechoraccoon.oscraccoon

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.*

object LocalMediaState {
    var tracks = mutableStateListOf<LocalTrack>()
    var playQueue = mutableStateListOf<LocalTrack>()
    var manualQueue = mutableStateListOf<LocalTrack>()
    var currentIndex by mutableStateOf(0)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    // Default 0.5 = middle of slider. Actual audio = volume * 0.5f so max real vol is 50%
    var volume by mutableStateOf(0.5f)
    var isShuffle by mutableStateOf(false)
    var isLoop by mutableStateOf(false)
    // Incremented whenever track metadata changes — used as a recomposition key
    var tracksVersion by mutableStateOf(0)

    private var mediaPlayer: MediaPlayer? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (mediaPlayer == null) mediaPlayer = MediaPlayer()
    }

    fun release() { mediaPlayer?.release(); mediaPlayer = null }

    val currentTrack: LocalTrack? get() = playQueue.getOrNull(currentIndex)

    fun loadTracks(newTracks: List<LocalTrack>) {
        tracks.clear(); tracks.addAll(newTracks)
        if (playQueue.isEmpty()) { playQueue.clear(); playQueue.addAll(newTracks) }
    }

    /** Replace the play queue with newTracks and start playing at startIndex. Tracks which playlist this came from. */
    fun loadAndPlayPlaylist(newTracks: List<LocalTrack>, startIndex: Int = 0, playlistId: String = ALL_TRACKS_ID) {
        if (newTracks.isEmpty()) return
        currentPlaylistId = playlistId
        playQueue.clear(); playQueue.addAll(newTracks)
        playTrack(startIndex.coerceIn(0, newTracks.size - 1))
    }

    fun playTrack(index: Int) {
        val ctx = appContext ?: return
        val track = playQueue.getOrNull(index) ?: return
        currentIndex = index
        try {
            mediaPlayer?.reset()
            mediaPlayer?.setDataSource(ctx, track.uri)
            mediaPlayer?.prepare()
            // Actual volume = slider * 0.5 so slider at 1.0 = 50% real volume
            mediaPlayer?.setVolume(volume * 0.5f, volume * 0.5f)
            mediaPlayer?.setOnCompletionListener { onTrackComplete() }
            mediaPlayer?.start()
            isPlaying = true
            durationMs = mediaPlayer?.duration?.toLong() ?: 0L
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun playPause() {
        val mp = mediaPlayer ?: return
        if (isPlaying) { try { mp.pause() } catch (e: Exception) {}; isPlaying = false }
        else {
            // If the player was never prepared for the current track (e.g. first Play
            // press in Chatbox/Local mode before any track was loaded), prepare it via
            // playTrack() instead of blindly calling start(), which would silently fail
            // and look like the same track restarting over and over.
            try {
                mp.start(); isPlaying = true
            } catch (e: Exception) {
                if (currentTrack != null) playTrack(currentIndex)
                else if (playQueue.isNotEmpty()) playTrack(0)
            }
        }
    }

    fun next() {
        if (manualQueue.isNotEmpty()) {
            val track = manualQueue.removeAt(0)
            if (currentIndex + 1 <= playQueue.size) playQueue.add(currentIndex + 1, track)
            else playQueue.add(track)
        }
        val next = if (isShuffle && playQueue.size > 1) {
            var r: Int; do { r = (0 until playQueue.size).random() } while (r == currentIndex); r
        } else (currentIndex + 1) % playQueue.size.coerceAtLeast(1)
        playTrack(next)
    }

    fun prev() {
        val prev = if (currentIndex > 0) currentIndex - 1 else (playQueue.size - 1).coerceAtLeast(0)
        playTrack(prev)
    }

    fun seek(ms: Long) { mediaPlayer?.seekTo(ms.toInt()); positionMs = ms }

    fun changeVolume(v: Float) {
        volume = v
        mediaPlayer?.setVolume(v * 0.5f, v * 0.5f)
    }

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
            val shuffled = playQueue.toMutableList().also { it.shuffle() }
            current?.let { ct -> shuffled.remove(ct); shuffled.add(0, ct) }
            playQueue.clear(); playQueue.addAll(shuffled); currentIndex = 0
        }
    }

    /** Update track info everywhere in state. Call tracksVersion++ to force UI refresh. */
    fun updateTrackInfo(oldUri: Uri, newTrack: LocalTrack) {
        val ti = tracks.indexOfFirst { it.uri == oldUri }
        if (ti >= 0) tracks[ti] = newTrack
        val qi = playQueue.indexOfFirst { it.uri == oldUri }
        if (qi >= 0) playQueue[qi] = newTrack
        val mi = manualQueue.indexOfFirst { it.uri == oldUri }
        if (mi >= 0) manualQueue[mi] = newTrack
        tracksVersion++
    }

    private fun onTrackComplete() {
        if (isLoop) { mediaPlayer?.seekTo(0); mediaPlayer?.start() } else next()
    }
}
