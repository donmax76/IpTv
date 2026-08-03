package com.fmradio.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the update APK directly via HttpURLConnection (which reliably
 * follows GitHub's 302 redirect from releases/latest/download/ to
 * objects.githubusercontent.com) and installs it via FileProvider.
 *
 * The previous DownloadManager approach failed on BYD DiLink because its
 * DownloadManager implementation did not follow the GitHub redirect →
 * download failed → fell back to opening the browser.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private val main = Handler(Looper.getMainLooper())

    /**
     * An APK that is downloaded and verified but could not be installed yet
     * because "install unknown apps" was not granted. The permission screen is
     * a different activity, so the only way back is the user returning to us —
     * at which point this gets installed without downloading it again.
     *
     * Without this the flow was: download 5 MB, get told to grant a permission,
     * grant it, come back to an app that had forgotten everything, and start
     * over from "check for updates". That is the "и повторите" the user saw.
     */
    @Volatile
    private var pendingApk: File? = null

    fun downloadAndInstall(context: Context, downloadUrl: String) {
        val appContext = context.applicationContext
        Toast.makeText(context, "Загрузка обновления...", Toast.LENGTH_SHORT).show()
        StartupLog.write("update: download from $downloadUrl")

        Thread {
            val apk = downloadApk(appContext, downloadUrl)
            main.post {
                if (apk != null && apk.length() > 100_000) {
                    StartupLog.write("update: downloaded ${apk.length()} bytes, installing")
                    installApk(appContext, apk)
                } else {
                    // Falling back to the browser is the visible symptom the
                    // user reports; it means the DOWNLOAD failed, not the
                    // install. Record why, because previously nothing did.
                    StartupLog.write("update: download FAILED (file=${apk?.length() ?: -1}), opening browser")
                    Toast.makeText(appContext, "Ошибка загрузки, открываю браузер", Toast.LENGTH_LONG).show()
                    openInBrowser(appContext, downloadUrl)
                }
            }
        }.start()
    }

    private fun downloadApk(context: Context, downloadUrl: String): File? {
        return try {
            val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val outFile = File(dir, "FmRadio-update.apk")
            if (outFile.exists()) outFile.delete()

            var url = downloadUrl
            var conn: HttpURLConnection
            var redirects = 0
            // Manually follow redirects (incl. http→https which HttpURLConnection
            // refuses to auto-follow) up to 5 hops.
            while (true) {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "FmRadio-Updater")
                }
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    val loc = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    url = loc
                    redirects++
                    continue
                }
                break
            }

            if (conn.responseCode !in 200..299) {
                StartupLog.write("update: HTTP ${conn.responseCode} after $redirects redirect(s)")
                Log.e(TAG, "Download failed: HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }

            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            conn.disconnect()
            Log.i(TAG, "Downloaded ${outFile.length()} bytes to $outFile")
            outFile
        } catch (e: Exception) {
            StartupLog.write("update: download error ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Download error", e)
            null
        }
    }

    /**
     * Hand the APK to the system installer, and do it FIRST.
     *
     * This used to test canRequestPackageInstalls() up front and, when it said
     * no, refuse to try — showing a toast and opening the "install unknown
     * apps" screen instead. On the BYD head unit that screen does not offer the
     * switch at all, so the update went from working to a dead end that could
     * never be got out of. The check was mine and it was wrong twice over: the
     * head unit installs happily without it, and even where the permission is
     * genuinely missing Android's own package installer puts up a far better
     * prompt than a second-guess from here.
     *
     * So: launch the install. If the system needs a permission it will say so
     * itself, in its own words, with its own button. The APK is kept either way
     * so that coming back finishes the job without downloading it again.
     */
    private fun installApk(context: Context, apk: File) {
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        } catch (e: Exception) {
            StartupLog.write("update: FileProvider failed (${e.javaClass.simpleName}), using file URI")
            Uri.fromFile(apk)
        }

        // The modern route first, then the pre-Oreo one. Some head-unit ROMs
        // only register a handler for one of them.
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
            },
            @Suppress("DEPRECATION")
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }
        )
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(intent)
                StartupLog.write("update: install launched (${intent.action})")
                // Launched: nothing left pending. Keeping it would relaunch the
                // installer every time the user came back to the app, including
                // after the update had already been installed.
                pendingApk = null
                return
            } catch (e: Exception) {
                StartupLog.write("update: ${intent.action} refused: ${e.javaClass.simpleName}")
            }
        }

        // Nothing on this device would take the APK at all. Keep it so a later
        // return to the app can try once more, and only now is the permission
        // screen worth offering — as a suggestion, not a demand.
        pendingApk = apk
        StartupLog.write("update: no installer accepted the APK")
        Toast.makeText(context,
            "Система не открыла установщик. Файл скачан: ${apk.absolutePath}",
            Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()) {
            try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                           Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    private fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    /**
     * Called when an activity comes back to the foreground. If a download is
     * waiting on the install permission and that permission now exists, finish
     * the update.
     *
     * @return true if an install was started, so the caller can say so.
     */
    fun resumePendingInstall(context: Context): Boolean {
        val apk = pendingApk ?: return false
        if (!apk.exists() || apk.length() < 100_000) {
            pendingApk = null
            return false
        }
        // No permission test here. On this head unit canRequestPackageInstalls()
        // answers no for ever, so gating on it meant a download that had been
        // refused once could never be retried. Clear it BEFORE retrying so a
        // failure cannot turn into a loop of installer launches.
        pendingApk = null
        StartupLog.write("update: retrying pending install")
        installApk(context, apk)
        return true
    }
}
