package com.rechoraccoon.oscraccoon

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

const val ALL_TRACKS_ID = "all_tracks_default"

data class CyclingMessage(val text: String, val isHidden: Boolean = false)
data class Preset(val id: String, val name: String, val template: String, val messages: List<CyclingMessage>, val interval: Int = 1)
data class VirtualPlaylist(
    val id: String,
    val name: String,
    val trackUris: List<String> = emptyList(),
    val coverUri: String = "",
    val coverOffsetX: Float = 0f,
    val coverOffsetY: Float = 0f,
    val coverScale: Float = 1f,
    val sortOrder: Int = 0
)
data class TrackOverride(val title: String, val artist: String)

object AppPreferences {
    private const val PREFS_NAME = "osc_raccoon_prefs"
    private val gson = Gson()
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveUsername(context: Context, u: String) = prefs(context).edit().putString("lastfm_username", u).apply()
    fun loadUsername(context: Context): String = prefs(context).getString("lastfm_username", "") ?: ""

    fun saveTemplate(context: Context, t: String) = prefs(context).edit().putString("message_template", t).apply()
    fun loadTemplate(context: Context): String =
        prefs(context).getString("message_template", "🎵 {song} by {artist}\n{cycling}\n{time}")
            ?: "🎵 {song} by {artist}\n{cycling}\n{time}"

    fun saveCyclingMessages(context: Context, messages: List<CyclingMessage>) =
        prefs(context).edit().putString("cycling_messages_v2", gson.toJson(messages)).apply()

    fun loadCyclingMessages(context: Context): List<CyclingMessage> {
        val raw = prefs(context).getString("cycling_messages_v2", null) ?: return defaultMessages()
        return try {
            val type = object : TypeToken<List<CyclingMessage>>() {}.type
            gson.fromJson(raw, type) ?: defaultMessages()
        } catch (e: Exception) { defaultMessages() }
    }

    fun defaultMessages() = listOf(
        CyclingMessage("💚🦝💚🦝💚🦝💚🦝"),
        CyclingMessage("🦝💚🦝💚🦝💚🦝💚")
    )

    fun saveInterval(context: Context, i: Int) = prefs(context).edit().putInt("cycle_interval", i).apply()
    fun loadInterval(context: Context): Int = prefs(context).getInt("cycle_interval", 1)

    fun saveSourceMode(context: Context, mode: String) = prefs(context).edit().putString("source_mode", mode).apply()
    fun loadSourceMode(context: Context): String = prefs(context).getString("source_mode", "LOCAL") ?: "LOCAL"

    fun saveLocalFolderUri(context: Context, uri: String) = prefs(context).edit().putString("local_folder_uri", uri).apply()
    fun loadLocalFolderUri(context: Context): String = prefs(context).getString("local_folder_uri", "") ?: ""

    fun savePresets(context: Context, presets: List<Preset>) =
        prefs(context).edit().putString("presets", gson.toJson(presets)).apply()

    fun loadPresets(context: Context): List<Preset> {
        val raw = prefs(context).getString("presets", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Preset>>() {}.type
            val loaded: List<Preset> = gson.fromJson(raw, type) ?: emptyList()
            // Gson bypasses the constructor on deserialization, so presets saved before the
            // "interval" field existed come back with interval = 0 instead of the Kotlin
            // default of 1. Normalize those up to a sane minimum.
            loaded.map { if (it.interval <= 0) it.copy(interval = 1) else it }
        } catch (e: Exception) { emptyList() }
    }

    fun saveCurrentPresetId(context: Context, id: String?) =
        prefs(context).edit().putString("current_preset_id", id).apply()
    fun loadCurrentPresetId(context: Context): String? =
        prefs(context).getString("current_preset_id", null)

    fun saveAppMode(context: Context, mode: String) =
        prefs(context).edit().putString("app_mode", mode).apply()
    fun loadAppMode(context: Context): String =
        prefs(context).getString("app_mode", "chatbox") ?: "chatbox"

    fun savePlaylistGridColumns(context: Context, columns: Int) =
        prefs(context).edit().putInt("playlist_grid_columns", columns).apply()
    fun loadPlaylistGridColumns(context: Context): Int =
        prefs(context).getInt("playlist_grid_columns", 3)

    fun saveTrackOverrides(context: Context, overrides: Map<String, TrackOverride>) =
        prefs(context).edit().putString("track_overrides", gson.toJson(overrides)).apply()

    fun loadTrackOverrides(context: Context): Map<String, TrackOverride> {
        val raw = prefs(context).getString("track_overrides", null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, TrackOverride>>() {}.type
            gson.fromJson(raw, type) ?: emptyMap()
        } catch (e: Exception) { emptyMap() }
    }

    fun savePlaylists(context: Context, playlists: List<VirtualPlaylist>) =
        prefs(context).edit().putString("playlists", gson.toJson(playlists)).apply()

    /** Always returns with ALL_TRACKS_ID as the first element. */
    fun loadPlaylists(context: Context): List<VirtualPlaylist> {
        val raw = prefs(context).getString("playlists", null)
        val saved: List<VirtualPlaylist> = if (raw == null) emptyList() else try {
            val type = object : TypeToken<List<VirtualPlaylist>>() {}.type
            gson.fromJson(raw, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }

        val hasAllTracks = saved.any { it.id == ALL_TRACKS_ID }
        return if (hasAllTracks) saved
        else listOf(VirtualPlaylist(id = ALL_TRACKS_ID, name = "All Tracks")) + saved
    }
}
