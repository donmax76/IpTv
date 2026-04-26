package com.fmradio

import android.app.Application
import android.content.Intent
import android.util.Log
import com.fmradio.dsp.DebugLog
import com.fmradio.util.CrashReportActivity
import com.fmradio.util.CrashReporter
import com.fmradio.util.ErrorLogger
import java.io.PrintWriter
import java.io.StringWriter

class FmRadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("FmRadio", "Uncaught exception", throwable)
                val errorText = getFullStackTrace(throwable)

                // Log to DebugLog file
                try {
                    DebugLog.log("CRASH", "Uncaught ${throwable.javaClass.simpleName} in ${thread.name}: ${throwable.message}")
                    val frames = throwable.stackTrace.take(10).joinToString("\n    ") { it.toString() }
                    DebugLog.log("CRASH", "Stack:\n    $frames")
                    DebugLog.flush()
                } catch (_: Throwable) {}

                // Log to ErrorLogger
                try { ErrorLogger.logException(applicationContext, throwable) } catch (_: Exception) {}

                // Send to CrashReporter
                try { CrashReporter.send(applicationContext, errorText) } catch (_: Exception) {}

                // Show CrashReportActivity
                val intent = Intent(applicationContext, CrashReportActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(CrashReportActivity.EXTRA_ERROR, errorText)
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("FmRadio", "Cannot show crash activity", e)
                }
            } catch (e: Exception) {
                Log.e("FmRadio", "Crash handler failed", e)
            }
        }
    }

    override fun onTerminate() {
        DebugLog.shutdown()
        super.onTerminate()
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
