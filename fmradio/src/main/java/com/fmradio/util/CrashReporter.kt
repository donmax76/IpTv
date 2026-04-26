package com.fmradio.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.fmradio.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash/log reporter — same approach as TVViewer project.
 * Primary: ntfy.sh (token-less, developer reads stream directly)
 * Fallback: opens browser with pre-filled GitHub issue
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var lastReport = 0L

    fun systemInfo(): String = buildString {
        append("**App**: FmRadio ").append(BuildConfig.VERSION_NAME)
            .append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
        append("**Android**: ").append(Build.VERSION.RELEASE)
            .append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
        append("**Device**: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
    }

    fun send(context: Context, errorText: String, silent: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 60_000L) return // rate limit: 1 per minute
        lastReport = now

        val title = "FmRadio: " + errorText.lineSequence().firstOrNull()?.take(100).orEmpty()
        val body = systemInfo() + "\n```\n$errorText\n```"

        Thread {
            val ntfyOk = postToNtfy(title, body)
            val ghOk = postToGitHub(title, body)

            main.post {
                if (ntfyOk || ghOk) {
                    if (!silent) {
                        Toast.makeText(context, "Лог отправлен", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    openInBrowser(context, title, body)
                }
            }
        }.start()
    }

    /** Send debug log (not just crash) */
    fun sendLog(context: Context, logText: String) {
        val title = "FmRadio Log: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}"
        val body = systemInfo() + "\n```\n$logText\n```"

        Thread {
            val ntfyOk = postToNtfy(title, body)
            val ghOk = postToGitHub(title, body)

            main.post {
                if (ntfyOk || ghOk) {
                    Toast.makeText(context, "Лог отправлен", Toast.LENGTH_SHORT).show()
                } else {
                    openInBrowser(context, title, body)
                }
            }
        }.start()
    }

    private fun postToNtfy(title: String, body: String): Boolean {
        val topic = BuildConfig.NTFY_TOPIC
        if (topic.isBlank()) return false
        return try {
            val url = URL("https://ntfy.sh/$topic")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Title", title.take(120))
            conn.setRequestProperty("Tags", "warning,android,fmradio")
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            Log.d(TAG, "ntfy.sh: ${conn.responseCode}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "ntfy failed", e)
            false
        }
    }

    private fun postToGitHub(title: String, body: String): Boolean {
        val token = BuildConfig.ISSUE_TOKEN
        if (token.isBlank()) return false
        return try {
            val payload = JSONObject().apply {
                put("title", title)
                put("body", body)
            }.toString()
            val url = URL("https://api.github.com/repos/${BuildConfig.ISSUE_REPO}/issues")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (_: Exception) { false }
    }

    private fun openInBrowser(context: Context, title: String, body: String) {
        try {
            val url = "https://github.com/${BuildConfig.ISSUE_REPO}/issues/new" +
                "?title=" + URLEncoder.encode(title, "UTF-8") +
                "&body=" + URLEncoder.encode(body, "UTF-8")
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {}
    }
}
