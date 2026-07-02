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

    // Однонитевой executor для ВСЕЙ файловой работы логгера: раньше
    // каждый log()/info() читал файл ДО 500КБ целиком и переписывал
    // его целиком — синхронно, на вызывающей нитке. Среди вызывающих:
    // onPlayerError (main thread, ровно когда плейбек и так лежит и
    // может идти реконнект-шторм), catch-блоки кликов в
    // MainActivity/HomeFragment. Однонитевой — ещё и сериализует
    // конкурентные записи (раньше две нитки могли переписывать файл
    // одновременно, теряя записи друг друга).
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "error-logger").apply { isDaemon = true }
    }

    fun log(context: Context, error: String) {
        val appCtx = context.applicationContext
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        try {
            ioExecutor.execute {
                try {
                    val file = getErrorFile(appCtx)
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
        } catch (e: Exception) {
            android.util.Log.e("ErrorLogger", "Failed to enqueue error", e)
        }
    }

    /**
     * Кладёт INFO-событие в тот же tvviewer_errors.txt, но с префиксом
     * `[ts][TAG]`, чтобы вся последовательность работы (HTTP коды,
     * размеры, парсинг, матчинг) была видна одной таймлайной картинкой
     * в "Логе ошибок". Раньше там были только ошибки — пользователь не
     * мог понять, ГДЕ именно теряется EPG, потому что happy-path события
     * никуда не писались.
     */
    fun info(context: Context, tag: String, message: String) {
        android.util.Log.i(tag, message)
        val appCtx = context.applicationContext
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        try {
            ioExecutor.execute {
                try {
                    val file = getErrorFile(appCtx)
                    val line = "[$ts][$tag] $message\n"
                    val content = (readFile(file) + line).takeLast(MAX_SIZE)
                    file.writeText(content)
                } catch (e: Exception) {
                    android.util.Log.e("ErrorLogger", "info write failed", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ErrorLogger", "info enqueue failed", e)
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
            // ExoPlayer чанки плеера регулярно ловят SocketException
            // ("Socket closed") при переключении канала или временном
            // обрыве сети — это норма, а не ошибка. Не пишем их в
            // tvviewer_errors.txt, иначе пользователь шлёт лог за логом
            // про плеерные блипы вместо реальных багов.
            if (t is java.net.SocketException ||
                t is java.net.SocketTimeoutException ||
                t is java.io.InterruptedIOException) return
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

    /** Дожидается, пока очередь фоновых записей опустеет (нужно
     *  крэш-хендлеру перед killProcess — иначе последняя запись о
     *  краше могла не успеть попасть на диск). */
    fun flush(timeoutMs: Long = 2000) {
        try {
            ioExecutor.submit { }.get(timeoutMs,
                java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
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
