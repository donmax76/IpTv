package com.fmradio

import android.app.Application
import com.fmradio.dsp.DebugLog

class FmRadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)

        // Global crash handler — log the exception to debug file before dying
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                DebugLog.log("CRASH", "Uncaught ${throwable.javaClass.simpleName} in ${thread.name}: ${throwable.message}")
                // Log first 10 stack frames
                val frames = throwable.stackTrace.take(10).joinToString("\n    ") { it.toString() }
                DebugLog.log("CRASH", "Stack:\n    $frames")
                val cause = throwable.cause
                if (cause != null) {
                    DebugLog.log("CRASH", "Caused by: ${cause.javaClass.simpleName}: ${cause.message}")
                }
                DebugLog.flush()
            } catch (_: Throwable) {
                // Can't log — just die
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onTerminate() {
        DebugLog.shutdown()
        super.onTerminate()
    }
}
