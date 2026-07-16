package com.tvviewer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Round 380: локальный кэш распарсенного плейлиста на диске.
 *
 * Проблема, которую решает: при ХОЛОДНОМ старте (в частности — сразу
 * ПОСЛЕ ОБНОВЛЕНИЯ, когда старый процесс убит установкой нового APK и
 * память пуста) приложение заново качало весь плейлист из сети + парсило
 * + сортировало 3000-4000 каналов — это занимало до минуты. При обычном
 * же перезапуске (процесс жив, ChannelDataHolder.allChannels ещё в
 * памяти) канал открывался мгновенно.
 *
 * Теперь список каналов сохраняется в filesDir и при старте читается с
 * диска за доли секунды — последний канал открывается сразу, а свежая
 * версия плейлиста подтягивается позже при обычной навигации.
 */
object ChannelCache {
    private const val FILE = "channels_cache_v1.json"

    /** Сохраняет список каналов последнего загруженного плейлиста.
     *  Пишем атомарно (tmp + rename), чтобы прерванная запись не оставила
     *  битый файл. Вызывать из фонового потока. */
    fun save(context: Context, playlistUrl: String, channels: List<Channel>) {
        try {
            if (channels.isEmpty()) return
            val arr = JSONArray()
            for (c in channels) {
                val o = JSONObject()
                o.put("n", c.name)
                o.put("u", c.url)
                c.logoUrl?.let { o.put("l", it) }
                c.group?.let { o.put("g", it) }
                c.tvgId?.let { o.put("t", it) }
                c.sourcePlaylist?.let { o.put("s", it) }
                arr.put(o)
            }
            val root = JSONObject()
            root.put("url", playlistUrl)
            root.put("channels", arr)
            val dir = context.filesDir
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(root.toString())
            val dst = File(dir, FILE)
            if (!tmp.renameTo(dst)) {
                // renameTo может не сработать поверх существующего файла на
                // некоторых FS — удаляем и пробуем снова.
                dst.delete()
                tmp.renameTo(dst)
            }
        } catch (_: Throwable) {
            // Кэш — оптимизация, не критично: молча игнорируем ошибки.
        }
    }

    data class Cached(val playlistUrl: String, val channels: List<Channel>)

    /** Читает кэш плейлиста. Вызывать из фонового потока — на большом
     *  плейлисте парсинг JSON заметен. */
    fun load(context: Context): Cached? {
        return try {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return null
            val root = JSONObject(f.readText())
            val url = root.optString("url", "")
            val arr = root.optJSONArray("channels") ?: return null
            val list = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("n", "")
                val chUrl = o.optString("u", "")
                if (chUrl.isBlank()) continue
                list.add(
                    Channel(
                        name = name,
                        url = chUrl,
                        logoUrl = o.optStringOrNull("l"),
                        group = o.optStringOrNull("g"),
                        tvgId = o.optStringOrNull("t"),
                        sourcePlaylist = o.optStringOrNull("s"),
                    )
                )
            }
            if (list.isEmpty()) null else Cached(url, list)
        } catch (_: Throwable) {
            null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null
}
