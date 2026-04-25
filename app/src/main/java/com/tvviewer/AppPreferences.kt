package com.tvviewer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

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

    var screenOrientation: String
        get() = prefs.getString(KEY_ORIENTATION, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_ORIENTATION, value).apply()

    var autoplayLast: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()

    var epgAutoUpdate: Boolean
        get() = prefs.getBoolean(KEY_EPG_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_EPG_AUTO_UPDATE, value).apply()

    var showBuiltInPlaylists: Boolean
        get() = prefs.getBoolean("show_builtin_playlists", true)
        set(value) = prefs.edit().putBoolean("show_builtin_playlists", value).apply()

    var epgLastUpdate: Long
        get() = prefs.getLong(KEY_EPG_LAST_UPDATE, 0L)
        set(value) = prefs.edit().putLong(KEY_EPG_LAST_UPDATE, value).apply()

    var channelSort: String
        get() = prefs.getString(KEY_CHANNEL_SORT, "default") ?: "default"
        set(value) = prefs.edit().putString(KEY_CHANNEL_SORT, value).apply()

    var sleepTimerMinutes: Int
        get() = prefs.getInt(KEY_SLEEP_TIMER, 0)
        set(value) = prefs.edit().putInt(KEY_SLEEP_TIMER, value).apply()

    var parentalPin: String?
        get() = prefs.getString(KEY_PARENTAL_PIN, null)
        set(value) = prefs.edit().putString(KEY_PARENTAL_PIN, value).apply()

    var lastEpgUrl: String?
        get() = prefs.getString(KEY_LAST_EPG_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_EPG_URL, value).apply()

    var colorTheme: String
        get() = prefs.getString(KEY_COLOR_THEME, "purple") ?: "purple"
        set(value) = prefs.edit().putString(KEY_COLOR_THEME, value).apply()

    var lastSelectedGroup: String?
        get() = prefs.getString(KEY_LAST_GROUP, null)
        set(value) = prefs.edit().putString(KEY_LAST_GROUP, value).apply()

    var lastPlaylistName: String?
        get() = prefs.getString(KEY_LAST_PLAYLIST_NAME, null)
        set(value) = prefs.edit().putString(KEY_LAST_PLAYLIST_NAME, value).apply()

    // Recent channels: most-recent first, capped at 30.
    var recentUrls: List<String>
        get() {
            return try {
                val json = prefs.getString(KEY_RECENT_URLS, "[]") ?: "[]"
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val arr = JSONArray()
            value.take(MAX_RECENT).forEach { arr.put(it) }
            prefs.edit().putString(KEY_RECENT_URLS, arr.toString()).apply()
        }

    fun pushRecent(url: String) {
        if (url.isBlank()) return
        val list = recentUrls.toMutableList()
        list.removeAll { it == url }
        list.add(0, url)
        recentUrls = list.take(MAX_RECENT)
    }

    fun clearRecent() {
        prefs.edit().remove(KEY_RECENT_URLS).apply()
    }

    // HD/4K filter chip selection: "all", "4K", "FHD", "HD", "SD"
    var qualityFilter: String
        get() = prefs.getString(KEY_QUALITY_FILTER, "all") ?: "all"
        set(value) = prefs.edit().putString(KEY_QUALITY_FILTER, value).apply()

    // Per-channel state: url -> JSONObject {speed, aspect, audio, pos, volume}
    fun getChannelState(url: String): JSONObject {
        if (url.isBlank()) return JSONObject()
        val raw = prefs.getString(KEY_PER_CHANNEL_STATE, "{}") ?: "{}"
        return try {
            val all = JSONObject(raw)
            all.optJSONObject(url) ?: JSONObject()
        } catch (_: Exception) { JSONObject() }
    }

    fun saveChannelState(url: String, state: JSONObject) {
        if (url.isBlank()) return
        try {
            val raw = prefs.getString(KEY_PER_CHANNEL_STATE, "{}") ?: "{}"
            val all = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            all.put(url, state)
            // Keep map size bounded - drop oldest if over 200 entries.
            if (all.length() > 200) {
                val keys = all.keys()
                val toDelete = mutableListOf<String>()
                var i = 0
                while (keys.hasNext() && i < all.length() - 200) {
                    toDelete.add(keys.next())
                    i++
                }
                toDelete.forEach { all.remove(it) }
            }
            prefs.edit().putString(KEY_PER_CHANNEL_STATE, all.toString()).apply()
        } catch (_: Exception) {}
    }

    // Multi-EPG: additional URLs (in addition to lastEpgUrl primary).
    var additionalEpgUrls: List<String>
        get() {
            return try {
                val json = prefs.getString(KEY_ADDITIONAL_EPG_URLS, "[]") ?: "[]"
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { if (it.isNotBlank()) arr.put(it) }
            prefs.edit().putString(KEY_ADDITIONAL_EPG_URLS, arr.toString()).apply()
        }

    fun addEpgUrl(url: String) {
        if (url.isBlank()) return
        val list = additionalEpgUrls.toMutableList()
        if (url !in list && url != lastEpgUrl) {
            list.add(url)
            additionalEpgUrls = list
        }
    }

    fun removeEpgUrl(url: String) {
        additionalEpgUrls = additionalEpgUrls.filterNot { it == url }
    }

    fun allEpgUrls(): List<String> {
        val out = mutableListOf<String>()
        lastEpgUrl?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        additionalEpgUrls.forEach { if (it !in out) out.add(it) }
        // Built-in defaults are used only when the user hasn't provided
        // any source AND the playlist itself has no x-tvg-url. This keeps
        // playback startup fast for users with a working primary source.
        if (out.isEmpty()) {
            DEFAULT_EPG_URLS.forEach { if (it !in out) out.add(it) }
        }
        return out
    }

    var userAgent: String
        get() = prefs.getString(KEY_USER_AGENT, DEFAULT_USER_AGENT) ?: DEFAULT_USER_AGENT
        set(value) {
            val v = value.trim().ifEmpty { DEFAULT_USER_AGENT }
            prefs.edit().putString(KEY_USER_AGENT, v).apply()
        }

    companion object {
        /** Built-in EPG sources used as a fallback when the user has none configured.
         *  Covers common Russian / CIS / general iptv-org channels by name. */
        val DEFAULT_EPG_URLS: List<String> = listOf(
            "http://epg.it999.ru/edem.xml.gz",
            "https://iptvx.one/epg/epg.xml.gz",
        )

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
        private const val KEY_ORIENTATION = "screen_orientation"
        private const val KEY_AUTOPLAY = "autoplay_last"
        private const val KEY_EPG_AUTO_UPDATE = "epg_auto_update"
        private const val KEY_EPG_LAST_UPDATE = "epg_last_update"
        private const val KEY_CHANNEL_SORT = "channel_sort"
        private const val KEY_SLEEP_TIMER = "sleep_timer"
        private const val KEY_PARENTAL_PIN = "parental_pin"
        private const val KEY_LAST_EPG_URL = "last_epg_url"
        private const val KEY_COLOR_THEME = "color_theme"
        private const val KEY_LAST_GROUP = "last_selected_group"
        private const val KEY_LAST_PLAYLIST_NAME = "last_playlist_name"
        private const val KEY_RECENT_URLS = "recent_urls"
        private const val KEY_QUALITY_FILTER = "quality_filter"
        private const val KEY_PER_CHANNEL_STATE = "per_channel_state"
        private const val KEY_ADDITIONAL_EPG_URLS = "additional_epg_urls"
        private const val KEY_USER_AGENT = "user_agent"
        private const val MAX_RECENT = 30
        private const val DEFAULT_UPDATE_CHECK_URL = "https://raw.githubusercontent.com/donmax76/TestApp/master/TVViewer/version.json"
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"

        const val PLAYER_INTERNAL = "internal"
        const val PLAYER_EXTERNAL = "external"
    }
}
