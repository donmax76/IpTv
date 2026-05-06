package com.tvviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Round 186: ПОЛНЫЙ РЕРАЙТ. Раньше использовался Android DownloadManager —
 * на Allwinner / Rockchip TV-боксах он принимает enqueue() но
 * BroadcastReceiver на ACTION_DOWNLOAD_COMPLETE никогда не приходит,
 * прогресс невидимый, юзер видит "Загрузка" в Toast и ничего больше не
 * происходит. Жалоба: "загрузка пишет но ничего не качает".
 *
 * Теперь качаем напрямую через OkHttp, показываем процент в Toast,
 * сохраняем в cacheDir, и запускаем установщик через FileProvider.
 * Никаких системных сервисов, никаких permission'ов.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ongoing: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun downloadAndInstall(context: Context, downloadUrl: String) {
        if (ongoing?.isActive == true) {
            Toast.makeText(context, R.string.update_downloading, Toast.LENGTH_SHORT).show()
            return
        }
        if (!downloadUrl.lowercase().endsWith(".apk")) {
            Toast.makeText(
                context,
                "Сборка ещё загружается на GitHub. Попробуйте через минуту.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        // Round 203: проверяем системное разрешение "Установка из
        // неизвестных источников" ДО скачивания. Без него
        // PackageInstaller игнорирует наш Intent и юзер видит "ничего
        // не происходит" после загрузки. На Android 8+ это per-app
        // permission (canRequestPackageInstalls), на 7 и ниже — system-
        // wide setting в "Безопасности". Если выключено — отправляем
        // юзера прямо в нужную страницу настроек.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val canInstall = try { context.packageManager.canRequestPackageInstalls() }
                catch (_: Throwable) { true }  // some TV ROMs throw — assume ok
            if (!canInstall) {
                promptInstallPermission(context)
                return
            }
        }
        val appCtx = context.applicationContext
        val outFile = File(appCtx.cacheDir, "TVViewer-update.apk")
        if (outFile.exists()) outFile.delete()

        toast(appCtx, "Загрузка обновления…")
        ongoing = scope.launch {
            val ok = try {
                doDownload(downloadUrl, outFile, appCtx)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                false
            }
            withContext(Dispatchers.Main) {
                if (ok && outFile.exists() && outFile.length() > 0) {
                    toast(appCtx, "Загружено, открываю установку")
                    triggerInstall(appCtx, outFile)
                } else {
                    Toast.makeText(appCtx,
                        "Не удалось скачать обновление. Откройте страницу релиза.",
                        Toast.LENGTH_LONG).show()
                    openInBrowser(appCtx, downloadUrl)
                }
            }
        }
    }

    private fun doDownload(url: String, dest: File, ctx: Context): Boolean {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "TVViewer-App")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "HTTP ${resp.code}")
                return false
            }
            val body = resp.body ?: return false
            val total = body.contentLength()
            var lastReportedPct = -1
            var written = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            val pct = ((written * 100) / total).toInt()
                            // Только при изменении на 10% — иначе будет
                            // 100 toast'ов спамить очередь.
                            val bucket = (pct / 10) * 10
                            if (bucket != lastReportedPct && bucket > 0 && bucket < 100) {
                                lastReportedPct = bucket
                                mainHandler.post {
                                    Toast.makeText(ctx,
                                        "Загрузка обновления: $bucket%",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
        return true
    }

    private fun toast(ctx: Context, text: String) {
        mainHandler.post {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerInstall(ctx: Context, apk: File) {
        try {
            val authority = "${ctx.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(ctx, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install intent failed", e)
            Toast.makeText(ctx,
                "Не удалось запустить установку: ${e.message?.take(80)}",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun openInBrowser(ctx: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {}
    }

    /** Round 203: открываем системную страницу "Установка из
     *  неизвестных источников" для текущего приложения с пояснением
     *  что нужно сделать. Без AlertDialog (нужен Activity-контекст,
     *  а нас могут дёрнуть из applicationContext) — просто длинный
     *  Toast + Intent. */
    private fun promptInstallPermission(ctx: Context) {
        Toast.makeText(
            ctx,
            "Включите «Установка из неизвестных источников» для TVViewer и нажмите «Обновить» снова.",
            Toast.LENGTH_LONG
        ).show()
        try {
            val intent = if (android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.O) {
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open install-sources settings", e)
            // Fallback: просто открываем общие настройки приложения.
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
