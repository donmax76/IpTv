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
    // v3: кэш не фильтруется под конкретный плейлист (Round 101).
    // Сохраняем все каналы из EPG-источника, чтобы любой плейлист
    // мог достать свои программы из общего кэша. Старый _v2 файл
    // содержал отфильтрованный кэш и его нужно выбросить — поэтому
    // меняю имя.
    // Round 226a: bumped v4 → v5 чтобы инвалидировать старые кэши,
    // собранные с узким окном 72 ч (Round 216). Round 225 расширил
    // окно до 120 ч, но юзер видел старые данные пока следующий
    // авто-fetch не сработает (через ~30 ч). Миграция в TVViewerApp
    // также сбрасывает prefs.epgLastUpdate, чтобы fetch запустился
    // сразу.
    private const val EPG_CACHE_FILE = "epg_cache_v5.json"
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
        // Auto-filter убран: при переключении плейлистов EPG-кэш
        // оказывался отфильтрованным под предыдущий плейлист, и
        // каналы из нового плейлиста не находили программу.
        // Теперь парсер сохраняет ВСЕ каналы (с 7-дневным time-фильтром
        // для экономии), и любой плейлист может из общего кэша достать
        // свои программы.
        channelFilter = null
        val deferred = synchronized(inFlightLock) {
            val existing = inFlight
            if (existing != null && !existing.isCompleted) {
                if (context != null) ErrorLogger.info(context, "EPG",
                    "fetchAll: уже в полёте, await существующий")
                existing
            } else {
                val newDeferred = fetchScope.async {
                    try {
                        setRefreshing(true)
                        doFetchAll(cleaned, context)
                    } finally {
                        setRefreshing(false)
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

        // Round 217: WakeLock на время парсинга. Юзер прислал лог с
        // Xiaomi LEX820 — парсинг 6531 каналов занял 9.5 минут вместо
        // 47 сек на другом телефоне. Причина: Android приморозил
        // фоновый CPU когда юзер свернул приложение. WakeLock
        // PARTIAL_WAKE_LOCK гарантирует что CPU не уйдёт в idle.
        // Освобождается в finally независимо от исхода.
        val wakeLock: android.os.PowerManager.WakeLock? = try {
            val pm = context?.getSystemService(Context.POWER_SERVICE)
                as? android.os.PowerManager
            pm?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "TVViewer:EpgFetch"
            )?.also {
                it.setReferenceCounted(false)
                it.acquire(15 * 60 * 1000L /* 15 min max */)
            }
        } catch (_: Throwable) { null }
        // Android Round 353: реальный try/finally для WakeLock — release
        // ниже стоял в прямом потоке кода, и Throwable из merge/save
        // секции держал lock до 15-минутного таймаута acquire.
        try {
        val summary = mutableListOf<Pair<String, Int>>()
        val errors = mutableListOf<Pair<String, String>>()
        // Раньше каждый fetchSingle оборачивался в runCatching и его
        // ошибка молча проглатывалась → пользователь видел "загружено
        // 0" без объяснения. Теперь каждую ошибку логируем и кладём
        // в lastFetchErrors, чтобы UI мог показать.
        // Серийный парсинг (один источник за раз). На X4 X4 (256MB
        // heap) параллельный парсинг 1.1MB+44MB одновременно вылетал
        // в OOM и BinderInternal$GcWatcher timeout. Серийно: парсим
        // один, мержим, GC, переходим к следующему.
        val results = mutableListOf<Map<String, List<Programme>>>()
        for (u in cleaned) {
            try {
                // 600s (10 мин) — поднял с 180с потому что на X4 X4
                // парсинг 6500 каналов занимает ~5 мин, и таймаут
                // обрывал корутину раньше чем parser закончил. Сам
                // parser продолжал работать (не проверяет cancel),
                // дописывал результат на диск, но fireEpgUpdated
                // никогда не вызывался → юзер видел "EPG нет".
                val data = kotlinx.coroutines.withTimeoutOrNull(600_000L) {
                    withContext(Dispatchers.IO) { fetchSingle(u, context) }
                }
                if (data == null) {
                    val msg = "Timeout (600 сек) — источник слишком медленный"
                    Log.e(TAG, "EPG source timed out: $u")
                    errors += u to msg
                    summary += u to 0
                    // Парсер мог дойти до конца после cancel и записать
                    // результат в кэш — пробуем поднять его.
                    val cached = loadFromCache(context)
                    if (cached != null && cached.isNotEmpty()) {
                        Log.d(TAG, "EPG timeout but cache has ${cached.size} entries — using")
                        results += cached
                    } else {
                        results += emptyMap()
                    }
                } else {
                    summary += u to data.size
                    results += data
                }
            } catch (t: Throwable) {
                Log.e(TAG, "EPG source failed: $u", t)
                val msg = "${t.javaClass.simpleName}: ${t.message?.take(120)}"
                errors += u to msg
                summary += u to 0
                if (context != null) {
                    try { ErrorLogger.logException(context, t) } catch (_: Exception) {}
                }
                // То же что и при timeout — пробуем cache fallback.
                val cached = loadFromCache(context)
                results += if (cached != null && cached.isNotEmpty()) cached else emptyMap()
            }
        }
        lastFetchSummary = summary
        lastFetchErrors = errors
        val merged = mutableMapOf<String, List<Programme>>()
        for (r in results) merged.putAll(r)
        if (merged.isNotEmpty()) {
            saveToCache(context, merged)
            // Уведомляем подписчиков (ChannelsFragment, FavoritesFragment...)
            // чтобы их адаптеры подхватили свежий EPG автоматически —
            // без ручной перезагрузки экрана.
            fireEpgUpdated(merged)
        }
        if (context != null) ErrorLogger.info(context, "EPG",
            "fetchAll done: merged=${merged.size} channels, " +
            "summary=${summary.joinToString { "${it.first.substringAfter("://").substringBefore("/").take(20)}=${it.second}" }}, " +
            "errors=${errors.size}")
        merged.ifEmpty { loadFromCache(context) ?: emptyMap() }
        } finally {
            // Round 217/353: освобождаем WakeLock гарантированно.
            try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        }
    }

    // Android Round 353: удалён мёртвый fetchEpg(epgUrl, context) —
    // ноль вызывающих; он обходил inFlight-дедуп fetchAll и был
    // готовой лазейкой для конкурентных загрузок/записей кэша.

    /** Last fetch raw response peek (first 200 chars after gzip).
     *  Используется в debugStatus в TvGuide для диагностики "почему 0". */
    @Volatile var lastFetchPeek: String = ""
        private set

    /** Отдельное поле для peek первых 200 байт когда парсер ничего не
     *  нашёл. lastFetchPeek перетирается в parseXmltvStreaming, поэтому
     *  диагностику храним отдельно. */
    @Volatile var lastEmptyPeek: String? = null

    /** Колбэк прогресса (всегда вызывается с main thread). UI подписывается,
     *  чтобы юзер видел "скачал 8MB / парсю / готово" а не пустой спиннер.
     *  Не зависим от view lifecycle — фрагмент переустанавливает на null
     *  в onDestroy. */
    @Volatile var onProgress: ((String) -> Unit)? = null

    /** Идёт ли в данный момент fetchAll (любой источник). UI может
     *  смотреть это поле чтобы отрисовать индикатор "обновление в
     *  фоне" даже когда юзер ушёл из Settings. */
    @Volatile var isRefreshing: Boolean = false
        private set

    /** Список подписчиков на изменение isRefreshing. Срабатывает
     *  когда обновление стартует и когда завершается. */
    private val refreshStateListeners = mutableListOf<(Boolean) -> Unit>()

    @Synchronized
    fun addRefreshStateListener(l: (Boolean) -> Unit) {
        refreshStateListeners += l
        // Сразу даём текущее состояние, чтобы новый подписчик
        // увидел что обновление уже идёт если оно идёт.
        try { l(isRefreshing) } catch (_: Throwable) {}
    }

    @Synchronized
    fun removeRefreshStateListener(l: (Boolean) -> Unit) {
        refreshStateListeners -= l
    }

    private fun setRefreshing(value: Boolean) {
        if (isRefreshing == value) return
        isRefreshing = value
        val handlers = synchronized(this) { refreshStateListeners.toList() }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            handlers.forEach { try { it(value) } catch (_: Throwable) {} }
        }
    }

    /** Список подписчиков на обновление EPG-кэша. Каждый раз после
     *  успешного fetchAll вызываются на main thread с новой картой.
     *  ChannelsFragment подписывается чтобы обновить адаптеры в
     *  списке каналов автоматически — без ручной перезагрузки. */
    private val epgUpdateListeners = mutableListOf<(Map<String, List<Programme>>) -> Unit>()

    @Synchronized
    fun addEpgUpdateListener(l: (Map<String, List<Programme>>) -> Unit) {
        epgUpdateListeners += l
    }

    @Synchronized
    fun removeEpgUpdateListener(l: (Map<String, List<Programme>>) -> Unit) {
        epgUpdateListeners -= l
    }

    @Synchronized
    private fun fireEpgUpdated(data: Map<String, List<Programme>>) {
        val handlers = epgUpdateListeners.toList()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            handlers.forEach { try { it(data) } catch (_: Throwable) {} }
        }
    }

    /** Public alias для внешних вызывающих (TVViewerApp, грузящий
     *  кэш на старте) — fireEpgUpdated приватный. */
    fun notifyEpgUpdate(data: Map<String, List<Programme>>) = fireEpgUpdated(data)

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
                    // Раньше тут был runInterruptible — он конвертил
                    // парсерный InterruptedException в CancellationException,
                    // которая ломала deferred и обнуляла inFlight, после
                    // чего следующий вызов стартовал второй параллельный
                    // парсинг и валил приложение в OOM. Без него: парсер
                    // просто бежит до конца, withTimeoutOrNull откинет
                    // результат если не успел, но дедуп остаётся целым.
                    combined.use { stream ->
                        parseXmltvStreaming(stream)
                    }
                } finally {
                    try { result.delete() } catch (_: Exception) {}
                }
            } else null
            val finalResult = parsedResult ?: return@withContext loadFromCache(context) ?: emptyMap()
            reportProgress("$host: ${finalResult.size} каналов, ${finalResult.values.sumOf { it.size }} передач")
            if (context != null) ErrorLogger.info(context, "EPG",
                "fetchSingle($host) parsed ${finalResult.size} channels, " +
                "${finalResult.values.sumOf { it.size }} programmes")
            // Если ничего не распарсилось — пишем peek в лог чтобы
            // увидеть формат файла (HTML, кириллица, бинарь и т.д.)
            val emptyPeek = lastEmptyPeek
            if (context != null && finalResult.isEmpty() && emptyPeek != null) {
                ErrorLogger.info(context, "EPG", "fetchSingle($host) $emptyPeek")
                lastEmptyPeek = null
            }
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

            // Раньше: если cache > 24h — удаляли. Это и был корень
            // проблемы "после апдейта программы EPG пропадает":
            // если юзер не запускал приложение пару дней, на следующий
            // запуск cache стирался, и он видел пустую программу пока
            // вручную не обновит.
            // Теперь: cache НЕ удаляем по возрасту. Пусть auto-refresh
            // в фоне обновит данные, но между тем юзер видит хоть что-то
            // (parser уже отфильтровал прошлые передачи, в кэше остаются
            // только текущие/будущие на 7 дней). Удаляем cache только
            // если он реально битый (>5MB) или невалидный JSON (catch).

            // Защита от чудовищных кэшей: если файл больше 25 MB —
            // он скорее всего от старой версии без time-фильтра.
            // Старый порог был 5 MB, но при 6300 каналах + display-name
            // зеркала + fuzzy ключи легко выходит 8-15 MB JSON → файл
            // удалялся, EPG показывался пустой пока не переобновишь.
            // 25 MB должно покрывать все нормальные cache-варианты.
            if (file.length() > 25L * 1024 * 1024) {
                Log.w(TAG, "EPG cache too big (${file.length()} bytes), discarding")
                file.delete()
                return null
            }

            val json = file.readText()
            return deserializeEpg(json)
        } catch (e: Throwable) {
            // Throwable: OOM включая. На X4 X4 (256MB) прошлый кэш
            // валил приложение прямо на старте.
            Log.e(TAG, "EPG cache load error", e)
            try {
                val file = File(context.filesDir, EPG_CACHE_FILE)
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
            return null
        }
    }

    /**
     * Save EPG data to disk cache. ВАЖНО: пустую карту НЕ сохраняем —
     * иначе неудачный fetch затирает существующий рабочий кэш.
     */
    private fun saveToCache(context: Context?, data: Map<String, List<Programme>>) {
        if (context == null) return
        if (data.isEmpty()) {
            Log.d(TAG, "EPG saveToCache skipped — empty map (preserving existing cache)")
            return
        }
        // Защита от «обрезанного» результата: если HTTP-поток без
        // gzip оборвался «чисто» (сервер закрыл соединение раньше
        // времени, Content-Length отсутствует), regex-парсер спокойно
        // доходит до конца частичного текста и возвращает 5 каналов
        // вместо 6000 — и раньше этот огрызок ПЕРЕЗАПИСЫВАЛ хороший
        // кэш до следующего авто-обновления (48ч). Тот же класс бага
        // ловили в Windows-порте («merged channels=2 from 3 sources»).
        if (data.size < 5) {
            try {
                val existing = File(context.filesDir, EPG_CACHE_FILE)
                if (existing.exists() && existing.length() > 10_000) {
                    Log.w(TAG, "EPG saveToCache skipped — suspiciously small " +
                        "result (${data.size} ch) would overwrite existing cache")
                    return
                }
            } catch (_: Exception) {}
        }
        try {
            val json = serializeEpg(data)
            val file = File(context.filesDir, EPG_CACHE_FILE)
            // Атомарная запись: tmp + rename. Раньше writeText писал
            // прямо в целевой файл — Android агрессивно убивает
            // процессы, и смерть посреди записи оставляла обрезанный
            // JSON. При следующем старте deserializeEpg падал, catch
            // удалял файл — юзер видел пустую программу до ручного
            // обновления. (Тот же баг был найден и исправлен в
            // Windows-порте.)
            // Android Round 353: УНИКАЛЬНОЕ имя tmp на каждую запись.
            // Фиксированный "$EPG_CACHE_FILE.tmp" оставлял окно гонки:
            // отменённый по таймауту парсер-сирота (см. комментарии в
            // fetchSingle — он дорабатывает и пишет кэш) и следующий
            // источник могли писать ОДИН tmp конкурентно — interleaved
            // writeText + rename ставили битый кэш, который следующий
            // loadFromCache молча удалял (тихая потеря EPG).
            val tmp = File(context.filesDir,
                "$EPG_CACHE_FILE.${System.nanoTime()}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                // rename поверх существующего файла на некоторых FS
                // требует удаления цели.
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "EPG cache save error", e)
        }
    }

    private fun serializeEpg(data: Map<String, List<Programme>>): String {
        // В data часто одна и та же List<Programme> присутствует под
        // несколькими ключами (display-name + fuzzy зеркала). Раньше
        // мы писали программы в JSON под КАЖДЫМ ключом → файл рос в
        // 2-3 раза, выходил за лимит и удалялся.
        // Теперь: первый ключ для конкретного List получает массив
        // программ, остальные пишут строку-алиас вида "@первый_ключ".
        // Десериализатор увидит "@..." и вместо парсинга подставит
        // ссылку на уже распарсенный массив.
        val sb = StringBuilder()
        sb.append("{")
        var first = true
        // identityHashMap чтобы корректно сравнивать ССЫЛКИ списков,
        // а не их .equals() (две разных List могут быть .equals).
        val seen = java.util.IdentityHashMap<List<Programme>, String>()
        for ((channelId, programmes) in data) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(escapeJson(channelId)).append("\":")
            val firstKeyForList = seen[programmes]
            if (firstKeyForList != null) {
                // Алиас. Префикс @ — маркер, не используется в ID.
                sb.append("\"@").append(escapeJson(firstKeyForList)).append("\"")
            } else {
                seen[programmes] = channelId
                sb.append("[")
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
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun deserializeEpg(json: String): Map<String, List<Programme>> {
        val result = mutableMapOf<String, List<Programme>>()
        // Тот же временной фильтр что и в parseXmltvFast: только
        // Round 216: окно сужено до 3 дней (см. parseXmltvFast).
        val now = System.currentTimeMillis()
        val keepFrom = now - 2L * 60 * 60 * 1000               // 2 часа назад
        // Round 225: было +72 ч (3 дня). С Round 219 staleAfter=48 ч,
        // через 48 ч после fetch в кэше оставалось лишь 24 ч будущих
        // передач — юзер жаловался «обновление было вчера но он должен
        // был показывать обновление на 3 сутки, не показывает». Берём
        // 120 ч (5 суток), deserialize всё равно отдаёт только 72 ч
        // от ТЕКУЩЕГО момента — даже накануне нового fetch у юзера
        // всегда есть 3 полных дня EPG.
        val keepTo = now + 120L * 60 * 60 * 1000                // +120 часов (5 суток)
        try {
            val obj = org.json.JSONObject(json)
            // Двухпроходный обход: сначала собираем все ключи с
            // массивами (реальные данные), потом обрабатываем алиасы
            // ("@key" вместо массива) — алиасы могут ссылаться на
            // ключи которые встречаются позже в JSON.
            val keys = mutableListOf<String>()
            val iter = obj.keys()
            while (iter.hasNext()) keys.add(iter.next())

            // Pass 1: реальные массивы программ.
            for (channelId in keys) {
                val v = obj.opt(channelId)
                if (v !is org.json.JSONArray) continue
                val programmes = mutableListOf<Programme>()
                for (i in 0 until v.length()) {
                    val pObj = v.getJSONObject(i)
                    val start = pObj.getLong("s")
                    val end = pObj.getLong("e")
                    if (end < keepFrom || start > keepTo) continue
                    programmes.add(Programme(
                        start = start,
                        end = end,
                        title = pObj.getString("t"),
                        description = ""
                    ))
                }
                if (programmes.isNotEmpty()) result[channelId] = programmes
            }
            // Pass 2: алиасы "@first_key" → подставляем ту же List<Programme>.
            for (channelId in keys) {
                val v = obj.opt(channelId)
                if (v !is String) continue
                if (!v.startsWith("@")) continue
                val target = v.substring(1)
                val progs = result[target] ?: continue
                result[channelId] = progs
            }
        } catch (e: Throwable) {
            Log.e(TAG, "EPG deserialize error", e)
            return emptyMap()
        }
        return result
    }

    private fun parseXmltvStreaming(input: java.io.InputStream): Map<String, List<Programme>> {
        // Regex-based потоковый парсер. Заменил SAX потому что SAX на
        // 75MB XMLTV токенизирует все ~1M узлов даже если 90% программ
        // нам не нужны → ловил 90+с таймаут на Redmi Note 9S.
        // Этот вариант:
        //  - читает поток чанками (32KB) в StringBuilder;
        //  - находит <programme ...>...</programme> через indexOf
        //    (намного быстрее SAX, нет XML-тoкенизации);
        //  - сразу извлекает channel-атрибут, проверяет фильтр —
        //    если канал не наш, скипает блок целиком без regex по
        //    title/start/stop;
        //  - <channel ...>...</channel> блоки обрабатываются в
        //    префиксе перед первым programme (типичная структура XMLTV).
        return try {
            // Гибрид: inline-фильтр в parseXmltvFast при наличии
            // <channel> блоков (5x быстрее), иначе принимает всё и
            // post-filter ниже разбирается. Для 44MB it999.ru с
            // 339 каналами это экономит парсинг ~280 ненужных каналов.
            val filter = channelFilter
            val acceptKeys: Set<String>? = if (filter != null && filter.isNotEmpty()) {
                val expanded = HashSet<String>(filter.size * 2)
                expanded.addAll(filter)
                for (k in filter) {
                    val fk = fuzzyKey(k)
                    if (fk.isNotEmpty()) expanded.add(fk)
                }
                expanded
            } else null
            val (rawResult, displayNamesById) = parseXmltvFast(input, acceptKeys)

            // Mirror display-names → programmes map. Это ставит
            // программу под именем канала (Cyrillic "Муз ТВ" → "музтв")
            // помимо id ("muztv"). Должно работать и до и после фильтра.
            for ((id, names) in displayNamesById) {
                val progs = rawResult[id] ?: continue
                for (n in names) {
                    if (n != id && !rawResult.containsKey(n)) rawResult[n] = progs
                }
            }

            // Post-filter (использует уже посчитанные acceptKeys).
            // Если inline уже отфильтровал — здесь будут проходить почти
            // все. Если inline сдался (нет <channel> блоков) — тут
            // финальная очистка. Дублирующая защита, ничего не стоит.
            val result: MutableMap<String, MutableList<Programme>> = if (acceptKeys == null) {
                rawResult
            } else {
                val keep = mutableMapOf<String, MutableList<Programme>>()
                for ((id, progs) in rawResult) {
                    if (id in acceptKeys || fuzzyKey(id) in acceptKeys) {
                        keep[id] = progs
                    }
                }
                keep
            }

            result.values.forEach { it.sortBy { p -> p.start } }

            // Fuzzy-зеркалирование. Сначала считаем сколько уникальных
            // id сводятся к каждому fuzzyKey. Если на один fuzzy key
            // претендуют несколько id (например "amedia1" и "amedia2"
            // оба → "amedia") — зеркало НЕ создаём: иначе оба канала
            // в плейлисте получат программу первого попавшегося id.
            // Это и был баг "Amedia 1 и Amedia 2 одинаковые программы".
            val snapshot = result.toMap()
            val fkCounts = HashMap<String, Int>(snapshot.size)
            for ((id, _) in snapshot) {
                val fk = fuzzyKey(id)
                if (fk.isNotEmpty() && fk != id) {
                    fkCounts[fk] = (fkCounts[fk] ?: 0) + 1
                }
            }
            for ((id, progs) in snapshot) {
                val fk = fuzzyKey(id)
                if (fk.isNotEmpty() && fk != id && !result.containsKey(fk) && fkCounts[fk] == 1) {
                    result[fk] = progs
                }
            }
            if (!lastFetchPeek.startsWith("PARSER ERROR")) {
                val totalProgs = result.values.sumOf { it.size }
                lastFetchPeek = "Parsed: ${result.size} channels, $totalProgs programmes (raw=${rawResult.size}, dn=${displayNamesById.size})" +
                    " | peek: " + lastFetchPeek.take(80)
            }
            result
        } catch (t: Throwable) {
            Log.e(TAG, "fast parser failed", t)
            lastFetchPeek = "PARSER ERROR: ${t.javaClass.simpleName}: ${t.message?.take(140)}"
            emptyMap()
        }
    }

    private fun parseXmltvFast(
        input: java.io.InputStream,
        acceptKeys: Set<String>?
    ): Pair<MutableMap<String, MutableList<Programme>>, MutableMap<String, MutableList<String>>> {
        val result = mutableMapOf<String, MutableList<Programme>>()
        val displayNamesById = mutableMapOf<String, MutableList<String>>()

        val reader = java.io.InputStreamReader(input, Charsets.UTF_8)
        val sb = StringBuilder(256 * 1024)
        val buf = CharArray(64 * 1024)

        // Precompiled attribute needles. Allocate once вместо
        // "$attr=\"" в каждом вызове extractAttr.
        val needleChannel = "channel=\""
        val needleStart = "start=\""
        val needleStop = "stop=\""
        val needleId = "id=\""
        val tagProgrammeClose = "</programme>"
        val tagChannelClose = "</channel>"
        val tagTitleOpen = "<title"
        val tagTitleClose = "</title>"
        val tagDisplayNameOpen = "<display-name"
        val tagDisplayNameClose = "</display-name>"

        // Round 216: время-фильтр сужен с 7 дней до 3 дней.
        // XMLTV-файлы обычно содержат архив на неделю назад/вперёд,
        // но юзеру видны только программы "Сейчас / Далее / завтра /
        // послезавтра". Скидываем всё что > 72 часов вперёд и > 2
        // часов назад — парсер быстрее, памяти меньше.
        val nowMillis = System.currentTimeMillis()
        val keepFrom = nowMillis - 2L * 60 * 60 * 1000         // 2 часа назад
        // Round 225: было +72 ч. См. блок в parseXmltvFast — теперь
        // храним 120 ч (5 суток) чтобы накануне следующего fetch
        // (через 48 ч) ещё оставалось минимум 72 ч будущих передач.
        val keepTo = nowMillis + 120L * 60 * 60 * 1000          // +120 часов (5 суток)

        // Универсальный поиск открывающего тега: ищет "<name" + любой
        // whitespace ИЛИ ">". Раньше искал ровно "<programme " (с
        // пробелом) и пропускал файлы где после имени \n / \t / просто
        // ">". Это и был баг: parsed 0 channels, 0 programmes на
        // 43MB it999.ru — парсер 6 минут читал файл и ни одной
        // программы не находил.
        fun findOpenTag(name: String, from: Int): Int {
            val needle = "<$name"
            var p = from
            while (p < sb.length) {
                val idx = sb.indexOf(needle, p)
                if (idx < 0) return -1
                val nextPos = idx + needle.length
                if (nextPos >= sb.length) return -1
                val c = sb[nextPos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '>') return idx
                p = idx + 1
            }
            return -1
        }

        // Кэш проверок accepted: для XMLTV с ~5000 каналами и 100 прог/канал
        // 500K программ → 5K уникальных id. Кэш экономит ~99% вызовов
        // fuzzyKey() и нормализаций.
        val acceptCache = HashMap<String, Boolean>(8192)

        var pos = 0
        var seenProgramme = false
        var pCount = 0
        var skipCount = 0
        val tStart = System.currentTimeMillis()

        // Принимает StringBuilder (не CharSequence) чтобы JVM
        // использовала нативный String.indexOf вместо медленной
        // kotlin.text extension.
        fun extractAttrSB(s: StringBuilder, from: Int, to: Int, needle: String): String? {
            val idx = s.indexOf(needle, from)
            if (idx < 0 || idx >= to) return null
            val start = idx + needle.length
            val endQuote = s.indexOf("\"", start)
            if (endQuote < 0 || endQuote >= to) return null
            return s.substring(start, endQuote)
        }

        fun extractTagContentSB(s: StringBuilder, from: Int, to: Int, openTag: String, closeTag: String): String? {
            val ob = s.indexOf(openTag, from)
            if (ob < 0 || ob >= to) return null
            val obEnd = s.indexOf(">", ob)
            if (obEnd < 0 || obEnd >= to) return null
            val cb = s.indexOf(closeTag, obEnd)
            if (cb < 0 || cb >= to) return null
            return s.substring(obEnd + 1, cb)
        }

        // Inline-фильтр с graceful fallback: если у нас уже есть
        // <channel> блоки с display-names (типичный XMLTV: каналы
        // идут до программ), фильтруем строго по id + fuzzy +
        // display-name. Если display-names map пустой (lite-файл
        // без <channel>), пропускаем программу — пост-filter в
        // parseXmltvStreaming сделает работу.
        // Это даёт ~5x ускорение на больших файлах вроде 44MB
        // it999.ru: из 339 каналов отбираем сразу ~50 которые в
        // плейлисте, не аллоцируем 300K мусорных Programme.
        fun isAccepted(chId: String): Boolean {
            if (acceptKeys == null) return true
            val cached = acceptCache[chId]
            if (cached != null) return cached
            // Файл без <channel> блоков → не отбрасываем ничего,
            // пусть post-filter решит.
            if (displayNamesById.isEmpty()) {
                acceptCache[chId] = true
                return true
            }
            val ok = chId in acceptKeys ||
                fuzzyKey(chId) in acceptKeys ||
                (displayNamesById[chId]?.any {
                    it in acceptKeys || fuzzyKey(it) in acceptKeys
                } == true)
            acceptCache[chId] = ok
            return ok
        }

        fun processProgrammeBlock(blockStart: Int, blockEnd: Int) {
            val headerEnd = sb.indexOf(">", blockStart)
            if (headerEnd < 0 || headerEnd >= blockEnd) return
            val rawCh = extractAttrSB(sb, blockStart, headerEnd, needleChannel) ?: return
            val chId = normalizeId(rawCh)
            if (chId.isEmpty()) return
            if (!isAccepted(chId)) {
                skipCount++
                return
            }
            val rawStart = extractAttrSB(sb, blockStart, headerEnd, needleStart) ?: return
            val rawStop = extractAttrSB(sb, blockStart, headerEnd, needleStop) ?: return
            val start = parseXmltvTime(rawStart)
            val end = parseXmltvTime(rawStop)
            if (start <= 0 || end <= 0) return
            // Время-фильтр: только программы из окна [вчера, +7 дней].
            // Архив старше дня и предсказания дальше недели — мусор
            // на 256MB heap.
            if (end < keepFrom || start > keepTo) {
                skipCount++
                return
            }
            val title = extractTagContentSB(sb, headerEnd + 1, blockEnd, tagTitleOpen, tagTitleClose)?.trim()?.take(120) ?: return
            if (title.isEmpty()) return
            result.getOrPut(chId) { mutableListOf() }.add(Programme(start, end, title, ""))
        }

        fun processChannelsRange(rangeStart: Int, rangeEnd: Int) {
            var p = rangeStart
            while (p < rangeEnd) {
                val cb = findOpenTag("channel", p)
                if (cb < 0 || cb >= rangeEnd) break
                val ce = sb.indexOf(tagChannelClose, cb)
                if (ce < 0 || ce >= rangeEnd) break
                val headerEnd = sb.indexOf(">", cb)
                if (headerEnd in cb until ce) {
                    val rawId = extractAttrSB(sb, cb, headerEnd, needleId)
                    if (rawId != null) {
                        val cId = normalizeId(rawId)
                        if (cId.isNotEmpty()) {
                            var dnFrom = headerEnd + 1
                            while (dnFrom < ce) {
                                val dn = extractTagContentSB(sb, dnFrom, ce, tagDisplayNameOpen, tagDisplayNameClose) ?: break
                                val name = normalizeId(dn)
                                if (name.isNotBlank() && name != cId) {
                                    displayNamesById.getOrPut(cId) { mutableListOf() }.add(name)
                                }
                                val dnClose = sb.indexOf(tagDisplayNameClose, dnFrom)
                                if (dnClose < 0) break
                                dnFrom = dnClose + tagDisplayNameClose.length
                            }
                        }
                    }
                }
                p = ce + tagChannelClose.length
            }
        }

        reportProgress("Парсю… запускаю")
        var firstPeekLogged = false
        // Сохраняем первые 200 символов чтобы записать в trace из
        // fetchSingle (у parser нет прямого доступа к context для
        // ErrorLogger).
        var firstPeek: String? = null

        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            sb.append(buf, 0, n)

            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("EPG parser cancelled")
            }

            // Один раз дамп первых 200 символов в trace — чтобы видеть
            // реальный формат файла когда парсер не находит programmes.
            if (!firstPeekLogged && sb.length >= 200) {
                val peek = sb.substring(0, 200).replace('\n', ' ').replace('\r', ' ').take(180)
                reportProgress("Peek: $peek")
                firstPeek = peek
                firstPeekLogged = true
            }

            if (!seenProgramme) {
                val pIdx = findOpenTag("programme", pos)
                if (pIdx >= 0) {
                    processChannelsRange(pos, pIdx)
                    pos = pIdx
                    seenProgramme = true
                    val sec = (System.currentTimeMillis() - tStart) / 1000
                    reportProgress("Channels parsed (${displayNamesById.size}), idёт по программам, ${sec}с")
                } else if (sb.length - pos > 4 * 1024 * 1024) {
                    val safe = sb.length - 1024
                    processChannelsRange(pos, safe)
                    sb.delete(0, safe)
                    pos = 0
                }
            }

            if (seenProgramme) {
                while (true) {
                    val pb = findOpenTag("programme", pos)
                    if (pb < 0) break
                    val pe = sb.indexOf(tagProgrammeClose, pb)
                    if (pe < 0) break
                    val blockEnd = pe + tagProgrammeClose.length
                    processProgrammeBlock(pb, blockEnd)
                    pCount++
                    pos = blockEnd
                    if ((pCount and 0x1FFF) == 0) {
                        val sec = (System.currentTimeMillis() - tStart) / 1000
                        reportProgress("Парсю… ${pCount / 1000}K блоков, accepted=${result.values.sumOf { it.size }}, ${sec}с")
                    }
                }
                if (pos > 256 * 1024) {
                    sb.delete(0, pos)
                    pos = 0
                }
            }
        }

        if (!seenProgramme) {
            processChannelsRange(pos, sb.length)
        }

        val sec = (System.currentTimeMillis() - tStart) / 1000
        val accepted = result.values.sumOf { it.size }
        reportProgress("Парсинг готов: ${pCount / 1000}K блоков, $accepted принято, $skipCount отброшено, ${sec}с")
        // Если ничего не нашли — публикуем peek в отдельное поле
        // lastEmptyPeek (lastFetchPeek перезатрётся 'Parsed: 0…').
        // fetchSingle прочитает и запишет в trace-лог.
        lastEmptyPeek = if (accepted == 0 && firstPeek != null)
            "EMPTY, blocks=$pCount, sec=$sec, peek=" + firstPeek!!.take(160)
        else null

        return Pair(result, displayNamesById)
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

    // Прекомпилированные regex'ы: normalizeId зовётся с onBindViewHolder
    // (до ~6 раз на строку через getNowNext/lookupProgrammes, в оверлее
    // плеера — ещё больше) — раньше Regex(...) КОМПИЛИРОВАЛСЯ на каждый
    // вызов, т.е. десятки компиляций regex на каждую строку при
    // скролле списка каналов. Заметный CPU-налог на кадр на TV-боксах.
    private val diacriticsRe = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val nonAlnumRe = Regex("[^\\p{L}\\p{N}]")

    private fun normalizeId(id: String): String {
        // \p{L} — любая буква (Cyrillic, Latin, Greek и т.д.), \p{N} — любая
        // цифра. Без этого "Первый канал" нормализовалось в "" и
        // русские каналы никогда не матчились с EPG по имени.
        // Дополнительно сворачиваем латинскую диакритику ('Türkiye' →
        // 'turkiye') чтобы плейлистные ASCII-варианты матчились с
        // iptv-org записями содержащими нац. символы.
        val folded = java.text.Normalizer.normalize(id.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(diacriticsRe, "")
        return folded.replace(nonAlnumRe, "")
    }

    /** Аггрессивная нормализация для fuzzy-матча: normalizeId плюс
     *  отрезание суффиксов качества/региона/слота, которые часто есть
     *  в M3U-именах но отсутствуют в XMLTV id. Например:
     *    "Sky Sports News HD 50 UK" → "skysportsnewshd50uk" → "skysportsnews"
     *    "Первый HD"               → "первыйhd"          → "первый"
     *    "РБК HD 4K"               → "рбкhd4k"           → "рбк"
     *  Используется как fallback когда точный match по normalizeId
     *  и display-name дал 0 совпадений. */
    private val fuzzyTrailDigits = Regex("\\d+$")
    private val fuzzySuffixes = listOf(
        // Resolution markers (часто в скобках в плейлисте: "(720p)",
        // "(1080p)" и т.д.). После normalize брackets улетают, остаётся
        // например "cartoonnetwork1080p" — без явного strip'а буква "p"
        // блокирует strip трейлинг-цифр и канал не матчится.
        "1080p", "1080i", "720p", "720i", "576p", "576i", "480p", "480i",
        "1440p", "2160p", "4320p",
        "uhd", "fhd", "qhd", "hd", "sd", "4k", "8k",
        // Country/region 2-letter codes. Длинные имена стран
        // (turkiye, azerbaijan) обычно идут целым словом — для них
        // лучше работает diacritic-fold + alt_names в iptv-org.
        "uk", "ru", "us", "az", "ua", "by", "kz", "tr", "ge", "am", "uz", "tj", "kg",
    )
    fun fuzzyKey(id: String?): String {
        if (id.isNullOrBlank()) return ""
        var t = normalizeId(id)
        // Минимум 3 символа после стрипа: защита от схлопывания
        // "1tv"→"1" итд. Цикл: пока что-то меняется (хвостовые цифры
        // или суффикс) — продолжаем.
        var changed = true
        while (changed && t.length > 3) {
            changed = false
            val nt = fuzzyTrailDigits.replace(t, "")
            if (nt.length in 3..t.length - 1) { t = nt; changed = true; continue }
            for (suf in fuzzySuffixes) {
                if (t.endsWith(suf) && t.length - suf.length >= 3) {
                    t = t.substring(0, t.length - suf.length)
                    changed = true
                    break
                }
            }
        }
        return t
    }

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
        val programmes = lookupProgrammes(epg, tvgId, channelName) ?: return null to null
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

    /** Round 212: вернуть N программ, которые НАЧИНАЮТСЯ ПОЗЖЕ
     *  текущего времени (после "now"). Используется в OverlayChannelAdapter
     *  для отрисовки следующих 2-3 программ в строке канала (стиль SS IPTV). */
    fun getUpcomingProgrammes(
        epg: Map<String, List<Programme>>,
        tvgId: String?,
        channelName: String?,
        count: Int,
    ): List<Programme> {
        val programmes = lookupProgrammes(epg, tvgId, channelName) ?: return emptyList()
        val now = System.currentTimeMillis()
        return programmes.asSequence()
            .filter { it.start > now }
            .sortedBy { it.start }
            .take(count)
            .toList()
    }

    /** Универсальный поиск programmes для канала. Применяется ко
     *  всему: getNowNextDetailed, getProgrammesForDay, прямые
     *  обращения. Перебираем все возможные ключи в порядке от точного
     *  к фуззи, чтобы канал из любого плейлиста нашёл свою программу
     *  в общем кэше EPG. */
    private fun lookupProgrammes(
        epg: Map<String, List<Programme>>,
        tvgId: String?,
        channelName: String?,
    ): List<Programme>? {
        if (epg.isEmpty()) return null
        val keys = LinkedHashSet<String>()
        // 1. Точный tvg-id и имя
        if (!tvgId.isNullOrBlank()) {
            normalizeId(tvgId).takeIf { it.isNotEmpty() }?.let(keys::add)
        }
        if (!channelName.isNullOrBlank()) {
            normalizeId(channelName).takeIf { it.isNotEmpty() }?.let(keys::add)
        }
        // 2. iptv-org tvg-id для этого имени (на случай если playlist
        //    использует только display-name)
        if (!channelName.isNullOrBlank()) {
            ChannelMetaLookup.lookup(channelName)?.tvgId?.let {
                normalizeId(it).takeIf { k -> k.isNotEmpty() }?.let(keys::add)
            }
        }
        // 3. Fuzzy-варианты (без HD/SD/UK/RU/(720p) и т.д.) — для
        //    плейлистов с суффиксами имени. Защита от Amedia 1/2
        //    коллизии — на уровне CACHE: при сборке кэша мы НЕ создаём
        //    fuzzy-зеркало если на тот же ключ претендуют несколько
        //    разных id (см. parseXmltv). Так что fuzzy здесь безопасен:
        //    либо матчит уникальный канал, либо ничего не матчит.
        if (!tvgId.isNullOrBlank()) {
            fuzzyKey(tvgId).takeIf { it.isNotEmpty() }?.let(keys::add)
        }
        if (!channelName.isNullOrBlank()) {
            fuzzyKey(channelName).takeIf { it.isNotEmpty() }?.let(keys::add)
        }
        if (!channelName.isNullOrBlank()) {
            ChannelMetaLookup.lookup(channelName)?.tvgId?.let {
                fuzzyKey(it).takeIf { k -> k.isNotEmpty() }?.let(keys::add)
            }
        }
        return keys.firstNotNullOfOrNull { epg[it] }
    }

    /**
     * Get all programmes for a channel on a specific day.
     */
    fun getProgrammesForDay(epg: Map<String, List<Programme>>, tvgId: String?, dayStartMs: Long, dayEndMs: Long): List<Programme> =
        getProgrammesForDay(epg, tvgId, null, dayStartMs, dayEndMs)

    /** Версия getProgrammesForDay с display-name fallback'ом. Принимает
     *  имя канала чтобы попасть в кэш по name/fuzzy/iptv-org даже когда
     *  tvg-id в плейлисте не задан или не совпадает с EPG. */
    fun getProgrammesForDay(
        epg: Map<String, List<Programme>>,
        tvgId: String?,
        channelName: String?,
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<Programme> {
        val programmes = lookupProgrammes(epg, tvgId, channelName) ?: return emptyList()
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
