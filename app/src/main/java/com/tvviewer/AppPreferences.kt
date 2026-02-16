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

    fun addCustomPlaylists(items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        val current = customPlaylists.toMutableList()
        current.addAll(items)
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

    var lastPlaylistUrl: String?
        get() = prefs.getString(KEY_LAST_PLAYLIST, null)
        set(value) = prefs.edit().putString(KEY_LAST_PLAYLIST, value).apply()

    var lastCategoryIndex: Int
        get() = prefs.getInt(KEY_LAST_CATEGORY, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_CATEGORY, value).apply()

    var lastChannelUrl: String?
        get() = prefs.getString(KEY_LAST_CHANNEL, null)
        set(value) = prefs.edit().putString(KEY_LAST_CHANNEL, value).apply()

    var isFullscreen: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN, value).apply()

    var preferredQuality: String
        get() = prefs.getString(KEY_QUALITY, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_QUALITY, value).apply()

    var customChannels: List<Pair<String, String>>
        get() {
            return try {
                val json = prefs.getString(KEY_CUSTOM_CHANNELS, "[]") ?: "[]"
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
            prefs.edit().putString(KEY_CUSTOM_CHANNELS, arr.toString()).apply()
        }

    fun addCustomChannel(name: String, url: String) {
        customChannels = customChannels + (name to url)
    }

    fun removeCustomChannel(index: Int) {
        val list = customChannels.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            customChannels = list
        }
    }

    var bufferMode: String
        get() = prefs.getString(KEY_BUFFER, "normal") ?: "normal"
        set(value) = prefs.edit().putString(KEY_BUFFER, value).apply()

    var listDisplayMode: String
        get() = prefs.getString(KEY_LIST_DISPLAY, "list") ?: "list"
        set(value) = prefs.edit().putString(KEY_LIST_DISPLAY, value).apply()

    var channelListAutoHideSeconds: Int
        get() = prefs.getInt(KEY_LIST_AUTOHIDE, 5).coerceIn(2, 30)
        set(value) = prefs.edit().putInt(KEY_LIST_AUTOHIDE, value.coerceIn(2, 30)).apply()

    var timeDisplayPosition: String
        get() = prefs.getString(KEY_TIME_DISPLAY, "off") ?: "off"
        set(value) = prefs.edit().putString(KEY_TIME_DISPLAY, value).apply()

    var updateCheckUrl: String?
        get() = prefs.getString(KEY_UPDATE_CHECK_URL, null)
            ?: DEFAULT_UPDATE_CHECK_URL
        set(value) = prefs.edit().putString(KEY_UPDATE_CHECK_URL, value?.takeIf { it.isNotEmpty() }).apply()

    fun getUpdateCheckUrlRaw(): String? = prefs.getString(KEY_UPDATE_CHECK_URL, null)

    companion object {
        private const val PREFS_NAME = "tvviewer_prefs"
        private const val KEY_PLAYER = "player_type"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_CUSTOM_PLAYLISTS = "custom_playlists"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_CRASH_URL = "crash_report_url"
        private const val KEY_CRASH_FIREBASE = "crash_report_firebase"
        private const val KEY_LAST_PLAYLIST = "last_playlist_url"
        private const val KEY_LAST_CATEGORY = "last_category"
        private const val KEY_LAST_CHANNEL = "last_channel_url"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_QUALITY = "preferred_quality"
        private const val KEY_CUSTOM_CHANNELS = "custom_channels"
        private const val KEY_BUFFER = "buffer_mode"
        private const val KEY_LIST_DISPLAY = "list_display"
        private const val KEY_LIST_AUTOHIDE = "list_autohide"
        private const val KEY_TIME_DISPLAY = "time_display"
        private const val KEY_UPDATE_CHECK_URL = "update_check_url"
        private const val DEFAULT_UPDATE_CHECK_URL = "https://raw.githubusercontent.com/donmax76/TestApp/master/TVViewer/version.json"

        const val PLAYER_INTERNAL = "internal"
        const val PLAYER_EXTERNAL = "external"
    }
}
