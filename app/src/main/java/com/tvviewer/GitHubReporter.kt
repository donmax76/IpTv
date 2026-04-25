package com.tvviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Submits crash / problem reports to the configured GitHub repo's issue tracker.
 *
 * If [BuildConfig.ISSUE_TOKEN] is set at build time, posts directly via
 * the GitHub REST API (no user interaction needed). Otherwise opens the
 * browser at github.com/.../issues/new with the title and body
 * URL-encoded so the user just has to tap "Submit".
 */
object GitHubReporter {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val main = Handler(Looper.getMainLooper())

    fun systemInfo(): String = buildString {
        append("**App**: ").append(BuildConfig.VERSION_NAME)
            .append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
        append("**Android**: ").append(Build.VERSION.RELEASE)
            .append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
        append("**Device**: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
        append("\n")
    }

    /**
     * Reports an issue. On success calls [onSuccess]; on failure either falls
     * back to opening the browser or calls [onError].
     *
     * Always runs the network call on a background thread.
     */
    fun report(
        context: Context,
        title: String,
        body: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        val token = BuildConfig.ISSUE_TOKEN
        if (token.isBlank()) {
            openInBrowser(context, title, body)
            return
        }
        Thread {
            try {
                val payload = JSONObject().apply {
                    put("title", title)
                    put("body", body)
                }.toString()
                val req = Request.Builder()
                    .url("https://api.github.com/repos/${BuildConfig.ISSUE_REPO}/issues")
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", "TVViewer-App/${BuildConfig.VERSION_NAME}")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        main.post {
                            Toast.makeText(context, R.string.error_log_sent, Toast.LENGTH_LONG).show()
                            onSuccess?.invoke()
                        }
                    } else {
                        // Token invalid / rate-limited / repo wrong — fall back to browser
                        main.post { openInBrowser(context, title, body) }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    onError?.invoke(e)
                    openInBrowser(context, title, body)
                }
            }
        }.start()
    }

    private fun openInBrowser(context: Context, title: String, body: String) {
        try {
            val url = "https://github.com/${BuildConfig.ISSUE_REPO}/issues/new" +
                "?title=" + URLEncoder.encode(title, "UTF-8") +
                "&body=" + URLEncoder.encode(body, "UTF-8")
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(context, R.string.error_log_send_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
