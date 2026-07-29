package com.fmradio.util

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.fmradio.BuildConfig
import com.fmradio.dsp.DebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.Toast

/**
 * One button, one file, everything in it.
 *
 * Handing a log over used to mean assembling it by hand. "Send log" gathered
 * three sources into a message and posted it to a tracker the user cannot see
 * the result of, then told them a filesystem path — on a car head unit, where
 * finding that path in a file manager is the hard part. Meanwhile the app had
 * written four separate files (startup.txt, startup-prev.txt,
 * fmradio_errors.txt, report.txt) and there was no way to know which mattered,
 * so the question that came back was "there are other files too, should I send
 * those as well?".
 *
 * And the message it did send was usually empty of the interesting part:
 * DebugLog.getText() reads the in-memory buffer for the on-screen panel, which
 * is only filled while that panel is open. With the panel closed — the normal
 * case — the debug section was blank even though the log FILE had everything.
 *
 * So: assemble one .txt with every source in it, write it where FileProvider
 * can reach it, and hand it straight to the Android share sheet. From there it
 * goes to a messenger, mail, Bluetooth or a USB stick in one tap, with no file
 * manager involved. Copying to the clipboard is offered alongside, because
 * pasting into a chat is what actually happens most of the time.
 */
object LogReport {

    /**
     * Tail kept from each file. The debug log runs to 2 MB and the interesting
     * part is always the end; a report that will not fit in a chat message or
     * a share intent is a report that does not get sent.
     */
    private const val DEBUG_TAIL_BYTES = 400_000
    private const val ERROR_TAIL_BYTES = 100_000

    private val fileStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val readable = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Everything worth knowing, as one plain-text document. */
    fun build(ctx: Context): String = buildString {
        append("=== FMRADIO REPORT ===\n")
        append("time    : ").append(readable.format(Date())).append('\n')
        append("app     : ").append(BuildConfig.VERSION_NAME)
            .append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n")
        append("device  : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Build.DEVICE).append(")\n")
        append("android : ").append(Build.VERSION.RELEASE)
            .append(" api ").append(Build.VERSION.SDK_INT).append('\n')
        append("abis    : ").append(Build.SUPPORTED_ABIS?.joinToString(",")).append('\n')
        append("logging : ").append(if (DebugLog.fileLoggingEnabled) "ON" else "OFF")
            .append(" (file: ").append(DebugLog.getLogFile()?.absolutePath ?: "none").append(")\n")

        // State at the moment the button was pressed. This is here so a report
        // is useful even from a session that ran with file logging off.
        append("\n=== RADIO NOW ===\n")
        append(StatusSnapshot.radio()).append('\n')
        append("rds: ").append(StatusSnapshot.rds()).append('\n')
        if (StatusSnapshot.lastError.isNotBlank()) {
            append("last error: ").append(StatusSnapshot.lastError).append('\n')
        }

        val startup = StartupLog.read()
        if (startup.isNotBlank()) {
            append("\n=== STARTUP LOG ===\n").append(startup).append('\n')
        }

        // The FILE, not the on-screen buffer — see the note above.
        val debugFile = tailOf(DebugLog.getLogFile(), DEBUG_TAIL_BYTES)
        if (debugFile.isNotBlank()) {
            append("\n=== DEBUG LOG (last ").append(debugFile.length).append(" chars) ===\n")
            append(debugFile).append('\n')
        }
        // The on-screen buffer as well when it has lines the file does not
        // (file logging off, panel open).
        val uiBuffer = DebugLog.getText()
        if (uiBuffer.isNotBlank() && debugFile.isBlank()) {
            append("\n=== DEBUG BUFFER (in memory) ===\n").append(uiBuffer).append('\n')
        }

        val errors = tailOf(ErrorLogger.getErrorFile(ctx), ERROR_TAIL_BYTES)
        if (errors.isNotBlank()) {
            append("\n=== ERROR LOG ===\n").append(errors).append('\n')
        }

        append("\n=== END ===\n")
    }

    /**
     * Write the report where FileProvider can reach it. The logs/ directory is
     * already declared in file_paths.xml, so a share URI can be granted for it.
     *
     * @return the file, or null if nothing was writable.
     */
    fun save(ctx: Context, text: String): File? = try {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs")
        dir.mkdirs()
        // Keep only the newest few: a head unit's storage is not large and
        // nobody ever goes back and deletes these by hand.
        dir.listFiles { f -> f.name.startsWith("fmradio-report-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(4)
            ?.forEach { it.delete() }
        val out = File(dir, "fmradio-report-${fileStamp.format(Date())}.txt")
        out.writeText(text)
        out
    } catch (_: Throwable) {
        null
    }

    /**
     * The share sheet, with the report attached as a file AND inlined as text.
     * Some targets take only one or the other: a messenger attaches the file, a
     * plain text field receives the body, and either way the report arrives.
     */
    fun shareIntent(ctx: Context, file: File, text: String): Intent {
        val subject = "FmRadio ${BuildConfig.VERSION_NAME} — ${readable.format(Date())}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TITLE, file.name)
            // Inlined body is capped: an intent that exceeds the binder
            // transaction limit throws TransactionTooLargeException and the
            // share sheet never appears at all.
            putExtra(Intent.EXTRA_TEXT, text.takeLast(120_000))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val uri: Uri? = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (_: Throwable) {
            try { Uri.fromFile(file) } catch (_: Throwable) { null }
        }
        if (uri != null) {
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.clipData = ClipData.newRawUri(file.name, uri)
        }
        return Intent.createChooser(intent, "Отправить отчёт").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** @return false if the clipboard is unavailable, so the caller can say so. */
    fun copyToClipboard(ctx: Context, text: String): Boolean = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FmRadio log", text))
        true
    } catch (_: Throwable) {
        false
    }

    private fun tailOf(file: File?, maxBytes: Int): String {
        if (file == null || !file.exists()) return ""
        return try {
            DebugLog.flush()
            val text = file.readText()
            if (text.length <= maxBytes) text else text.takeLast(maxBytes)
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Build the report, save it, and offer the ways it can leave the device.
     * Both the settings screen and the main screen call this, so there is one
     * behaviour to reason about instead of two copies that drifted apart.
     */
    fun offer(activity: Activity) {
        val text = build(activity)
        val file = save(activity, text)
        val sizeKb = text.length / 1024
        val where = file?.absolutePath ?: "не удалось сохранить файл"

        AlertDialog.Builder(activity)
            .setTitle("Отчёт готов ($sizeKb КБ)")
            .setMessage("Файл:\n$where\n\n" +
                "«Отправить» — выбор приложения (мессенджер, почта, Bluetooth, USB).\n" +
                "«Копировать» — весь текст в буфер обмена.")
            .setPositiveButton("Отправить") { _, _ ->
                if (file == null) {
                    copyToClipboard(activity, text)
                    Toast.makeText(activity, "Файл не сохранён — текст скопирован",
                        Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                try {
                    activity.startActivity(shareIntent(activity, file, text))
                } catch (_: Throwable) {
                    // Nothing on this head unit accepts a share intent — fall
                    // back to the clipboard rather than leave a dead button.
                    copyToClipboard(activity, text)
                    Toast.makeText(activity, "Нет приложения для отправки — текст скопирован",
                        Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Копировать") { _, _ ->
                val ok = copyToClipboard(activity, text)
                Toast.makeText(activity,
                    if (ok) "Отчёт скопирован — вставьте в сообщение"
                    else "Буфер обмена недоступен",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()

        // Best effort in the background, and only that: the user is never left
        // waiting on it and never told it worked when it did not.
        CrashReporter.sendLog(activity, text, silent = true)
    }
}
