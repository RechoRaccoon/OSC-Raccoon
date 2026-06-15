package com.rechoraccoon.oscraccoon

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class CyclingMessage(val text: String, val isHidden: Boolean = false)
data class Preset(val id: String, val name: String, val template: String, val messages: List<CyclingMessage>)
data class VirtualPlaylist(
    val id: String,
    val name: String,
    val trackUris: List<String> = emptyList(),
    val coverUri: String = "",
    val sortOrder: Int = 0
)

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
    fun loadSourceMode(context: Context): String = prefs(context).getString("source_mode", "LASTFM") ?: "LASTFM"

    fun saveLocalFolderUri(context: Context, uri: String) = prefs(context).edit().putString("local_folder_uri", uri).apply()
    fun loadLocalFolderUri(context: Context): String = prefs(context).getString("local_folder_uri", "") ?: ""

    fun savePresets(context: Context, presets: List<Preset>) =
        prefs(context).edit().putString("presets", gson.toJson(presets)).apply()

    fun loadPresets(context: Context): List<Preset> {
        val raw = prefs(context).getString("presets", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Preset>>() {}.type
            gson.fromJson(raw, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun savePlaylists(context: Context, playlists: List<VirtualPlaylist>) =
        prefs(context).edit().putString("playlists", gson.toJson(playlists)).apply()

    fun loadPlaylists(context: Context): List<VirtualPlaylist> {
        val raw = prefs(context).getString("playlists", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<VirtualPlaylist>>() {}.type
            gson.fromJson(raw, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }
}
