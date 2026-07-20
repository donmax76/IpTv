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

    /** Set избранных URL-адресов. Старый API (используется адаптерами
     *  для проверки "это сердечко закрашено?"). Деривится из
     *  favoriteChannels. */
    var favorites: Set<String>
        get() = favoriteChannels.map { it.url }.toSet()
        set(value) {
            // Старый API set'тер использовался крайне редко (только в
            // одном месте после миграции). Удаляем те что не в value;
            // Channel-данные для НОВЫХ url не сохраняем (их и не должно
            // быть — set'тер служит только удалению).
            val current = favoriteChannels.toMutableList()
            current.removeAll { it.url !in value }
            favoriteChannels = current
        }

    /** Список избранных каналов с полными данными (name + url + logo +
     *  group + tvgId). Хранится как JSON. Так избранные сохраняются
     *  ВО ВСЕХ ПЛЕЙЛИСТАХ — после смены плейлиста ты не теряешь
     *  предыдущие избранные. */
    var favoriteChannels: List<Channel>
        get() {
            return try {
                // Кэш распарсенного списка (companion — см. per_channel_
                // state выше). Раньше геттер парсил весь favorite-JSON
                // на КАЖДЫЙ вызов, а его зовут: isFavorite на каждый
                // bind строки, каждый keystroke поиска в оверлее
                // (updateFavorites), каждый toggle (по 3-4 вызова).
                prefs.getString(KEY_FAVORITE_CHANNELS, null)?.let { json ->
                    synchronized(favCacheLock) {
                        if (json == cachedFavRaw) cachedFavList?.let { return it }
                    }
                }
                val json = prefs.getString(KEY_FAVORITE_CHANNELS, null) ?: run {
                    // Миграция со старого формата (Set<String> URLs):
                    // создаём Channel с минимальными данными чтобы хоть
                    // что-то показать. Имя по умолчанию = последняя
                    // часть URL.
                    val legacy = prefs.getStringSet(KEY_FAVORITES, null) ?: return@run null
                    if (legacy.isEmpty()) return@run "[]"
                    val arr = JSONArray()
                    legacy.forEach { url ->
                        arr.put(JSONObject().apply {
                            put("name", url.substringAfterLast('/').take(50))
                            put("url", url)
                        })
                    }
                    val text = arr.toString()
                    prefs.edit().putString(KEY_FAVORITE_CHANNELS, text).apply()
                    text
                }
                val arr = JSONArray(json)
                val parsed = (0 until arr.length()).map {
                    val obj = arr.getJSONObject(it)
                    Channel(
                        name = obj.optString("name"),
                        url = obj.optString("url"),
                        logoUrl = obj.optString("logo").ifBlank { null },
                        group = obj.optString("group").ifBlank { null },
                        tvgId = obj.optString("tvgId").ifBlank { null },
                        sourcePlaylist = obj.optString("src").ifBlank { null }
                    )
                }
                synchronized(favCacheLock) {
                    cachedFavRaw = json
                    cachedFavList = parsed
                }
                parsed
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { ch ->
                arr.put(JSONObject().apply {
                    put("name", ch.name)
                    put("url", ch.url)
                    if (!ch.logoUrl.isNullOrBlank()) put("logo", ch.logoUrl)
                    if (!ch.group.isNullOrBlank()) put("group", ch.group)
                    if (!ch.tvgId.isNullOrBlank()) put("tvgId", ch.tvgId)
                    if (!ch.sourcePlaylist.isNullOrBlank()) put("src", ch.sourcePlaylist)
                })
            }
            val serialized = arr.toString()
            synchronized(favCacheLock) {
                cachedFavRaw = serialized
                cachedFavList = value.toList()
            }
            prefs.edit().putString(KEY_FAVORITE_CHANNELS, serialized).apply()
        }

    fun addFavorite(channel: Channel) {
        if (channel.url.isBlank()) return
        // Если sourcePlaylist не передан — берём текущий открытый плейлист.
        // Так в Favorites будет видно из какого плейлиста канал.
        val withSource = if (channel.sourcePlaylist.isNullOrBlank()) {
            channel.copy(sourcePlaylist = lastPlaylistName?.takeIf { it.isNotBlank() })
        } else channel
        val list = favoriteChannels.toMutableList()
        val existingIdx = list.indexOfFirst { it.url == withSource.url }
        if (existingIdx < 0) {
            list.add(withSource)
        } else {
            // Перезаписываем существующую запись свежими данными.
            // Кейс: миграция со старого формата создавала запись где
            // name == URL fragment. Когда юзер заново добавит канал
            // (с полным Channel из плейлиста) — имя/лого/группа
            // обновятся на нормальные.
            list[existingIdx] = withSource
        }
        favoriteChannels = list
    }

    /** Enrich существующую favorite-запись данными из текущего
     *  плейлиста. Используется когда плейлист загружен и в нём
     *  есть канал с URL который уже в favorite, но с плохим именем
     *  (миграция) или без sourcePlaylist (Round 152 ввёл это поле,
     *  старые записи без него). Тогда мы автоматически проапгрейдим
     *  запись. */
    fun enrichFavorites(channels: List<Channel>) {
        if (channels.isEmpty()) return
        val playlistName = lastPlaylistName?.takeIf { it.isNotBlank() }
        val list = favoriteChannels.toMutableList()
        var changed = false
        for (i in list.indices) {
            val fav = list[i]
            val match = channels.firstOrNull { it.url == fav.url } ?: continue
            // Считаем что имя "плохое" если оно равно URL целиком,
            // содержит / или ., или оканчивается на .ts/.m3u8/etc.
            val nameLooksLikeUrl = fav.name.contains('/') ||
                fav.name.startsWith("http", true) ||
                fav.name == fav.url
            val needName = nameLooksLikeUrl
            val needLogo = fav.logoUrl.isNullOrBlank() && !match.logoUrl.isNullOrBlank()
            val needGroup = fav.group.isNullOrBlank() && !match.group.isNullOrBlank()
            val needTvgId = fav.tvgId.isNullOrBlank() && !match.tvgId.isNullOrBlank()
            val needSource = fav.sourcePlaylist.isNullOrBlank() && playlistName != null
            if (!needName && !needLogo && !needGroup && !needTvgId && !needSource) continue
            list[i] = fav.copy(
                name = if (needName) match.name else fav.name,
                logoUrl = fav.logoUrl ?: match.logoUrl,
                group = fav.group ?: match.group,
                tvgId = fav.tvgId ?: match.tvgId,
                sourcePlaylist = fav.sourcePlaylist ?: playlistName
            )
            changed = true
        }
        if (changed) favoriteChannels = list
    }

    /** Старая сигнатура — оставлена для обратной совместимости.
     *  Используется когда у нас только URL под рукой. Имя берём как
     *  fallback. */
    fun addFavorite(url: String) {
        if (url.isBlank()) return
        val list = favoriteChannels.toMutableList()
        if (list.none { it.url == url }) {
            list.add(Channel(
                name = url.substringAfterLast('/').take(50),
                url = url
            ))
            favoriteChannels = list
        }
    }

    fun removeFavorite(url: String) {
        favoriteChannels = favoriteChannels.filterNot { it.url == url }
    }

    fun isFavorite(url: String) = favoriteChannels.any { it.url == url }

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

    /** Кэш разрешений каналов: url → height. Заполняется PlayerActivity
     *  через onVideoSizeChanged когда юзер реально открывает канал.
     *  Адаптеры списка читают это и показывают точный бейдж качества
     *  (а не только из имени канала, которое может врать). */
    fun getChannelHeight(url: String): Int {
        if (url.isBlank()) return 0
        val state = getChannelState(url)
        return state.optInt("h", 0)
    }

    fun setChannelHeight(url: String, height: Int) {
        if (url.isBlank() || height <= 0) return
        val state = getChannelState(url)
        // Не обновляем если значение почти то же (избежать лишних writes).
        if (state.optInt("h", 0) == height) return
        state.put("h", height)
        saveChannelState(url, state)
    }

    /** True если последний просмотр шёл через вкладку "Избранные".
     *  Кнопка "Прямой эфир" при перезапуске открывает их (а не
     *  последний плейлист). Сбрасывается когда юзер открывает
     *  плейлист или включает канал из плейлиста. */
    var lastWasFavorites: Boolean
        get() = prefs.getBoolean("last_was_favorites", false)
        set(value) = prefs.edit().putBoolean("last_was_favorites", value).apply()

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

    /** Round 175: больше не используется (nextlib и libmpv удалены).
     *  Геттер/сеттер оставлены чтобы старые сохранённые значения
     *  не ломали SharedPreferences. */
    var forceSoftwareDecoder: Boolean
        get() = prefs.getBoolean("force_software_decoder", false)
        set(value) = prefs.edit().putBoolean("force_software_decoder", value).apply()

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

    /** Timestamp последнего УСПЕШНОГО запроса GitHub Releases API
     *  (System.currentTimeMillis()). Используется для троттлинга авто-
     *  проверки в MainActivity: если больше 6 часов с прошлой проверки,
     *  пробуем снова при возврате в активити. Это нужно потому что
     *  раньше проверка делалась только в onCreate(savedInstance==null),
     *  а на TV-боксах активити часто не пересоздаётся между
     *  background/foreground — пользователь жаловался что "обновление
     *  не приходит само на следующий день". */
    var lastUpdateCheckMs: Long
        get() = prefs.getLong("last_update_check_ms", 0L)
        set(value) = prefs.edit().putLong("last_update_check_ms", value).apply()

    // Round 228: кешируем последний известный апдейт. Если за TTL
    // (10 мин) splash увидит «апдейта нет» — следующий запуск пропустит
    // сетевой запрос вообще, юзер не увидит ни секунды splash'а.
    // Если апдейт был — храним versionCode + name + url + notes,
    // показываем диалог сразу из кэша.
    var cachedUpdateBuildCode: Int
        get() = prefs.getInt("cached_update_code", 0)
        set(v) = prefs.edit().putInt("cached_update_code", v).apply()
    var cachedUpdateVersionName: String
        get() = prefs.getString("cached_update_name", "") ?: ""
        set(v) = prefs.edit().putString("cached_update_name", v).apply()
    var cachedUpdateDownloadUrl: String
        get() = prefs.getString("cached_update_url", "") ?: ""
        set(v) = prefs.edit().putString("cached_update_url", v).apply()
    var cachedUpdateNotes: String
        get() = prefs.getString("cached_update_notes", "") ?: ""
        set(v) = prefs.edit().putString("cached_update_notes", v).apply()

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

    /** Round 382: показывать взрослые категории (18+, XXX). По умолчанию
     *  выключено — включение защищено PIN родительского контроля. */
    var showAdult: Boolean
        get() = prefs.getBoolean("show_adult_categories", false)
        set(value) = prefs.edit().putBoolean("show_adult_categories", value).apply()

    /** Round 382: мини-превью (PiP) выделенного канала при листании
     *  списка в плеере. По умолчанию выключено. */
    var listPreview: Boolean
        get() = prefs.getBoolean("list_pip_preview", false)
        set(value) = prefs.edit().putBoolean("list_pip_preview", value).apply()

    /** Referer header sent with every stream request. Blank → auto
     *  (stream's own scheme://host). Used to satisfy servers that
     *  enforce site origin (common on Azerbaijani / Russian IPTV). */
    var httpReferer: String
        get() = prefs.getString("http_referer", "") ?: ""
        set(value) = prefs.edit().putString("http_referer", value).apply()

    var epgLastUpdate: Long
        get() = prefs.getLong(KEY_EPG_LAST_UPDATE, 0L)
        set(value) = prefs.edit().putLong(KEY_EPG_LAST_UPDATE, value).apply()

    // Round 226a: универсальный флаг для одноразовых миграций
    // (например, удаление кэша EPG v4 при выкатке v5).
    fun getMigrationFlag(name: String): Boolean =
        prefs.getBoolean("migration_$name", false)
    fun setMigrationFlag(name: String, value: Boolean) {
        prefs.edit().putBoolean("migration_$name", value).apply()
    }

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

    /** Расширенная версия pushRecent: запоминает не только URL но и
     *  полный Channel (имя, лого, группа, sourcePlaylist). Так
     *  RecentFragment видит каналы даже из других плейлистов. */
    fun pushRecentChannel(channel: Channel) {
        if (channel.url.isBlank()) return
        // Старый Set<URL> для обратной совместимости.
        pushRecent(channel.url)
        // Новый снапшот.
        val withSrc = if (channel.sourcePlaylist.isNullOrBlank()) {
            channel.copy(sourcePlaylist = lastPlaylistName?.takeIf { it.isNotBlank() })
        } else channel
        val list = recentChannels.toMutableList()
        list.removeAll { it.url == channel.url }
        list.add(0, withSrc)
        recentChannels = list.take(MAX_RECENT)
    }

    /** Снапшоты последних просмотренных каналов с полными данными.
     *  Раньше был только Set<URL> и при просмотре из другого плейлиста
     *  канал в Recent отображался URL'ом без имени. */
    var recentChannels: List<Channel>
        get() {
            return try {
                val json = prefs.getString(KEY_RECENT_CHANNELS, null) ?: return emptyList()
                val arr = JSONArray(json)
                (0 until arr.length()).map {
                    val obj = arr.getJSONObject(it)
                    Channel(
                        name = obj.optString("name"),
                        url = obj.optString("url"),
                        logoUrl = obj.optString("logo").ifBlank { null },
                        group = obj.optString("group").ifBlank { null },
                        tvgId = obj.optString("tvgId").ifBlank { null },
                        sourcePlaylist = obj.optString("src").ifBlank { null }
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val arr = JSONArray()
            value.take(MAX_RECENT).forEach { ch ->
                arr.put(JSONObject().apply {
                    put("name", ch.name)
                    put("url", ch.url)
                    if (!ch.logoUrl.isNullOrBlank()) put("logo", ch.logoUrl)
                    if (!ch.group.isNullOrBlank()) put("group", ch.group)
                    if (!ch.tvgId.isNullOrBlank()) put("tvgId", ch.tvgId)
                    if (!ch.sourcePlaylist.isNullOrBlank()) put("src", ch.sourcePlaylist)
                })
            }
            prefs.edit().putString(KEY_RECENT_CHANNELS, arr.toString()).apply()
        }

    fun clearRecent() {
        prefs.edit()
            .remove(KEY_RECENT_URLS)
            .remove(KEY_RECENT_CHANNELS)
            .apply()
    }

    // HD/4K filter chip selection: "all", "4K", "FHD", "HD", "SD"
    var qualityFilter: String
        get() = prefs.getString(KEY_QUALITY_FILTER, "all") ?: "all"
        set(value) = prefs.edit().putString(KEY_QUALITY_FILTER, value).apply()

    /**
     * Per-channel User-Agent. Возвращает кастомный UA, если он задан
     * для канала, иначе глобальный prefs.userAgent. Применяется в
     * data-factory плеера вместо глобального.
     */
    fun getChannelUserAgent(url: String): String {
        val state = getChannelState(url)
        val custom = state.optString("ua", "").ifBlank { null }
        return custom ?: userAgent
    }

    fun setChannelUserAgent(url: String, ua: String?) {
        if (url.isBlank()) return
        val state = getChannelState(url)
        if (ua.isNullOrBlank()) state.remove("ua") else state.put("ua", ua)
        saveChannelState(url, state)
    }

    // Per-channel state: url -> JSONObject {speed, aspect, audio, pos, volume, ua}
    //
    // Кэшируем распарсенный блоб в companion (static): раньше КАЖДЫЙ
    // вызов getChannelState парсил ВЕСЬ per_channel_state JSON (до 200
    // записей, десятки КБ) заново — а его зовут onBindViewHolder ОБОИХ
    // адаптеров каналов (getChannelHeight на каждую строку при
    // скролле 4000-канального списка), ChannelSorter в режиме
    // "quality" (полный парс × КАЖДЫЙ канал = 4000 парсов на main
    // thread при нажатии «Эфир»), onVideoSizeChanged, каждое
    // переключение канала. Кэш сверяется по raw-строке — SharedPrefs
    // держит её в памяти, сравнение по identity почти бесплатное.
    private fun allChannelStates(): JSONObject {
        val raw = prefs.getString(KEY_PER_CHANNEL_STATE, "{}") ?: "{}"
        synchronized(stateCacheLock) {
            if (raw === cachedStateRaw || raw == cachedStateRaw) {
                cachedStateAll?.let { return it }
            }
            val all = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            cachedStateRaw = raw
            cachedStateAll = all
            return all
        }
    }

    fun getChannelState(url: String): JSONObject {
        if (url.isBlank()) return JSONObject()
        return try {
            val state = synchronized(stateCacheLock) {
                allChannelStates().optJSONObject(url)
            } ?: return JSONObject()
            // Копия: кэшированный объект общий, а вызывающие мутируют
            // возвращённый state (setChannelUserAgent и т.п.).
            JSONObject(state.toString())
        } catch (_: Exception) { JSONObject() }
    }

    fun saveChannelState(url: String, state: JSONObject) {
        if (url.isBlank()) return
        try {
            synchronized(stateCacheLock) {
                val all = allChannelStates()
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
                val serialized = all.toString()
                cachedStateRaw = serialized
                cachedStateAll = all
                prefs.edit().putString(KEY_PER_CHANNEL_STATE, serialized).apply()
            }
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

    // === Android Round 366: родительский контроль ===
    // ВАЖНО: set, который вернул getStringSet, НЕЛЬЗЯ мутировать —
    // всегда копия (документированное ограничение SharedPreferences).

    var parentalPinHash: String?
        get() = prefs.getString(KEY_PARENTAL_PIN_HASH, null)
        set(value) {
            if (value.isNullOrBlank()) prefs.edit().remove(KEY_PARENTAL_PIN_HASH).apply()
            else prefs.edit().putString(KEY_PARENTAL_PIN_HASH, value).apply()
        }

    var lockedCategories: Set<String>
        get() = prefs.getStringSet(KEY_LOCKED_CATEGORIES, emptySet())?.toSet()
            ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_LOCKED_CATEGORIES, value.toSet()).apply()
        }

    var lockedChannelUrls: Set<String>
        get() = prefs.getStringSet(KEY_LOCKED_CHANNELS, emptySet())?.toSet()
            ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_LOCKED_CHANNELS, value.toSet()).apply()
        }

    fun toggleChannelLock(url: String): Boolean {
        val cur = lockedChannelUrls.toMutableSet()
        val nowLocked = if (url in cur) { cur.remove(url); false }
                        else { cur.add(url); true }
        lockedChannelUrls = cur
        return nowLocked
    }

    // === Android Round 366: редактирование пользовательского плейлиста ===
    fun updateCustomPlaylist(index: Int, name: String, url: String) {
        val current = customPlaylists.toMutableList()
        if (index in current.indices) {
            current[index] = name to url
            customPlaylists = current
        }
    }

    companion object {
        // Кэш распарсенного per_channel_state — см. allChannelStates().
        // Static (companion), потому что AppPreferences создаётся
        // заново в каждом месте вызова, а SharedPreferences под ним —
        // один и тот же файл.
        private val stateCacheLock = Any()
        @Volatile private var cachedStateRaw: String? = null
        @Volatile private var cachedStateAll: JSONObject? = null

        // Кэш распарсенного favoriteChannels — см. геттер.
        private val favCacheLock = Any()
        @Volatile private var cachedFavRaw: String? = null
        @Volatile private var cachedFavList: List<Channel>? = null

        /** Built-in EPG sources used as a fallback when the user has none configured.
         *  Эти источники работают в OTT Navigator — значит сами по
         *  себе валидны. Round 59-62 настроили pipeline (download
         *  to disk + 3-min timeout + drop descriptions) так чтобы
         *  они корректно парсились на TV-боксах с 256MB heap.
         *  Используем УПРОЩЁННЫЙ epg.xml (без gzip, программа только
         *  на текущий день) — он специально сделан для слабых устройств
         *  и парсится быстро без OOM. */
        val DEFAULT_EPG_URLS: List<String> = listOf(
            "http://epg.it999.ru/epg.xml",
        )

        /** Готовый список EPG-источников для выбора в настройках. */
        val SUGGESTED_EPG_URLS: List<Pair<String, String>> = listOf(
            "epg.it999.ru/epg.xml — лёгкий, на сегодня (рекомендуется)" to
                "http://epg.it999.ru/epg.xml",
            "epg.it999.ru/edem.xml.gz — полный (Edem)" to
                "http://epg.it999.ru/edem.xml.gz",
            "epg.it999.ru/epg.xml.gz — полный (тёмный фон)" to
                "http://epg.it999.ru/epg.xml.gz",
            "epg.it999.ru/epg2.xml.gz — полный (прозрачный фон)" to
                "http://epg.it999.ru/epg2.xml.gz",
            "epg.it999.ru/pp.xml.gz — Perfect Player / ProgTV" to
                "http://epg.it999.ru/pp.xml.gz",
            "epg.it999.ru/ru.xml.gz — только русские каналы" to
                "http://epg.it999.ru/ru.xml.gz",
            "epg.it999.ru/ru2.xml.gz — русские (прозрачные пиконы)" to
                "http://epg.it999.ru/ru2.xml.gz",
            "epg.it999.ru/rupp.xml.gz — русские для Perfect Player" to
                "http://epg.it999.ru/rupp.xml.gz",
            "iptvx.one/epg/epg.xml.gz — мульти-регион (тяжёлый)" to
                "https://iptvx.one/epg/epg.xml.gz",
            "iptvx.one/epg/epg_lite.xml.gz — лёгкая версия iptvx" to
                "http://iptvx.one/epg/epg_lite.xml.gz",
            "teleguide.info — JTV формат (zip)" to
                "http://www.teleguide.info/download/new3/jtv.zip",
            "programtv.ru/xmltv.xml.gz" to
                "http://programtv.ru/xmltv.xml.gz",
            "ottepg.ru/ottepg.xml.gz" to
                "https://ottepg.ru/ottepg.xml.gz",
            "stb.shara-tv.org/epg/epgtv.xml.gz" to
                "http://stb.shara-tv.org/epg/epgtv.xml.gz",
            "st.kineskop.tv/epg.xml.gz" to
                "http://st.kineskop.tv/epg.xml.gz",
            "webarmen.com/my/iptv/xmltv.xml.gz" to
                "https://webarmen.com/my/iptv/xmltv.xml.gz",
            "static.mediatech.by/epg.xml" to
                "https://static.mediatech.by/epg.xml",
        )

        private const val PREFS_NAME = "tvviewer_prefs"
        private const val KEY_PLAYER = "player_type"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_CUSTOM_PLAYLISTS = "custom_playlists"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_FAVORITE_CHANNELS = "favorite_channels"
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
        private const val KEY_RECENT_CHANNELS = "recent_channels"
        private const val KEY_QUALITY_FILTER = "quality_filter"
        private const val KEY_PER_CHANNEL_STATE = "per_channel_state"
        private const val KEY_ADDITIONAL_EPG_URLS = "additional_epg_urls"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_PARENTAL_PIN_HASH = "parental_pin_hash"
        private const val KEY_LOCKED_CATEGORIES = "parental_locked_categories"
        private const val KEY_LOCKED_CHANNELS = "parental_locked_channels"
        private const val MAX_RECENT = 30
        private const val DEFAULT_UPDATE_CHECK_URL = "https://raw.githubusercontent.com/donmax76/TestApp/master/TVViewer/version.json"
        // VLC user-agent — many regional IPTV portals (izone.az,
        // ucoz.ru, restream services, etc.) explicitly whitelist VLC and
        // serve 403 to bare ExoPlayer. VLC is the de-facto standard so
        // safer as a default.
        const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

        const val PLAYER_INTERNAL = "internal"
        const val PLAYER_EXTERNAL = "external"
    }
}
