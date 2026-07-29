package com.fmradio.dsp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Debug logger for FM Radio.
 *
 * Two levels, deliberately:
 *  - an in-memory ring of the last MAX_LINES lines, ALWAYS kept. Callers have
 *    already built the message string by the time they get here, so the added
 *    cost is one deque push; and a problem is noticed after it happens, so a
 *    report from a session that ran with logging off used to contain nothing
 *    at all. Now it always carries recent history.
 *  - the file, written only when fileLoggingEnabled = true (LOG:ON). That is
 *    the part with real cost — disk I/O on every line — and it stays opt-in.
 */
object DebugLog {

    private const val TAG = "FmDebugLog"
    private const val MAX_UI_LINES = 500
    private const val LOG_FILE_NAME = "fmradio_debug.txt"
    private const val OLD_LOG_FILE_NAME = "fmradio_debug.old.txt"

    private val uiLines = ArrayDeque<String>(MAX_UI_LINES + 10)
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val sdfFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    private var logWriter: PrintWriter? = null
    private var logFile: File? = null
    private var context: Context? = null

    /** UI panel visible */
    @Volatile
    var enabled = false

    /** File logging — ONLY when ON does anything happen.
     *  Default OFF = zero overhead, zero disk I/O, zero memory. */
    @Volatile
    var fileLoggingEnabled = false

    /** Callback for real-time UI updates */
    @Volatile
    var onNewLine: ((String) -> Unit)? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        try {
            val dir = File(ctx.getExternalFilesDir(null), "logs")
            dir.mkdirs()

            val legacy = File(dir, "fmradio_debug.log")
            val target = File(dir, LOG_FILE_NAME)
            if (legacy.exists() && !target.exists()) {
                legacy.renameTo(target)
            }
            logFile = target

            if (logFile!!.exists() && logFile!!.length() > 2 * 1024 * 1024) {
                val old = File(dir, OLD_LOG_FILE_NAME)
                old.delete()
                logFile!!.renameTo(old)
                logFile = File(dir, LOG_FILE_NAME)
            }

            Log.i(TAG, "Log file ready: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init log file", e)
        }
    }

    /**
     * Log a message. The in-memory ring always takes it; the file only when
     * file logging is switched on.
     */
    fun log(tag: String, message: String) {
        val ts = sdf.format(Date())
        val line = "$ts [$tag] $message"

        synchronized(lock) {
            uiLines.addLast(line)
            while (uiLines.size > MAX_UI_LINES) uiLines.removeFirst()
        }
        if (enabled) {
            try { onNewLine?.invoke(line) } catch (_: Exception) {}
        }

        if (!fileLoggingEnabled) return

        try {
            if (logWriter == null) {
                logWriter = PrintWriter(FileWriter(logFile, true), true)
                logWriter?.println("=== Log started ${sdfFull.format(Date())} ===")
                logWriter?.println("=== Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ===")
            }
            logWriter?.println(line)
        } catch (_: Exception) {}
    }

    fun flush() {
        try { logWriter?.flush() } catch (_: Exception) {}
    }

    /** The in-memory ring — populated whatever the logging preference is. */
    fun getText(): String {
        synchronized(lock) { return uiLines.joinToString("\n") }
    }

    fun clear() {
        synchronized(lock) { uiLines.clear() }
    }

    fun getLogFile(): File? = logFile

    fun saveToFile(ctx: Context): File? {
        flush()
        return logFile
    }

    fun exportToTxt(ctx: Context): File? {
        flush()
        val src = logFile ?: return null
        if (!src.exists()) return null
        return try {
            val dir = File(ctx.getExternalFilesDir(null), "logs")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val out = File(dir, "fmradio_log_$stamp.txt")
            src.inputStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "exportToTxt failed", e)
            null
        }
    }

    fun getShareIntent(ctx: Context): Intent? {
        val file = exportToTxt(ctx) ?: return null
        if (!file.exists()) return null
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (_: Exception) {
            android.net.Uri.fromFile(file)
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "FM Radio Debug Log ${sdfFull.format(Date())}")
            putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
        }
    }

    fun shutdown() {
        try {
            logWriter?.println("=== Log closed ${sdfFull.format(Date())} ===")
            logWriter?.flush()
            logWriter?.close()
        } catch (_: Exception) {}
        logWriter = null
    }
}
