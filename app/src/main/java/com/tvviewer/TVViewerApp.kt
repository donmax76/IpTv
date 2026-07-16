package com.tvviewer

import android.app.Application
import android.content.Intent
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.PrintWriter
import java.io.StringWriter

class TVViewerApp : Application(), ImageLoaderFactory {

    companion object {
        /** Process-wide scope для фоновых задач которые должны
         *  переживать смену экранов: ручное обновление EPG из настроек,
         *  авто-обновление в фоне. Использует SupervisorJob чтобы
         *  одна ошибка не валила соседние корутины. */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Счётчик started-активити: >0 — приложение на экране.
         *  Нужен для кейса «Плеер → Настройки → HOME»: PlayerActivity
         *  при открытии Настроек ставит keepPlayingInBackground и его
         *  onStop НЕ глушит поток (это фича — трансляция играет за
         *  Настройками). Но если юзер уходит HOME из Настроек, никакой
         *  колбэк плееру больше не приходит — поток продолжал играть
         *  за лаунчером бесконечно (звук + сеть + батарея). Теперь при
         *  падении счётчика до нуля дёргаем onAppBackgrounded. */
        @Volatile var startedActivityCount = 0
        val isAppVisible: Boolean get() = startedActivityCount > 0

        /** Ставится PlayerActivity в onCreate, снимается в onDestroy.
         *  Вызывается на main thread когда приложение полностью ушло
         *  в фон (все активити остановлены). */
        @Volatile var onAppBackgrounded: (() -> Unit)? = null

        /** Триггер EPG auto-refresh из любой Activity. Сама функция
         *  имеет 30-сек delay + 24h gate, так что повторные вызовы
         *  в течение дня — no-op. Используется MainActivity.onResume
         *  чтобы поймать кейс "юзер не закрывал приложение пару дней"
         *  — без этого scheduleEpgAutoRefresh вызывался один раз при
         *  старте процесса и больше никогда. */
        fun triggerEpgAutoRefresh(context: android.content.Context) {
            (context.applicationContext as? TVViewerApp)?.scheduleEpgAutoRefresh()
        }
    }

    override fun newImageLoader(): ImageLoader {
        // Coil использует свой OkHttpClient — у него своя HostnameVerifier
        // и SSL-цепочка, поэтому глобальный fix HttpsURLConnection из
        // installPermissiveSslForStreaming() его не цепляет. Логотипы
        // каналов часто хостятся на тех же CDN с несовпадающими сертами,
        // что и стримы. Выдаём Coil'у trust-all OkHttpClient.
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
        // User-Agent: некоторые CDN'ы (Cloudflare-проксирующие лого
        // каналов) блокируют запросы с дефолтным "okhttp/4.x" UA или
        // без UA. Подменяем на браузерный — это выручает большинство
        // случаев когда логотипы есть в плейлисте, но не загружаются.
        // Plus connect/read timeouts: 8с/10с — лого на медленном CDN
        // не должно дёргать UI на 60+ секунд.
        val uaInterceptor = okhttp3.Interceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 11; TV Box) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(req)
        }
        val ok = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(uaInterceptor)
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(ok)
            .crossfade(true)
            // Cap memory cache: 10% of heap (вместо дефолта 25%).
            // На X4 X4 (256MB heap) это ~25 MB. Этого хватает для
            // ~100 видимых лого, остальные грузятся по-новой при
            // прокрутке. Без этого у нас 3000+ кэшированных картинок
            // забивали память — отсюда лаги в плеере.
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.10)
                    .build()
            }
            // Cap disk cache to 50 MB. По дефолту Coil выделяет до 250 MB
            // в cacheDir на лого. У пользователя 3000+ каналов — все
            // лого попадают на диск и распухают приложение до 1+ ГБ.
            // 50 MB хватает для ~5000 PNG/SVG.
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    private fun isCancellation(throwable: Throwable): Boolean {
        var t: Throwable? = throwable
        while (t != null) {
            if (t is CancellationException) return true
            t = t.cause
        }
        return false
    }

    override fun onCreate() {
        super.onCreate()
        // Учёт видимости приложения — см. startedActivityCount.
        registerActivityLifecycleCallbacks(object :
                android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) {
                startedActivityCount++
            }
            override fun onActivityStopped(a: android.app.Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    // Android Round 373: приложение ушло в фон —
                    // сбрасываем сессионную разблокировку родительского
                    // контроля. Юзер: после одного ввода PIN канал
                    // открывался без PIN в следующие разы. Теперь при
                    // каждом возврате из фона заблокированный канал
                    // снова требует PIN.
                    ParentalControl.sessionUnlocked = false
                    try { onAppBackgrounded?.invoke() } catch (_: Throwable) {}
                }
            }
            override fun onActivityCreated(a: android.app.Activity,
                                           b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity,
                                                     b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        // Подметаем мусор в cacheDir: epg_dl_*.bin остаются, если
        // приложение упало или килланулось посреди загрузки EPG.
        // Они весят по 50-100 MB каждый, а собирается их за месяц
        // на гигабайт. В фоне: listFiles + delete — дисковый I/O,
        // которому нечего делать на main thread в onCreate.
        applicationScope.launch {
            try {
                cacheDir?.listFiles()?.forEach { f ->
                    if (f.name.startsWith("epg_dl_") && f.name.endsWith(".bin")) {
                        try { f.delete() } catch (_: Exception) {}
                    }
                }
                // Чистим устаревшие версии EPG-кэша. v2 был с playlist-
                // фильтром (Round 101+), v3 без fuzzy-mirror (Round 119+).
                // Текущая v4 с fuzzy-mirror восстановлен (Round 126).
                try {
                    java.io.File(filesDir, "epg_cache_v2.json").delete()
                    java.io.File(filesDir, "epg_cache_v3.json").delete()
                    java.io.File(filesDir, "epg_cache.json").delete()
                } catch (_: Exception) {}
                // Android Round 353: осиротевшие tmp атомарных записей
                // (смерть процесса между writeText и rename).
                try {
                    filesDir?.listFiles()?.forEach { f ->
                        if (f.name.endsWith(".tmp")) {
                            try { f.delete() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
        // Pre-warm the iptv-org channel database so logos / tvg-ids for
        // user-added channels become available a few seconds after launch.
        try { ChannelMetaLookup.ensureLoaded(applicationContext) } catch (_: Exception) {}
        // Поднимаем обучаемый кэш логотипов (built up across all
        // playlists ever loaded). Даёт лого каналам в плейлистах
        // без tvg-logo если те же каналы встречались раньше с лого.
        // Загрузка в фоне: раньше ensureLoaded звался СИНХРОННО прямо
        // здесь, в Application.onCreate на main thread — чтение файла
        // до 2МБ + JSONObject-парс + цикл с fuzzyKey-regex на каждую
        // из до 10k записей. На слабом устройстве это сотни мс к
        // холодному старту (ANR-бюджет). lookup() до загрузки просто
        // вернёт null — адаптеры и так живут с этим (fallback на
        // letter-tile), harvest() сам дозагрузит при первом вызове.
        applicationScope.launch {
            try { LearnedLogos.ensureLoaded(applicationContext) } catch (_: Exception) {}
        }
        // Фоновое авто-обновление EPG: раз в 24 часа после последнего
        // успешного обновления. Раньше эта проверка жила в TvGuideFragment
        // (вкладка ТВ Гид) — но мы её убрали. Теперь ставим прямо в
        // Application.onCreate. Запуск отложен на 30 секунд чтобы не
        // конкурировать с iptv-org parse за CPU при холодном старте.
        try { scheduleEpgAutoRefresh() } catch (_: Exception) {}
        // Round 187: чистим скачанный APK обновления при старте — если
        // файл существует, значит предыдущий цикл "скачать → запустить
        // установщик" уже завершился (установка прошла, новый APK
        // запущен → этот код выполняется), мусор больше не нужен.
        try {
            val stale = java.io.File(cacheDir, "TVViewer-update.apk")
            if (stale.exists()) stale.delete()
        } catch (_: Exception) {}
        // IPTV-стримы часто живут на CDN'ах с несовпадающими сертами
        // (53be5ef2d13aa.streamlock.net показывает cert *.maksnet.tv
        // и пр.), и SSL-валидация их режет. Ослабляем глобально для
        // HttpsURLConnection — этим пользуется ExoPlayer для стримов
        // и HLS-манифестов. На GitHub API / EPG / playlist через
        // OkHttp это не влияет (там свой HostnameVerifier).
        try { installPermissiveSslForStreaming() } catch (_: Exception) {}
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            if (isCancellation(throwable)) return@setDefaultUncaughtExceptionHandler
            try {
                Log.e("TVViewer", "Uncaught exception", throwable)
                val errorText = getFullStackTrace(throwable)
                ErrorLogger.logException(applicationContext, throwable)
                try { CrashReporter.send(applicationContext, errorText) } catch (_: Exception) {}
                // Token-less auto-publish to ntfy.sh + GitHub (if token set)
                // so the developer can see the crash without any user step.
                try {
                    val title = "[Android crash] " + errorText.lineSequence()
                        .firstOrNull { it.isNotBlank() }?.take(80).orEmpty()
                    val body = buildString {
                        append("Auto-submitted crash report.\n\n")
                        append(GitHubReporter.systemInfo())
                        append("\n**Stacktrace**:\n```\n")
                        append(errorText.takeLast(4000))
                        append("\n```\n")
                    }
                    // silent=true: rate-limited and toast-less to avoid
                    // flooding the screen with "Log sent" on a crash loop.
                    GitHubReporter.report(applicationContext, title, body, silent = true)
                } catch (_: Exception) {}
                val intent = Intent(applicationContext, CrashReportActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(CrashReportActivity.EXTRA_ERROR, errorText)
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("TVViewer", "Cannot show crash activity", e)
                }
            } catch (e: Exception) {
                Log.e("TVViewer", "Crash handler failed", e)
            } finally {
                // ОБЯЗАТЕЛЬНО убиваем процесс. При краше main thread
                // Looper.loop() уже вышел: CrashReportActivity в ЭТОМ
                // процессе никогда не создастся, и без killProcess
                // приложение просто замирало (юзер видел вечный фриз
                // вместо краш-экрана, пока система не прибьёт по ANR).
                // FLAG_ACTIVITY_NEW_TASK + отдельный запуск intent'а
                // выше позволяют системе поднять активити в новом
                // процессе после смерти этого.
                // Перед смертью: дописываем очередь ErrorLogger на диск
                // и даём фоновым репортерам (ntfy/GitHub, свои нитки)
                // пару секунд на отправку.
                try { ErrorLogger.flush(2000) } catch (_: Throwable) {}
                try { Thread.sleep(2000) } catch (_: Throwable) {}
                try {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Throwable) {}
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** Запуск авто-обновления EPG в фоне. Условия:
     *  1. Есть хотя бы один EPG-источник в настройках.
     *  2. Прошло > 24 часов с последнего успешного fetchAll.
     *  Если refresh уже был сегодня — ничего не делаем, кэш остаётся.
     */
    @Volatile private var autoRefreshScheduled = false

    fun scheduleEpgAutoRefresh() {
        // Запускаем ОДИН долгоживущий цикл на процесс. Дедуп: повторные
        // вызовы (из onResume любой активности) проваливаются в no-op,
        // потому что цикл уже крутится в applicationScope.
        if (autoRefreshScheduled) return
        autoRefreshScheduled = true
        applicationScope.launch {
            try {
                // Round 226a: одноразовая миграция — старый кэш
                // epg_cache_v4.json (Round 216, окно 72 ч) удаляем
                // и сбрасываем epgLastUpdate, чтобы новый fetch
                // запустился сразу и сохранил 120-часовое окно
                // (Round 225). Без этого юзер ждал бы ~30 часов до
                // следующего авто-fetch чтобы увидеть полные 3 суток.
                try {
                    val migrated = AppPreferences(applicationContext)
                        .getMigrationFlag("epg_v5_migrated")
                    if (!migrated) {
                        java.io.File(applicationContext.filesDir, "epg_cache_v4.json")
                            .takeIf { it.exists() }?.delete()
                        AppPreferences(applicationContext).epgLastUpdate = 0L
                        AppPreferences(applicationContext)
                            .setMigrationFlag("epg_v5_migrated", true)
                    }
                } catch (_: Throwable) {}

                // Шаг 1: подгружаем кеш в память — это даёт EPG в UI
                // моментально пока сетевой fetch ещё не отработал.
                val cached = EpgRepository.loadFromCache(applicationContext)
                if (cached != null && cached.isNotEmpty()) {
                    ChannelDataHolder.epgData = cached
                    EpgRepository.notifyEpgUpdate(cached)
                }

                // Round 183: сократили warmup 30→5 сек. Раньше юзер
                // успевал убить приложение ДО того как loop сделает
                // первый check, и EPG не обновлялся.
                kotlinx.coroutines.delay(5_000)

                // Бесконечный цикл проверки: раз в 30 мин смотрим, прошло
                // ли 12 ч с последнего refresh; если да — качаем. Гейт
                // снижен 24→12 ч и интервал 60→30 мин по жалобе юзера
                // что EPG "сам не обновляется". Все шаги пишутся в
                // ErrorLogger чтобы можно было прислать лог если ещё
                // и эти параметры не помогут.
                val prefs = AppPreferences(applicationContext)
                val checkInterval = 30L * 60 * 1000    // 30 мин
                // Round 219: staleAfter 12 ч → 48 ч. EPG-парсер сохраняет
                // программы на 3 дня вперёд (Round 216b). Фетчить раз в
                // 12 ч было пустой тратой — данные ещё свежие на 60 часов
                // вперёд. 48 ч = когда остаётся ~24 ч данных (запас на
                // случай сетевой ошибки). Юзер на 3-й день увидит свежий
                // EPG автоматически.
                val staleAfter = 48L * 60 * 60 * 1000  // 48 ч
                while (true) {
                    val urls = prefs.allEpgUrls()
                    val staleAt = System.currentTimeMillis() - staleAfter
                    // Round 194: учитываем флаг "EPG авто-обновление" из
                    // Settings. Раньше он писался но не проверялся —
                    // юзер не мог отключить автообновление.
                    val needFetch = prefs.epgAutoUpdate &&
                        urls.isNotEmpty() && prefs.epgLastUpdate < staleAt
                    // Android Round 378: auto-tick больше НЕ пишем в
                    // файл лога (ErrorLogger) — юзер: «убери EPG
                    // логирование». Эта строка писалась каждую минуту и
                    // забивала лог ошибок, который потом уходит в отчёт.
                    // Оставляем только в logcat для отладки.
                    android.util.Log.d("EPG",
                        "auto-tick urls=${urls.size} needFetch=$needFetch")
                    if (needFetch) {
                        try {
                            val data = EpgRepository.fetchAll(urls, applicationContext)
                            if (data.isNotEmpty()) {
                                ChannelDataHolder.epgData = data
                                prefs.epgLastUpdate = System.currentTimeMillis()
                                try { ErrorLogger.info(applicationContext, "EPG",
                                    "auto-refresh ok: ${data.size} channels") } catch (_: Throwable) {}
                            } else {
                                try { ErrorLogger.info(applicationContext, "EPG",
                                    "auto-refresh returned empty data") } catch (_: Throwable) {}
                            }
                        } catch (t: Throwable) {
                            Log.e("TVViewer", "EPG fetch failed; retrying in 30 min", t)
                            try { ErrorLogger.info(applicationContext, "EPG",
                                "auto-refresh failed: ${t.javaClass.simpleName}: ${t.message?.take(120)}") } catch (_: Throwable) {}
                        }
                    }
                    kotlinx.coroutines.delay(checkInterval)
                }
            } catch (_: CancellationException) {
                // applicationScope обычно не отменяется, но на всякий
                // случай — сбрасываем флаг чтобы следующая попытка
                // запустить цикл снова сработала.
                autoRefreshScheduled = false
            } catch (t: Throwable) {
                Log.e("TVViewer", "EPG auto-refresh loop crashed", t)
                autoRefreshScheduled = false
            }
        }
    }

    private fun installPermissiveSslForStreaming() {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
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
        )
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
        javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    }

    private fun getFullStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        var cause = throwable.cause
        while (cause != null) {
            pw.println("\nCaused by:")
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        return sw.toString()
    }
}
