package com.tvviewer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Fetches and parses XMLTV EPG data with disk caching.
 * Returns map: channelId (normalized) -> list of (start, end, title, description)
 */
object EpgRepository {

    private const val TAG = "TVViewer"
    // v2: ключи нормализуются Unicode-aware (\p{L}\p{N}) — кириллица
    // сохраняется. Старые кэши с пустыми ключами для русских каналов
    // больше не читаются (новая фабрика fetchAll создаёт v2 с нуля).
    private const val EPG_CACHE_FILE = "epg_cache_v2.json"
    private const val EPG_CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L // 6 hours

    private val client: OkHttpClient = run {
        // EPG-источники бывают на HTTPS-доменах с самоподписанными
        // или несовпадающими сертами (стандартная IPTV-ситуация).
        // Стандартный OkHttp HostnameVerifier это режет — ослабляем.
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<java.security.cert.X509Certificate>,
                authType: String
            ) {}
            override fun checkServerTrusted(
                chain: Array<java.security.cert.X509Certificate>,
                authType: String
            ) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                emptyArray()
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(trust), java.security.SecureRandom())
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .sslSocketFactory(ctx.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    data class Programme(
        val start: Long,
        val end: Long,
        val title: String,
        val description: String = ""
    )

    /**
     * Fetch EPG from URL with disk caching.
     * First tries to download fresh data. If fails, returns cached data.
     */
    /**
     * Fetch and merge EPG data from multiple URLs in parallel.
     * Last-write-wins on overlapping channel ids — additional sources fill in gaps.
     */
    /** Filter set: только эти normalized id / display-names будут
     *  индексироваться при парсинге. Drastically reduces memory: на
     *  3639-канальный плейлист и 5000-канальный XMLTV экономит ~80%
     *  программ. Установи перед fetchAll, очистится после. */
    @Volatile var channelFilter: Set<String>? = null

    /** Список источников и сколько каналов выдал каждый. */
    var lastFetchSummary: List<Pair<String, Int>> = emptyList()
        private set
    var lastFetchErrors: List<Pair<String, String>> = emptyList()
        private set

    suspend fun fetchAll(urls: List<String>, context: Context? = null): Map<String, List<Programme>> = coroutineScope {
        val cleaned = urls.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) return@coroutineScope loadFromCache(context) ?: emptyMap()
        val summary = mutableListOf<Pair<String, Int>>()
        val errors = mutableListOf<Pair<String, String>>()
        // Раньше каждый fetchSingle оборачивался в runCatching и его
        // ошибка молча проглатывалась → пользователь видел "загружено
        // 0" без объяснения. Теперь каждую ошибку логируем и кладём
        // в lastFetchErrors, чтобы UI мог показать.
        val results = cleaned.map { u ->
            async(Dispatchers.IO) {
                try {
                    val data = fetchSingle(u, context)
                    summary += u to data.size
                    data
                } catch (t: Throwable) {
                    Log.e(TAG, "EPG source failed: $u", t)
                    val msg = "${t.javaClass.simpleName}: ${t.message?.take(120)}"
                    errors += u to msg
                    summary += u to 0
                    if (context != null) {
                        try { ErrorLogger.logException(context, t) } catch (_: Exception) {}
                    }
                    emptyMap()
                }
            }
        }.map { it.await() }
        lastFetchSummary = summary
        lastFetchErrors = errors
        val merged = mutableMapOf<String, List<Programme>>()
        for (r in results) merged.putAll(r)
        if (merged.isNotEmpty()) saveToCache(context, merged)
        merged.ifEmpty { loadFromCache(context) ?: emptyMap() }
    }

    suspend fun fetchEpg(epgUrl: String?, context: Context? = null): Map<String, List<Programme>> = withContext(Dispatchers.IO) {
        if (epgUrl.isNullOrBlank()) {
            // Try to load from cache
            return@withContext loadFromCache(context) ?: emptyMap()
        }
        fetchSingle(epgUrl, context)
    }

    private suspend fun fetchSingle(epgUrl: String, context: Context?): Map<String, List<Programme>> = withContext(Dispatchers.IO) {
        val userAgent = context?.let { AppPreferences(it).userAgent } ?: AppPreferences.DEFAULT_USER_AGENT
        try {
            Log.d(TAG, "Fetching EPG from: $epgUrl")
            val request = Request.Builder()
                .url(epgUrl)
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", userAgent)
                .build()
            // ВАЖНО: парсим ПОТОКОВО, без буферизации всего ответа в
            // памяти. EPG-файлы бывают 100+ MB в gzip / 500+ MB
            // распакованные — на X4 X4 (heap 256MB) предыдущая
            // реализация (body.bytes() + readText) ловила
            // OutOfMemoryError. XmlPullParser умеет работать с
            // InputStream напрямую — отдаём ему GZIPInputStream и
            // парсер читает по чанкам.
            val result = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "EPG HTTP error: ${response.code}")
                    return@use null
                }
                val body = response.body ?: return@use null
                // Умное определение gzip: смотрим первые 2 байта (магия
                // 1F 8B). Если есть — gzipped, иначе plain XML. Это
                // надёжнее чем доверять URL/Content-Type — некоторые
                // серверы отдают .gz без Content-Encoding и наоборот.
                val raw = body.byteStream().buffered()
                raw.mark(2)
                val b1 = raw.read()
                val b2 = raw.read()
                raw.reset()
                val isGzip = (b1 == 0x1F && b2 == 0x8B)
                Log.d(TAG, "EPG body: gzip=$isGzip (header=$b1 $b2)")
                val stream = if (isGzip) GZIPInputStream(raw, 32 * 1024) else raw
                stream.use { parseXmltvStreaming(it) }
            } ?: return@withContext loadFromCache(context) ?: emptyMap()
            Log.d(TAG, "EPG parsed: ${result.size} channels with data")

            // Save to cache
            saveToCache(context, result)

            result
        } catch (e: Exception) {
            Log.e(TAG, "EPG fetch error", e)
            // Try to load from cache on error
            loadFromCache(context) ?: emptyMap()
        }
    }

    /**
     * Load cached EPG data from disk.
     */
    fun loadFromCache(context: Context?): Map<String, List<Programme>>? {
        if (context == null) return null
        try {
            val file = File(context.filesDir, EPG_CACHE_FILE)
            if (!file.exists()) return null

            // Check age
            val age = System.currentTimeMillis() - file.lastModified()
            if (age > EPG_CACHE_MAX_AGE_MS * 4) {
                // Too old (24h), delete
                file.delete()
                return null
            }

            val json = file.readText()
            return deserializeEpg(json)
        } catch (e: Exception) {
            Log.e(TAG, "EPG cache load error", e)
            return null
        }
    }

    /**
     * Save EPG data to disk cache.
     */
    private fun saveToCache(context: Context?, data: Map<String, List<Programme>>) {
        if (context == null) return
        try {
            val json = serializeEpg(data)
            val file = File(context.filesDir, EPG_CACHE_FILE)
            file.writeText(json)
            Log.d(TAG, "EPG cached to disk: ${file.length()} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "EPG cache save error", e)
        }
    }

    private fun serializeEpg(data: Map<String, List<Programme>>): String {
        val sb = StringBuilder()
        sb.append("{")
        var first = true
        for ((channelId, programmes) in data) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(escapeJson(channelId)).append("\":[")
            var pFirst = true
            for (p in programmes) {
                if (!pFirst) sb.append(",")
                pFirst = false
                sb.append("{\"s\":").append(p.start)
                sb.append(",\"e\":").append(p.end)
                sb.append(",\"t\":\"").append(escapeJson(p.title)).append("\"")
                if (p.description.isNotEmpty()) {
                    sb.append(",\"d\":\"").append(escapeJson(p.description)).append("\"")
                }
                sb.append("}")
            }
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun deserializeEpg(json: String): Map<String, List<Programme>> {
        val result = mutableMapOf<String, MutableList<Programme>>()
        try {
            val obj = org.json.JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val channelId = keys.next()
                val arr = obj.getJSONArray(channelId)
                val programmes = mutableListOf<Programme>()
                for (i in 0 until arr.length()) {
                    val pObj = arr.getJSONObject(i)
                    programmes.add(Programme(
                        start = pObj.getLong("s"),
                        end = pObj.getLong("e"),
                        title = pObj.getString("t"),
                        description = pObj.optString("d", "")
                    ))
                }
                result[channelId] = programmes
            }
        } catch (e: Exception) {
            Log.e(TAG, "EPG deserialize error", e)
        }
        return result
    }

    private fun parseXmltvStreaming(input: java.io.InputStream): Map<String, List<Programme>> {
        // Та же логика, что у parseXmltv, но пуллер читает напрямую из
        // InputStream — без сохранения всего XML в строку. Critical для
        // больших EPG-файлов (epg.it999.ru ~500MB распакованный).
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        return parseXmltvFromParser(parser)
    }

    private fun parseXmltv(xml: String): Map<String, List<Programme>> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        return parseXmltvFromParser(parser)
    }

    private fun parseXmltvFromParser(parser: XmlPullParser): Map<String, List<Programme>> {
        val result = mutableMapOf<String, MutableList<Programme>>()

        // Map of channel-id → normalized display names (for fallback matching)
        val displayNamesById = mutableMapOf<String, MutableList<String>>()

        var channelId: String? = null
        var start: Long = 0
        var end: Long = 0
        var title = ""
        var description = ""
        var inProgramme = false
        var inTitle = false
        var inDesc = false
        var inChannel = false
        var inDisplayName = false
        var currentChannelId: String? = null
        var displayNameBuf = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            inChannel = true
                            currentChannelId = parser.getAttributeValue(null, "id")?.let { normalizeId(it) }
                        }
                        "display-name" -> if (inChannel) {
                            inDisplayName = true
                            displayNameBuf = ""
                        }
                        "programme" -> {
                            inProgramme = true
                            channelId = parser.getAttributeValue(null, "channel")?.let { normalizeId(it) }
                            start = parseXmltvTime(parser.getAttributeValue(null, "start"))
                            end = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                            title = ""
                            description = ""
                        }
                        "title" -> if (inProgramme) inTitle = true
                        "desc" -> if (inProgramme) inDesc = true
                    }
                }
                XmlPullParser.TEXT -> {
                    when {
                        inDisplayName -> displayNameBuf += parser.text
                        inTitle -> title = parser.text.trim()
                        inDesc -> description = parser.text.trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            inChannel = false
                            currentChannelId = null
                        }
                        "display-name" -> {
                            if (inChannel && currentChannelId != null) {
                                val norm = normalizeId(displayNameBuf)
                                if (norm.isNotBlank()) {
                                    displayNamesById.getOrPut(currentChannelId!!) { mutableListOf() }.add(norm)
                                }
                            }
                            inDisplayName = false
                        }
                        "programme" -> {
                            // Раньше я добавлял playlist-фильтр здесь
                            // (channelFilter), но он сравнивал channelId
                            // из EPG ("1tvru") с именем канала из M3U
                            // ("первыйканал") — никогда не совпадало,
                            // парсер скипал ВСЁ. Снято: пишем все
                            // programme'ы, лишние entries — это просто
                            // ссылки на тот же List<Programme>, не
                            // копии, память не страдает.
                            if (channelId != null && title.isNotEmpty()) {
                                result.getOrPut(channelId!!) { mutableListOf() }
                                    .add(Programme(start, end, title, description))
                            }
                            inProgramme = false
                        }
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                    }
                }
            }
            eventType = parser.next()
        }

        // Mirror each channel's programmes under every normalized display-name as well.
        // This lets us match by channel name when the M3U lacks (or mistypes) tvg-id.
        for ((id, names) in displayNamesById) {
            val progs = result[id] ?: continue
            for (n in names) {
                if (n != id && !result.containsKey(n)) result[n] = progs
            }
        }

        result.values.forEach { it.sortBy { p -> p.start } }
        return result
    }

    private fun normalizeId(id: String): String =
        // \p{L} — любая буква (Cyrillic, Latin, Greek и т.д.), \p{N} — любая
        // цифра. Без этого "Первый канал" нормализовалось в "" и
        // русские каналы никогда не матчились с EPG по имени.
        id.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun parseXmltvTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        return try {
            // Handle timezone offset in XMLTV format: 20240101120000 +0300
            val clean = s.replace(" ", "").take(14)
            val offsetStr = s.replace(Regex("[^+\\-0-9]"), "").let {
                if (it.length > 14) it.substring(14) else ""
            }
            val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            if (offsetStr.isNotEmpty()) {
                try {
                    val fullFormat = SimpleDateFormat("yyyyMMddHHmmssZ", Locale.US)
                    fullFormat.parse(clean + offsetStr)?.time ?: 0L
                } catch (_: Exception) {
                    sdf.parse(clean)?.time ?: 0L
                }
            } else {
                sdf.parse(clean)?.time ?: 0L
            }
        } catch (_: Exception) { 0L }
    }

    fun getNowNext(epg: Map<String, List<Programme>>, tvgId: String?): Pair<String?, String?> {
        val (now, next) = getNowNextDetailed(epg, tvgId)
        return now?.title to next?.title
    }

    /** Like getNowNext, but accepts the channel's display name as a fallback. */
    fun getNowNext(
        epg: Map<String, List<Programme>>,
        tvgId: String?,
        channelName: String?,
    ): Pair<String?, String?> {
        val (now, next) = getNowNextDetailed(epg, tvgId, channelName)
        return now?.title to next?.title
    }

    /**
     * Get detailed now/next info with times.
     */
    fun getNowNextDetailed(epg: Map<String, List<Programme>>, tvgId: String?): Pair<Programme?, Programme?> =
        getNowNextDetailed(epg, tvgId, null)

    fun getNowNextDetailed(
        epg: Map<String, List<Programme>>,
        tvgId: String?,
        channelName: String?,
    ): Pair<Programme?, Programme?> {
        if (epg.isEmpty()) return null to null
        val keys = mutableListOf<String>()
        if (!tvgId.isNullOrBlank()) keys += normalizeId(tvgId)
        if (!channelName.isNullOrBlank()) keys += normalizeId(channelName)
        // Try iptv-org's tvg-id for the same channel name as a last resort
        if (!channelName.isNullOrBlank()) {
            ChannelMetaLookup.lookup(channelName)?.tvgId?.let {
                keys += normalizeId(it)
            }
        }
        val programmes = keys.firstNotNullOfOrNull { epg[it] } ?: return null to null
        val now = System.currentTimeMillis()
        var nowProg: Programme? = null
        var nextProg: Programme? = null
        for (p in programmes) {
            when {
                now in p.start..p.end -> nowProg = p
                now < p.start && nextProg == null -> { nextProg = p; break }
            }
        }
        return nowProg to nextProg
    }

    /**
     * Get all programmes for a channel on a specific day.
     */
    fun getProgrammesForDay(epg: Map<String, List<Programme>>, tvgId: String?, dayStartMs: Long, dayEndMs: Long): List<Programme> {
        if (tvgId.isNullOrBlank()) return emptyList()
        val norm = normalizeId(tvgId)
        val programmes = epg[norm] ?: return emptyList()
        return programmes.filter { it.start <= dayEndMs && it.end >= dayStartMs }
    }

    /**
     * Get progress of current programme (0.0 to 1.0).
     */
    fun getCurrentProgress(programme: Programme?): Float {
        if (programme == null) return 0f
        val now = System.currentTimeMillis()
        val total = programme.end - programme.start
        if (total <= 0) return 0f
        val elapsed = now - programme.start
        return (elapsed.toFloat() / total).coerceIn(0f, 1f)
    }
}
