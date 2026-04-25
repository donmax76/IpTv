package com.tvviewer

import android.app.Application
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.PrintWriter
import java.io.StringWriter

class TVViewerApp : Application() {

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
