package com.rechoraccoon.oscraccoon

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {
    private const val PREFS_NAME = "osc_raccoon_prefs"
    private const val KEY_USERNAME = "lastfm_username"
    private const val KEY_TEMPLATE = "message_template"
    private const val KEY_CYCLING = "cycling_messages"
    private const val KEY_INTERVAL = "cycle_interval"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveUsername(context: Context, username: String) =
        prefs(context).edit().putString(KEY_USERNAME, username).apply()

    fun loadUsername(context: Context): String =
        prefs(context).getString(KEY_USERNAME, "") ?: ""

    fun saveTemplate(context: Context, template: String) =
        prefs(context).edit().putString(KEY_TEMPLATE, template).apply()

    fun loadTemplate(context: Context): String =
        prefs(context).getString(KEY_TEMPLATE, "🎵 {song} by {artist}\n{cycling}\n{time}") ?: "🎵 {song} by {artist}\n{cycling}\n{time}"

    fun saveCyclingMessages(context: Context, messages: List<String>) =
        prefs(context).edit().putString(KEY_CYCLING, messages.joinToString("|||")).apply()

    fun loadCyclingMessages(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CYCLING, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("|||")
    }

    fun saveInterval(context: Context, interval: Int) =
        prefs(context).edit().putInt(KEY_INTERVAL, interval).apply()

    fun loadInterval(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL, 5)

    fun saveHiddenMessages(context: Context, hidden: Set<Int>) =
        prefs(context).edit().putString("hidden_messages", hidden.joinToString(",")).apply()

    fun loadHiddenMessages(context: Context): Set<Int> {
        val raw = prefs(context).getString("hidden_messages", "") ?: ""
        return if (raw.isEmpty()) emptySet() else raw.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }
}
