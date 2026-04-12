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
    private const val LOG_FILE_NAME = "fmradio_debug.txt"
    private const val OLD_LOG_FILE_NAME = "fmradio_debug.old.txt"

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

    /** File logging — when OFF, no disk I/O and no memory buffering.
     *  Toggle from debug panel. Default OFF to save memory/storage. */
    @Volatile
    var fileLoggingEnabled = false

    /** Callback for real-time UI updates — set/read under lock to prevent race */
    @Volatile
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

            // One-time migration: if a legacy .log file exists, rename it to .txt
            // so users with old installs see their existing history.
            val legacy = File(dir, "fmradio_debug.log")
            val target = File(dir, LOG_FILE_NAME)
            if (legacy.exists() && !target.exists()) {
                legacy.renameTo(target)
            }
            logFile = target

            // Rotate: if log > 2MB, rename to .old and start fresh
            if (logFile!!.exists() && logFile!!.length() > 2 * 1024 * 1024) {
                val old = File(dir, OLD_LOG_FILE_NAME)
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
        // Skip everything when both file logging and UI are off — zero overhead
        if (!fileLoggingEnabled && !enabled) return

        val ts = sdf.format(Date())
        val line = "$ts [$tag] $message"

        // Write to file only if file logging is enabled
        if (fileLoggingEnabled) {
            try {
                logWriter?.println(line)
            } catch (_: Exception) {}
        }

        // Buffer for UI only if panel is open
        if (enabled) {
            synchronized(lock) {
                uiLines.addLast(line)
                while (uiLines.size > MAX_UI_LINES) uiLines.removeFirst()
            }
            val callback = onNewLine
            try {
                callback?.invoke(line)
            } catch (_: Exception) {}
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
     * Export the current log as a freshly named .txt file for sharing.
     * The exported file uses a timestamped name like fmradio_log_2026-04-08_12-34-56.txt
     * so receiving apps (mail, messengers) treat it as plain text and don't fall
     * back to a generic .bin/octet-stream extension.
     */
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

    /**
     * Create a share Intent for the log file. Builds a fresh timestamped .txt
     * snapshot so the receiving app sees a real text file.
     * Use: startActivity(Intent.createChooser(getShareIntent(ctx), "Share log"))
     */
    fun getShareIntent(ctx: Context): Intent? {
        val file = exportToTxt(ctx) ?: return null
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
