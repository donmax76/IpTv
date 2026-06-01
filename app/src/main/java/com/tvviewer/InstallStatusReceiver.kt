package com.tvviewer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Round 221f: получает результат PackageInstaller сессии и либо
 * запускает системный диалог подтверждения установки
 * (STATUS_PENDING_USER_ACTION), либо показывает Toast с ошибкой.
 *
 * Без этого ресивера PackageInstaller.commit() молча зависает — система
 * не знает кому отдать управление, и юзер видит «загружено» а потом
 * ничего.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Это и есть момент когда нужно открыть системный
                // installer dialog. Берём confirmation Intent из extras
                // и запускаем как новую задачу.
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
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // юзер отменил — не шумим
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "code=$status"
                Toast.makeText(context, "Установка не удалась: $msg",
                    Toast.LENGTH_LONG).show()
                Log.e("InstallStatusReceiver", "Install failed status=$status msg=$msg")
            }
        }
    }
}
