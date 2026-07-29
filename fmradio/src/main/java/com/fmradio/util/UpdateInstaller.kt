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

    private fun installApk(context: Context, apk: File) {
        try {
            // Android 8 and later refuse an install from an app that has not
            // been granted "install unknown apps". Without this check the
            // install intent simply does nothing and the user is left with a
            // downloaded file and no explanation.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()) {
                StartupLog.write("update: install permission not granted, opening settings")
                // Hold on to the download so returning from the permission
                // screen finishes the job instead of starting it again.
                pendingApk = apk
                Toast.makeText(context,
                    "Включите «Разрешить установку из этого источника», " +
                    "затем вернитесь назад — установка продолжится сама",
                    Toast.LENGTH_LONG).show()
                val perm = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(perm)
                } catch (_: Exception) {
                    // Some head units ship without that settings screen. Send
                    // the user to the app's own settings page, where the same
                    // switch lives, rather than leaving them with a dead end.
                    try {
                        context.startActivity(Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        Toast.makeText(context,
                            "Разрешите установку приложений из этого источника в настройках Android",
                            Toast.LENGTH_LONG).show()
                    }
                }
                return
            }
            pendingApk = null
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            StartupLog.write("update: install failed ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Install failed", e)
            Toast.makeText(context, "Ошибка установки", Toast.LENGTH_LONG).show()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()) {
            return false          // still not granted; leave it pending
        }
        StartupLog.write("update: permission granted, resuming install")
        pendingApk = null
        Toast.makeText(context, "Разрешение получено — устанавливаю обновление",
            Toast.LENGTH_SHORT).show()
        installApk(context, apk)
        return true
    }
}
