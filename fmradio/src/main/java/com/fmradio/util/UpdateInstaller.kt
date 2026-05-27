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

    fun downloadAndInstall(context: Context, downloadUrl: String) {
        val appContext = context.applicationContext
        Toast.makeText(context, "Загрузка обновления...", Toast.LENGTH_SHORT).show()

        Thread {
            val apk = downloadApk(appContext, downloadUrl)
            main.post {
                if (apk != null && apk.length() > 100_000) {
                    installApk(appContext, apk)
                } else {
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
            Log.e(TAG, "Download error", e)
            null
        }
    }

    private fun installApk(context: Context, apk: File) {
        try {
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
}
