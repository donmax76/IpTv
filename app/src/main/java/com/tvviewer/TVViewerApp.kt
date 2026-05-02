package com.tvviewer

import android.app.Application
import android.content.Intent
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.io.PrintWriter
import java.io.StringWriter

class TVViewerApp : Application(), ImageLoaderFactory {

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
        // Подметаем мусор в cacheDir: epg_dl_*.bin остаются, если
        // приложение упало или килланулось посреди загрузки EPG.
        // Они весят по 50-100 MB каждый, а собирается их за месяц
        // на гигабайт.
        try {
            cacheDir?.listFiles()?.forEach { f ->
                if (f.name.startsWith("epg_dl_") && f.name.endsWith(".bin")) {
                    try { f.delete() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        // Pre-warm the iptv-org channel database so logos / tvg-ids for
        // user-added channels become available a few seconds after launch.
        try { ChannelMetaLookup.ensureLoaded(applicationContext) } catch (_: Exception) {}
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
