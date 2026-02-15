package com.tvviewer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var playerType: String
        get() = prefs.getString(KEY_PLAYER, PLAYER_INTERNAL) ?: PLAYER_INTERNAL
        set(value) = prefs.edit().putString(KEY_PLAYER, value).apply()

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var customPlaylists: List<Pair<String, String>>
        get() {
            return try {
                val json = prefs.getString(KEY_CUSTOM_PLAYLISTS, "[]") ?: "[]"
                val arr = JSONArray(json)
                (0 until arr.length()).map {
                    val obj = arr.getJSONObject(it)
                    obj.getString("name") to obj.getString("url")
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { (name, url) ->
                arr.put(org.json.JSONObject().apply {
                    put("name", name)
                    put("url", url)
                })
            }
            prefs.edit().putString(KEY_CUSTOM_PLAYLISTS, arr.toString()).apply()
        }

    fun addCustomPlaylist(name: String, url: String) {
        val current = customPlaylists.toMutableList()
        current.add(name to url)
        customPlaylists = current
    }

    fun removeCustomPlaylist(index: Int) {
        val current = customPlaylists.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            customPlaylists = current
        }
    }

    var favorites: Set<String>
        get() = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FAVORITES, value).apply()

    fun addFavorite(url: String) {
        favorites = favorites + url
    }

    fun removeFavorite(url: String) {
        favorites = favorites - url
    }

    fun isFavorite(url: String) = url in favorites

    var crashReportUrl: String?
        get() = prefs.getString(KEY_CRASH_URL, null)
        set(value) = prefs.edit().putString(KEY_CRASH_URL, value).apply()

    var crashReportFirebaseId: String?
        get() = prefs.getString(KEY_CRASH_FIREBASE, null)
        set(value) = prefs.edit().putString(KEY_CRASH_FIREBASE, value).apply()

    companion object {
        private const val PREFS_NAME = "tvviewer_prefs"
        private const val KEY_PLAYER = "player_type"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_CUSTOM_PLAYLISTS = "custom_playlists"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_CRASH_URL = "crash_report_url"
        private const val KEY_CRASH_FIREBASE = "crash_report_firebase"

        const val PLAYER_INTERNAL = "internal"
        const val PLAYER_EXTERNAL = "external"
    }
}
