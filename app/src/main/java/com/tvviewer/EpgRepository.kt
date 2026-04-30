package com.tvviewer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // НЕТ callTimeout — он включал время чтения парсером, что
            // на 100MB EPG-файлах срабатывало через 45s посередине
            // парсинга. Read-timeout 60s ловит зависшие соединения.
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

    // Дедуп-блок: если fetchAll уже в полёте, второй вызов awaits тот
    // же Deferred. Без этого один вызов из ChannelsFragment (auto-load
    // playlist) и второй из TvGuideFragment (refresh button) запускали
    // ДВА параллельных скачивания + парсинга 80+46 MB XMLTV → OOM-краш
    // на 256MB heap.
    //
    // ВАЖНО: inFlight обнуляется только когда РЕАЛЬНАЯ работа в
    // fetchScope закончилась (finally внутри async{}), а НЕ когда
    // await был отменён (например при смене таба). Иначе пока 1-й
    // парсинг продолжает молотить, 2-й вызов видит inFlight==null
    // и запускает второй параллельный парс — снова OOM.
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var inFlight: Deferred<Map<String, List<Programme>>>? = null
    private val inFlightLock = Any()

    suspend fun fetchAll(urls: List<String>, context: Context? = null): Map<String, List<Programme>> {
        val cleaned = urls.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) return loadFromCache(context) ?: emptyMap()
        val deferred = synchronized(inFlightLock) {
            val existing = inFlight
            if (existing != null && !existing.isCompleted) {
                if (context != null) ErrorLogger.info(context, "EPG",
                    "fetchAll: уже в полёте, await существующий")
                existing
            } else {
                val newDeferred = fetchScope.async {
                    try {
                        doFetchAll(cleaned, context)
                    } finally {
                        synchronized(inFlightLock) { inFlight = null }
                    }
                }
                inFlight = newDeferred
                newDeferred
            }
        }
        return deferred.await()
    }

    private suspend fun doFetchAll(cleaned: List<String>, context: Context?): Map<String, List<Programme>> = coroutineScope {
        if (context != null) ErrorLogger.info(context, "EPG",
            "fetchAll start: ${cleaned.size} sources, filter=${channelFilter?.size ?: 0} keys")
        val summary = mutableListOf<Pair<String, Int>>()
        val errors = mutableListOf<Pair<String, String>>()
        // Раньше каждый fetchSingle оборачивался в runCatching и его
        // ошибка молча проглатывалась → пользователь видел "загружено
        // 0" без объяснения. Теперь каждую ошибку логируем и кладём
        // в lastFetchErrors, чтобы UI мог показать.
        val results = cleaned.map { u ->
            async(Dispatchers.IO) {
                try {
                    // withTimeoutOrNull: жёсткий потолок 90 сек на
                    // источник. Раньше было 3 мин, но пользователь не мог
                    // дождаться — если оба источника зависли, юзер ждал
                    // до 6 минут. На X4 X4 (256MB heap) парсинг 50MB
                    // XMLTV + фильтр по плейлисту укладывается в 30-40
                    // сек, так что 90 сек хватает с запасом.
                    val data = kotlinx.coroutines.withTimeoutOrNull(90_000L) {
                        fetchSingle(u, context)
                    }
                    if (data == null) {
                        val msg = "Timeout (90 сек) — источник слишком медленный"
                        Log.e(TAG, "EPG source timed out: $u")
                        errors += u to msg
                        summary += u to 0
                        emptyMap()
                    } else {
                        summary += u to data.size
                        data
                    }
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
        if (context != null) ErrorLogger.info(context, "EPG",
            "fetchAll done: merged=${merged.size} channels, " +
            "summary=${summary.joinToString { "${it.first.substringAfter("://").substringBefore("/").take(20)}=${it.second}" }}, " +
            "errors=${errors.size}")
        merged.ifEmpty { loadFromCache(context) ?: emptyMap() }
    }

    suspend fun fetchEpg(epgUrl: String?, context: Context? = null): Map<String, List<Programme>> = withContext(Dispatchers.IO) {
        if (epgUrl.isNullOrBlank()) {
            // Try to load from cache
            return@withContext loadFromCache(context) ?: emptyMap()
        }
        fetchSingle(epgUrl, context)
    }

    /** Last fetch raw response peek (first 200 chars after gzip).
     *  Используется в debugStatus в TvGuide для диагностики "почему 0". */
    @Volatile var lastFetchPeek: String = ""
        private set

    /** Колбэк прогресса (всегда вызывается с main thread). UI подписывается,
     *  чтобы юзер видел "скачал 8MB / парсю / готово" а не пустой спиннер.
     *  Не зависим от view lifecycle — фрагмент переустанавливает на null
     *  в onDestroy. */
    @Volatile var onProgress: ((String) -> Unit)? = null

    private fun reportProgress(text: String) {
        val cb = onProgress ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb(text) }
        } catch (_: Throwable) {}
    }

    private suspend fun fetchSingle(epgUrl: String, context: Context?): Map<String, List<Programme>> = withContext(Dispatchers.IO) {
        val userAgent = context?.let { AppPreferences(it).userAgent } ?: AppPreferences.DEFAULT_USER_AGENT
        val host = epgUrl.substringAfter("://").substringBefore("/").take(30)
        try {
            Log.d(TAG, "Fetching EPG from: $epgUrl")
            reportProgress("Подключаюсь к $host…")
            if (context != null) ErrorLogger.info(context, "EPG", "fetchSingle($host) start, UA=${userAgent.take(40)}")
            val request = Request.Builder()
                .url(epgUrl)
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", userAgent)
                .build()
            val tStart = System.currentTimeMillis()
            val result = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "EPG HTTP error: ${response.code}")
                    lastFetchPeek = "HTTP ${response.code} ${response.message}"
                    if (context != null) ErrorLogger.info(context, "EPG",
                        "fetchSingle($host) HTTP ${response.code} ${response.message}")
                    return@use null
                }
                val body = response.body ?: return@use null
                reportProgress("$host: скачиваю EPG…")
                if (context != null) ErrorLogger.info(context, "EPG",
                    "fetchSingle($host) HTTP ${response.code}, downloading…")
                // Стратегия: сначала СКАЧИВАЕМ во временный файл, потом
                // ЗАКРЫВАЕМ HTTP, потом парсим с диска. Без этого парсер
                // 100MB EPG-файла зависал в HTTP-чтении дольше
                // OkHttp callTimeout, ловил InterruptedIOException.
                val tempFile = context?.cacheDir?.let { File(it, "epg_dl_${System.nanoTime()}.bin") }
                    ?: File.createTempFile("epg_dl", ".bin")
                tempFile.outputStream().use { out ->
                    body.byteStream().copyTo(out, 64 * 1024)
                }
                Log.d(TAG, "EPG downloaded ${tempFile.length()} bytes to $tempFile")
                reportProgress("$host: скачано ${tempFile.length() / 1024} KB, парсю…")
                if (context != null) ErrorLogger.info(context, "EPG",
                    "fetchSingle($host) downloaded ${tempFile.length() / 1024} KB in ${(System.currentTimeMillis() - tStart) / 1000}s")
                tempFile
            }
            // HTTP-соединение уже закрыто (response.use{} вышел).
            // Парсим с диска без какого-либо тайм-аута.
            val parsedResult = if (result != null) {
                try {
                    val raw = result.inputStream().buffered()
                    raw.mark(2)
                    val b1 = raw.read()
                    val b2 = raw.read()
                    raw.reset()
                    val isGzip = (b1 == 0x1F && b2 == 0x8B)
                    Log.d(TAG, "EPG body: gzip=$isGzip (header=$b1 $b2)")
                    if (context != null) ErrorLogger.info(context, "EPG",
                        "fetchSingle($host) gzip=$isGzip, parsing…")
                    val decoded = if (isGzip) GZIPInputStream(raw, 32 * 1024) else raw
                    val buffered = decoded.buffered(64 * 1024)
                    buffered.mark(512)
                    val peekBuf = ByteArray(200)
                    val peekLen = buffered.read(peekBuf)
                    buffered.reset()
                    lastFetchPeek = if (peekLen > 0) {
                        String(peekBuf, 0, peekLen, Charsets.UTF_8)
                            .filter { it.code in 32..126 || it == '\n' || it == '\t' }
                            .take(180)
                    } else "(empty)"
                    Log.d(TAG, "EPG peek: $lastFetchPeek")
                    val cleanedFirst = run {
                        val buf = ByteArray(8 * 1024)
                        val n = buffered.read(buf)
                        if (n <= 0) ByteArray(0) else {
                            val s = String(buf, 0, n, Charsets.UTF_8)
                            val cleaned = s.replace(Regex("<!DOCTYPE[^>]*>"), "")
                            cleaned.toByteArray(Charsets.UTF_8)
                        }
                    }
                    val combined: java.io.InputStream = java.io.SequenceInputStream(
                        java.io.ByteArrayInputStream(cleanedFirst),
                        buffered
                    )
                    combined.use { parseXmltvStreaming(it) }
                } finally {
                    try { result.delete() } catch (_: Exception) {}
                }
            } else null
            val finalResult = parsedResult ?: return@withContext loadFromCache(context) ?: emptyMap()
            Log.d(TAG, "EPG parsed: ${finalResult.size} channels with data")
            reportProgress("$host: ${finalResult.size} каналов, ${finalResult.values.sumOf { it.size }} передач")
            if (context != null) ErrorLogger.info(context, "EPG",
                "fetchSingle($host) parsed ${finalResult.size} channels, " +
                "${finalResult.values.sumOf { it.size }} programmes")
            saveToCache(context, finalResult)
            finalResult
        } catch (e: kotlinx.coroutines.CancellationException) {
            // ВАЖНО: CancellationException пробрасываем дальше. Иначе
            // withTimeoutOrNull не увидит, что таймаут сработал, а
            // fetchSingle вернёт emptyMap как успешный результат.
            throw e
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
        // Использую SAX (javax.xml.parsers) вместо KXmlParser — стандартный
        // Android-парсер, надёжнее на больших файлах. EntityResolver
        // подменяю на пустой, чтобы DTD точно не грузилось по сети.
        return try {
            val factory = javax.xml.parsers.SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Throwable) {}
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Throwable) {}
            val parser = factory.newSAXParser()
            parser.xmlReader.entityResolver = org.xml.sax.EntityResolver { _, _ ->
                org.xml.sax.InputSource(java.io.StringReader(""))
            }
            val handler = XmltvSaxHandler()
            parser.parse(input, handler)
            // Пост-фильтр: оставляем только каналы из текущего плейлиста.
            // Без этого XMLTV на 5000 каналов держит в памяти ~150K объектов
            // Programme, из которых 90% мы никогда не покажем. Убирает
            // десятки МБ хипа и ускоряет последующие операции.
            val filter = channelFilter
            val result = if (filter != null && filter.isNotEmpty()) {
                val keep = mutableMapOf<String, MutableList<Programme>>()
                for ((id, progs) in handler.result) {
                    val matchesById = id in filter
                    val matchesByName = handler.displayNamesById[id]?.any { it in filter } == true
                    if (matchesById || matchesByName) keep[id] = progs
                }
                keep
            } else handler.result
            // Сортируем + мирроринг под display-names (для матчинга по имени)
            result.values.forEach { it.sortBy { p -> p.start } }
            for ((id, names) in handler.displayNamesById) {
                val progs = result[id] ?: continue
                for (n in names) {
                    if (n != id && !result.containsKey(n)) result[n] = progs
                }
            }
            if (!lastFetchPeek.startsWith("PARSER ERROR")) {
                val totalProgs = result.values.sumOf { it.size }
                lastFetchPeek = "Parsed: ${result.size} channels, $totalProgs programmes" +
                    " | peek: " + lastFetchPeek.take(100)
            }
            result
        } catch (t: Throwable) {
            Log.e(TAG, "SAX parser failed", t)
            lastFetchPeek = "PARSER ERROR: ${t.javaClass.simpleName}: ${t.message?.take(140)}"
            emptyMap()
        }
    }

    /** SAX handler для XMLTV: только title (description выкинут чтобы
     *  heap не забивался), display-names собираются для пост-мирроринга. */
    private class XmltvSaxHandler : org.xml.sax.helpers.DefaultHandler() {
        val result = mutableMapOf<String, MutableList<Programme>>()
        val displayNamesById = mutableMapOf<String, MutableList<String>>()
        private var inChannel = false
        private var inProgramme = false
        private var inDisplayName = false
        private var inTitle = false
        private var currentChannelId: String? = null
        private val displayNameBuf = StringBuilder()
        private val titleBuf = StringBuilder()
        private var programmeChannel: String? = null
        private var programmeStart: Long = 0
        private var programmeEnd: Long = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attrs: org.xml.sax.Attributes?) {
            val name = qName ?: localName ?: return
            when (name) {
                "channel" -> {
                    inChannel = true
                    currentChannelId = attrs?.getValue("id")?.let { normalizeId(it) }
                }
                "display-name" -> if (inChannel) {
                    inDisplayName = true
                    displayNameBuf.setLength(0)
                }
                "programme" -> {
                    inProgramme = true
                    programmeChannel = attrs?.getValue("channel")?.let { normalizeId(it) }
                    programmeStart = parseXmltvTime(attrs?.getValue("start"))
                    programmeEnd = parseXmltvTime(attrs?.getValue("stop"))
                    titleBuf.setLength(0)
                }
                "title" -> if (inProgramme) {
                    inTitle = true
                    titleBuf.setLength(0)
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (ch == null) return
            when {
                inDisplayName -> displayNameBuf.append(ch, start, length)
                inTitle -> titleBuf.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = qName ?: localName ?: return
            when (name) {
                "channel" -> {
                    inChannel = false
                    currentChannelId = null
                }
                "display-name" -> {
                    if (inChannel && currentChannelId != null) {
                        val norm = normalizeId(displayNameBuf.toString())
                        if (norm.isNotBlank()) {
                            displayNamesById.getOrPut(currentChannelId!!) { mutableListOf() }.add(norm)
                        }
                    }
                    inDisplayName = false
                }
                "title" -> inTitle = false
                "programme" -> {
                    val chId = programmeChannel
                    val title = titleBuf.toString().trim().take(120)
                    if (chId != null && title.isNotEmpty()) {
                        result.getOrPut(chId) { mutableListOf() }
                            .add(Programme(programmeStart, programmeEnd, title, ""))
                    }
                    inProgramme = false
                    programmeChannel = null
                }
            }
        }
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
                        // <desc> игнорируем намеренно: на больших EPG-
                        // файлах описания (часто 500+ символов на каждое
                        // событие) забивали heap и приводили к OOM.
                        // ТВ-Гид показывает только заголовок, описание
                        // не нужно.
                    }
                }
                XmlPullParser.TEXT -> {
                    when {
                        inDisplayName -> displayNameBuf += parser.text
                        // Title тоже ограничиваем — некоторые EPG
                        // запихивают целые синопсисы в <title>.
                        inTitle -> title = parser.text.trim().take(120)
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
                                    .add(Programme(start, end, title, ""))
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
        // Дублируем счётчик в lastFetchPeek (если ещё не было ошибки)
        // — увидим reached parser, сколько каналов / программ
        // распарсилось.
        if (!lastFetchPeek.startsWith("PARSER ERROR")) {
            val totalProgs = result.values.sumOf { it.size }
            lastFetchPeek = "Parsed: ${result.size} channels, $totalProgs programmes" +
                " | peek: " + lastFetchPeek.take(100)
        }
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
