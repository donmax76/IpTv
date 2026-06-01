package com.tvviewer

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Обучаемый кэш логотипов: запоминает пары normalize(имя_канала) →
 * URL_лого из всех плейлистов которые юзер когда-либо открывал.
 *
 * Это даёт логотипы для каналов в плейлистах БЕЗ tvg-logo: если
 * "Cartoon Network" встретился в плейлисте A с лого imgur, то в
 * плейлисте B без лого тот же канал получит этот URL.
 *
 * Сохраняется в filesDir/learned_logos.json. Размер: пары
 * (~50 байт ключ + 100 байт URL) × ~5000 уникальных каналов = ~750 KB.
 */
object LearnedLogos {

    private const val TAG = "LearnedLogos"
    private const val CACHE_FILE = "learned_logos.json"
    private const val MAX_ENTRIES = 10_000

    private val map = HashMap<String, String>(2000)
    private val fuzzyMap = HashMap<String, String>(2000)
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        try {
            val file = File(context.filesDir, CACHE_FILE)
            if (file.exists() && file.length() in 1..2_000_000) {
                val obj = JSONObject(file.readText())
                val keys = obj.keys()
                var dropped = 0
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (v.isEmpty()) continue
                    // Чистим старый мусор: Google-фавиконы которые
                    // попали сюда до Round 100. Они отображаются как
                    // серая планетка 16px и хуже placeholder'а.
                    if (v.contains("google.com/s2/favicons")) {
                        dropped++
                        continue
                    }
                    map[k] = v
                    val fk = EpgRepository.fuzzyKey(k)
                    if (fk.isNotEmpty()) fuzzyMap[fk] = v
                }
                if (dropped > 0) persist(context)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "load failed", e)
        }
        loaded = true
    }

    @Synchronized
    fun lookup(channelName: String): String? {
        if (channelName.isBlank()) return null
        val key = normalize(channelName)
        map[key]?.let { return it }
        val fk = EpgRepository.fuzzyKey(channelName)
        if (fk.isNotEmpty()) fuzzyMap[fk]?.let { return it }
        return null
    }

    /** Запоминает (имя → URL) из текущего плейлиста. Перезаписывает
     *  существующее (последнее увиденное побеждает — вдруг URL обновился). */
    @Synchronized
    fun harvest(context: Context, channels: List<Channel>) {
        if (!loaded) ensureLoaded(context)
        var added = 0
        for (ch in channels) {
            val url = ch.logoUrl ?: continue
            if (url.isBlank()) continue
            // Skip favicon URLs — это не настоящие лого
            if (url.contains("google.com/s2/favicons")) continue
            val key = normalize(ch.name)
            if (key.isEmpty()) continue
            if (map[key] != url) {
                map[key] = url
                val fk = EpgRepository.fuzzyKey(ch.name)
                if (fk.isNotEmpty()) fuzzyMap[fk] = url
                added++
            }
        }
        if (added > 0) {
            persist(context)
        }
    }

    @Synchronized
    private fun persist(context: Context) {
        try {
            // Если карта раздулась — оставим самые свежие 10K (HashMap
            // не упорядочен, так что просто обрезаем до лимита.
            // Audit #16: iterator-based truncation вместо take().associate
            // — без промежуточного List<Pair> и Map.
            if (map.size > MAX_ENTRIES) {
                val it = map.entries.iterator()
                var i = 0
                while (it.hasNext()) {
                    it.next()
                    if (i >= MAX_ENTRIES) it.remove()
                    i++
                }
            }
            val obj = JSONObject()
            for ((k, v) in map) obj.put(k, v)
            File(context.filesDir, CACHE_FILE).writeText(obj.toString())
        } catch (e: Throwable) {
            Log.e(TAG, "persist failed", e)
        }
    }

    @Synchronized
    fun size(): Int = map.size

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}
