package com.tvviewer

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ErrorLogger {

    private const val FILENAME = "tvviewer_errors.txt"
    private const val MAX_SIZE = 500_000

    fun log(context: Context, error: String) {
        try {
            val file = getErrorFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val device = "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.SDK_INT}"
            val entry = """
                |========== $timestamp ==========
                |Device: $device
                |$error
                |
                """.trimMargin()
            val content = (readFile(file) + entry).takeLast(MAX_SIZE)
            file.writeText(content)
        } catch (e: Exception) {
            android.util.Log.e("ErrorLogger", "Failed to save error", e)
        }
    }

    fun logException(context: Context, throwable: Throwable) {
        if (throwable is kotlinx.coroutines.CancellationException) return
        if (throwable.message?.contains("Response code: 403") == true ||
            throwable.message?.contains("Response code: 404") == true) return
        var t: Throwable? = throwable
        while (t != null) {
            if (t.message?.contains("Response code: 403") == true ||
                t.message?.contains("Response code: 404") == true) return
            t = t.cause
        }
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        var cause = throwable.cause
        while (cause != null) {
            sw.append("\nCaused by: ")
            cause.printStackTrace(java.io.PrintWriter(sw))
            cause = cause.cause
        }
        log(context, sw.toString())
    }

    fun getErrorFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILENAME)
    }

    fun getErrorContent(context: Context): String {
        return try {
            val content = readFile(getErrorFile(context))
            if (content.isBlank()) "" else content
        } catch (e: Exception) {
            ""
        }
    }

    fun clear(context: Context) {
        try {
            getErrorFile(context).writeText("")
        } catch (_: Exception) {}
    }

    private fun readFile(file: File): String {
        return if (file.exists()) file.readText() else ""
    }
}
