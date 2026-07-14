package com.tvviewer

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Round 185: общая утилита авто-проверки обновлений APK. Раньше эта
 * логика жила приватным методом в MainActivity, поэтому чек срабатывал
 * только когда юзер на главном экране. Юзер обычно живёт в плеере —
 * "не видно из программы 272 только 271" значит проверка не была
 * запущена. Помещаем тут чтобы и MainActivity, и PlayerActivity могли
 * вызывать одно и то же.
 *
 * Дедуп: 1-часовой троттл через prefs.lastUpdateCheckMs. force=true —
 * пропускает троттл (используется на cold start MainActivity).
 *
 * Per-session флаг (sessionDialogShown) предотвращает повторный показ
 * одного и того же диалога после того как юзер закрыл его и переключил
 * вкладку/активити.
 */
object UpdateCheckerHelper {

    @Volatile private var sessionDialogShown = false

    /** Сбрасывается из MainActivity.onCreate (новая сессия). */
    fun resetSessionDialogFlag() { sessionDialogShown = false }

    fun <A> maybeCheck(activity: A, force: Boolean = false)
            where A : Activity, A : LifecycleOwner {
        if (sessionDialogShown) return
        val prefs = AppPreferences(activity)
        val now = System.currentTimeMillis()
        val sinceLast = now - prefs.lastUpdateCheckMs
        // 1 час — баланс между нагрузкой на GitHub API и свежестью.
        if (!force && sinceLast < 60 * 60 * 1000L) return
        prefs.lastUpdateCheckMs = now
        activity.lifecycleScope.launch {
            try {
                val result = UpdateChecker.check(prefs.updateCheckUrl)
                val updateInfo = result.getOrNull()
                if (updateInfo != null && UpdateChecker.isServerNewer(
                        updateInfo, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)) {
                    val message = buildString {
                        append("${activity.getString(R.string.current_version)}: " +
                            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        append("\n${activity.getString(R.string.new_version)}: " +
                            "${updateInfo.versionName} (${updateInfo.versionCode})")
                        if (updateInfo.releaseNotes.isNotBlank()) {
                            append("\n\n${updateInfo.releaseNotes.take(500)}")
                        }
                    }
                    sessionDialogShown = true
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        AlertDialog.Builder(activity, R.style.Theme_TVViewer_Dialog)
                            .setTitle(activity.getString(
                                R.string.update_available, updateInfo.versionName))
                            .setMessage(message)
                            .setPositiveButton(R.string.update_download) { _, _ ->
                                UpdateInstaller.downloadAndInstall(
                                    activity, updateInfo.downloadUrl)
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.d("TVViewer", "Auto update check failed", e)
            }
        }
    }
}
