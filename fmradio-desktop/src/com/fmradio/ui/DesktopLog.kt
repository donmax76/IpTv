package com.fmradio.ui

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File logging for the desktop app.
 *
 * The app only ever used println(), which goes nowhere when it is launched
 * through FmRadio.exe (javaw, no console) — so when something misbehaved on a
 * user's machine there was simply nothing to look at. This tees stdout/stderr
 * into a file next to the JAR (falling back to the user's home directory if
 * that location is read-only, e.g. Program Files) and adds timestamps.
 */
object DesktopLog {

    private const val MAX_BYTES = 2L * 1024 * 1024   // rotate at 2 MB

    @Volatile
    private var out: PrintStream? = null

    @Volatile
    var logFile: File? = null
        private set

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Directory the running JAR sits in. */
    private fun appDir(): File? = try {
        val src = DesktopLog::class.java.protectionDomain?.codeSource?.location
        if (src != null) File(src.toURI()).parentFile else null
    } catch (_: Exception) { null }

    private fun candidateFiles(): List<File> {
        val list = ArrayList<File>()
        appDir()?.let { list.add(File(it, "fmradio.log")) }
        list.add(File(System.getProperty("user.home") ?: ".", "fmradio.log"))
        list.add(File(System.getProperty("java.io.tmpdir") ?: ".", "fmradio.log"))
        return list
    }

    /** Start teeing console output to a file. Safe to call once at startup. */
    fun init() {
        if (out != null) return
        for (f in candidateFiles()) {
            try {
                if (f.exists() && f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, "fmradio.log.1")
                    old.delete()
                    f.renameTo(old)
                }
                val fos = FileOutputStream(f, true)
                val ps = PrintStream(fos, true, "UTF-8")
                out = ps
                logFile = f

                val realOut = System.out
                val realErr = System.err
                System.setOut(PrintStream(Tee(realOut, ps), true, "UTF-8"))
                System.setErr(PrintStream(Tee(realErr, ps), true, "UTF-8"))

                ps.println()
                ps.println("========================================")
                log("=== FM Radio v${MainWindow.VERSION} build ${MainWindow.BUILD} ===")
                log("OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
                log("Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
                log("Log file: ${f.absolutePath}")
                return
            } catch (_: Exception) {
                // try the next candidate location
            }
        }
    }

    fun log(message: String) {
        val line = "${stamp.format(Date())} $message"
        out?.println(line) ?: println(line)
    }

    fun flush() {
        try { out?.flush() } catch (_: Exception) {}
    }

    /** Last [n] lines of the log, for showing inside the app. */
    fun tail(n: Int = 200): String {
        val f = logFile ?: return "Лог-файл не создан."
        return try {
            val lines = f.readLines()
            lines.takeLast(n).joinToString("\n")
        } catch (e: Exception) {
            "Не удалось прочитать лог: ${e.message}"
        }
    }

    /** Writes to both the original stream and the log file. */
    private class Tee(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
        override fun write(x: Int) {
            try { a.write(x) } catch (_: Exception) {}
            try { b.write(x) } catch (_: Exception) {}
        }
        override fun write(buf: ByteArray, off: Int, len: Int) {
            try { a.write(buf, off, len) } catch (_: Exception) {}
            try { b.write(buf, off, len) } catch (_: Exception) {}
        }
        override fun flush() {
            try { a.flush() } catch (_: Exception) {}
            try { b.flush() } catch (_: Exception) {}
        }
    }
}
