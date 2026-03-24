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
 * - ALWAYS logs to file (even when UI panel is closed)
 * - Shows last N lines in UI when debug panel is open
 * - saveToFile() exports full log for sharing
 */
object DebugLog {

    private const val TAG = "FmDebugLog"
    private const val MAX_UI_LINES = 500
    private const val LOG_FILE_NAME = "fmradio_debug.log"

    private val uiLines = ArrayDeque<String>(MAX_UI_LINES + 10)
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val sdfFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    private var logWriter: PrintWriter? = null
    private var logFile: File? = null
    private var context: Context? = null

    /** UI panel visible — controls onNewLine callback */
    @Volatile
    var enabled = false

    /** Callback for real-time UI updates */
    var onNewLine: ((String) -> Unit)? = null

    /**
     * Initialize file logging. Call from Application.onCreate() or MainActivity.
     * After this, ALL log() calls write to file regardless of `enabled` flag.
     */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        try {
            val dir = File(ctx.getExternalFilesDir(null), "logs")
            dir.mkdirs()
            logFile = File(dir, LOG_FILE_NAME)

            // Rotate: if log > 2MB, rename to .old and start fresh
            if (logFile!!.exists() && logFile!!.length() > 2 * 1024 * 1024) {
                val old = File(dir, "fmradio_debug.old.log")
                old.delete()
                logFile!!.renameTo(old)
                logFile = File(dir, LOG_FILE_NAME)
            }

            logWriter = PrintWriter(FileWriter(logFile, true), true)
            logWriter?.println("=== FM Radio Debug Log started ${sdfFull.format(Date())} ===")
            logWriter?.println("=== Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ===")
            logWriter?.flush()
            Log.i(TAG, "Log file: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init log file", e)
        }
    }

    /**
     * Log a message. ALWAYS writes to file. Shows in UI only if enabled.
     */
    fun log(tag: String, message: String) {
        val ts = sdf.format(Date())
        val line = "$ts [$tag] $message"

        // Always write to file
        try {
            logWriter?.println(line)
        } catch (_: Exception) {}

        // Buffer for UI
        synchronized(lock) {
            uiLines.addLast(line)
            while (uiLines.size > MAX_UI_LINES) uiLines.removeFirst()
        }

        // Notify UI if panel is open
        if (enabled) {
            onNewLine?.invoke(line)
        }
    }

    /** Flush file writer (call before sharing) */
    fun flush() {
        try { logWriter?.flush() } catch (_: Exception) {}
    }

    fun getText(): String {
        synchronized(lock) {
            return uiLines.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lock) {
            uiLines.clear()
        }
        // Don't clear file — keep full history
    }

    /** Get the log file path for sharing */
    fun getLogFile(): File? = logFile

    /**
     * Save current session to a shareable file and return its path.
     * Returns null on failure.
     */
    fun saveToFile(ctx: Context): File? {
        flush()
        return logFile
    }

    /**
     * Create a share Intent for the log file.
     * Use: startActivity(Intent.createChooser(getShareIntent(ctx), "Share log"))
     */
    fun getShareIntent(ctx: Context): Intent? {
        val file = saveToFile(ctx) ?: return null
        if (!file.exists()) return null

        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (_: Exception) {
            // Fallback: just use file URI (works on older Android without FileProvider)
            android.net.Uri.fromFile(file)
        }

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "FM Radio Debug Log ${sdfFull.format(Date())}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
