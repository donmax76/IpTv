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
        val ok = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(ok)
            .crossfade(true)
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
