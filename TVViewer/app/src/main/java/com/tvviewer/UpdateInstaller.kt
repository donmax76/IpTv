package com.tvviewer

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Downloads APK and triggers install. Uses DownloadManager + install intent.
 */
object UpdateInstaller {

    private var downloadId: Long = -1
    private var receiver: BroadcastReceiver? = null

    fun downloadAndInstall(context: Context, downloadUrl: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle("TVViewer Update")
            setDescription("Downloading update...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "TVViewer-update.apk")
            setMimeType("application/vnd.android.package-archive")
        }
        downloadId = dm.enqueue(request)
        Toast.makeText(context, context.getString(R.string.update_downloading), Toast.LENGTH_LONG).show()

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                try {
                    val uri = dm.getUriForDownloadedFile(downloadId)
                    if (uri != null) {
                        installApk(ctx, uri)
                    } else {
                        openInBrowser(ctx, downloadUrl)
                    }
                } catch (e: Exception) {
                    openInBrowser(ctx, downloadUrl)
                }
                try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                receiver = null
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), flags)
    }

    private fun installApk(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            openInBrowser(context, uri.toString())
        }
    }

    private fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.update_download)))
        } catch (_: Exception) {}
    }
}
