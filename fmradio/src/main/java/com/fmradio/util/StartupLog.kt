package com.fmradio.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log that is ALWAYS on and survives the process dying.
 *
 * The existing DebugLog is off by default (fileLoggingEnabled = false), so when
 * the app failed to start on a car head unit there was nothing on disk to look
 * at — the crash handler's own DebugLog.log() calls were silently discarded.
 * Worse, a crash in native code kills the process outright, so the Java
 * uncaught-exception handler never runs and no report is produced at all.
 *
 * This writes a breadcrumb at each startup milestone and flushes immediately.
 * If the app dies, whatever the last line says is where it got to — which
 * identifies a native crash by what is missing, without needing logcat from a
 * device that makes logcat impractical to reach.
 *
 * Kept deliberately tiny and defensive: it must never be the thing that breaks
 * startup.
 */
object StartupLog {

    private const val FILE_NAME = "startup.log"
    private const val PREV_NAME = "startup-prev.log"
    private const val MAX_BYTES = 256 * 1024

    @Volatile
    private var file: File? = null

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Where the log ended up, for showing the user. */
    fun path(): String = file?.absolutePath ?: "(не создан)"

    /**
     * Pick a writable location and start a new session. Internal storage is
     * tried first because it always exists; external is preferred when
     * available only because it is easier for a user to copy off the device.
     */
    fun init(ctx: Context) {
        if (file != null) return
        val candidates = ArrayList<File>()
        try { ctx.getExternalFilesDir(null)?.let { candidates.add(File(it, "logs")) } } catch (_: Throwable) {}
        try { candidates.add(File(ctx.filesDir, "logs")) } catch (_: Throwable) {}

        for (dir in candidates) {
            try {
                dir.mkdirs()
                val f = File(dir, FILE_NAME)
                // Keep the previous session: the interesting one is usually the
                // run that died, not the one being started now.
                if (f.exists()) {
                    val prev = File(dir, PREV_NAME)
                    prev.delete()
                    f.renameTo(prev)
                }
                f.appendText("")     // fails here if the location is not writable
                file = f
                break
            } catch (_: Throwable) {
                // try the next location
            }
        }

        write("=== FmRadio start ===")
        write("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        write("android: ${Build.VERSION.RELEASE} api ${Build.VERSION.SDK_INT}")
        write("abis: ${Build.SUPPORTED_ABIS?.joinToString(",")}")
    }

    /** Record a milestone. Never throws. */
    fun write(message: String) {
        val f = file
        try {
            val line = "${stamp.format(Date())} $message\n"
            android.util.Log.i("FmRadioStartup", message)
            if (f != null) {
                if (f.length() > MAX_BYTES) f.writeText("")
                f.appendText(line)
            }
        } catch (_: Throwable) {
            // a log must never be the reason startup fails
        }
    }

    /**
     * Both sessions, current and previous. The previous one matters most: if
     * the app died, the run that died is the one before the one now reading
     * this. Empty string when nothing was ever written.
     */
    fun read(): String {
        val f = file ?: return ""
        return buildString {
            try {
                val prev = File(f.parentFile, PREV_NAME)
                if (prev.exists()) {
                    append("=== предыдущий запуск ===\n")
                    append(prev.readText())
                    append("\n")
                }
            } catch (_: Throwable) {}
            try {
                append("=== текущий запуск ===\n")
                append(f.readText())
            } catch (_: Throwable) {}
        }
    }

    /** Record a crash in full, independently of any logging preference. */
    fun writeCrash(thread: String, t: Throwable) {
        try {
            val sw = java.io.StringWriter()
            val pw = java.io.PrintWriter(sw)
            t.printStackTrace(pw)
            var cause = t.cause
            while (cause != null) {
                pw.println("Caused by:")
                cause.printStackTrace(pw)
                cause = cause.cause
            }
            write("*** CRASH in thread '$thread' ***\n$sw")
        } catch (_: Throwable) {}
    }
}
