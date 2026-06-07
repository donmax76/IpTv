package com.tvviewer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Round 221f: получает результат PackageInstaller сессии и либо
 * запускает системный диалог подтверждения установки
 * (STATUS_PENDING_USER_ACTION), либо показывает Toast с ошибкой.
 *
 * Round 227a/229a: после успешной установки открывает MainActivity.
 * На Android 9 — напрямую через startActivity. На 10+ background-launch
 * ограничения блокируют это, поэтому ещё постим high-priority
 * notification с setFullScreenIntent — система сама поднимает MainActivity
 * как только разблокирован экран.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "update_complete"
        private const val NOTIFICATION_ID = 4242
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        Log.e("InstallStatusReceiver", "Failed to start install confirm", e)
                        Toast.makeText(context,
                            "Не удалось открыть установщик: ${e.message?.take(80)}",
                            Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context,
                        "Установка: подтверждение запрошено, но Intent пустой",
                        Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Обновление установлено",
                    Toast.LENGTH_SHORT).show()
                launchMainAfterInstall(context)
                UpdateInstaller.notifyFinished()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                UpdateInstaller.notifyFinished()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "code=$status"
                Toast.makeText(context, "Установка не удалась: $msg",
                    Toast.LENGTH_LONG).show()
                Log.e("InstallStatusReceiver", "Install failed status=$status msg=$msg")
                UpdateInstaller.notifyFinished()
            }
        }
    }

    private fun launchMainAfterInstall(context: Context) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                     Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        // На Android 9 это срабатывает напрямую.
        try {
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.w("InstallStatusReceiver",
                "Direct startActivity blocked (background launch restriction)", e)
        }
        // На 10+ background launch блокируется. Уведомление с
        // fullScreenIntent — workaround: Android поднимет активити
        // автоматически как только устройство активно. Ставим всегда
        // — на случай если direct launch не сработал.
        try {
            postOpenAppNotification(context, launchIntent)
        } catch (e: Exception) {
            Log.e("InstallStatusReceiver", "Cannot post launch notification", e)
        }
    }

    private fun postOpenAppNotification(context: Context, launchIntent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Установка обновлений",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Открытие приложения после обновления"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0)
        val openPi = PendingIntent.getActivity(
            context, NOTIFICATION_ID, launchIntent, piFlags)

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_playlist)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Обновление установлено — нажмите чтобы открыть")
            .setContentIntent(openPi)
            // fullScreenIntent ⇒ если экран активен, Android запустит
            // активити автоматически.
            .setFullScreenIntent(openPi, true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notif)
        } catch (e: SecurityException) {
            Log.w("InstallStatusReceiver", "Notification denied", e)
        }
    }
}
