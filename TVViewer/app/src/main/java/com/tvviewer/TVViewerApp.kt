package com.tvviewer

import android.app.Application
import android.content.Intent
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

class TVViewerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e("TVViewer", "Uncaught exception", throwable)
            val errorText = getFullStackTrace(throwable)
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
