package com.fmradio.util

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.fmradio.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Best-effort network delivery of a report.
 *
 * This is the developer's own inbox and nothing more. It is never the way a
 * report reaches a person: both destinations can be unconfigured or blocked,
 * and neither tells the user anything they can act on. LogReport does the part
 * that has to work — one file, handed to the share sheet.
 *
 * The old fallback here was to open a browser at a GitHub "new issue" URL with
 * the whole log percent-encoded into the query string. A real log is tens to
 * hundreds of kilobytes; browsers cut URLs off around 8 KB and the server
 * rejects what is left, so the user was sent to a broken page instead of being
 * told the send had failed. That fallback is gone.
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
        if (now - lastReport < 60_000L) {
            // Say so. Returning quietly here made the button look broken —
            // the user pressed it and nothing whatsoever happened.
            if (!silent) main.post {
                Toast.makeText(context, "Уже отправлено, подождите минуту",
                    Toast.LENGTH_SHORT).show()
            }
            return
        }
        lastReport = now

        val title = "FmRadio: " + errorText.lineSequence().firstOrNull()?.take(100).orEmpty()
        val body = systemInfo() + "\n```\n" + errorText.takeLast(60_000) + "\n```"

        Thread {
            val delivered = postToNtfy(title, body) or postToGitHub(title, body)
            if (!silent) {
                main.post {
                    Toast.makeText(context,
                        if (delivered) "Лог отправлен"
                        else "Отправить не удалось — используйте «Отправить лог» в настройках",
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Post a report to whichever destination is configured.
     *
     * @param silent no toast either way — used when the caller has already
     *   given the user a real way to send the report and this is only a copy
     *   going to the developer's inbox. Announcing "Лог отправлен" for it would
     *   be a claim about a delivery the user cannot check.
     */
    fun sendLog(context: Context, logText: String, silent: Boolean = false) {
        val title = "FmRadio Log: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}"
        // Trackers reject oversized bodies outright; the tail is the part that
        // matters and a rejected post delivers nothing at all.
        val body = systemInfo() + "\n```\n" + logText.takeLast(60_000) + "\n```"

        Thread {
            val delivered = postToNtfy(title, body) or postToGitHub(title, body)
            if (!silent) {
                main.post {
                    Toast.makeText(context,
                        if (delivered) "Лог отправлен"
                        else "Отправить не удалось — сохраните файл и приложите к сообщению",
                        Toast.LENGTH_LONG).show()
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
            // Read the code BEFORE disconnecting — afterwards the connection is
            // torn down and the query can throw or reopen it.
            val code = conn.responseCode
            conn.disconnect()
            Log.d(TAG, "ntfy.sh: $code")
            code in 200..299
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

}
