package com.tvviewer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Round 221: запускается раньше MainActivity. Проверяет апдейт, при
 * наличии показывает диалог «обновить / пропустить». В любом случае
 * (нет обновления / таймаут / пользователь отказался / уже скачивает)
 * переходит в MainActivity. Без отдельного splash'а проверка стартовала
 * уже после показа главного экрана и пользователь видел приложение до
 * того, как узнавал про апдейт.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        // Жёсткий таймаут — даже если сеть тупит, не держим юзера на
        // splash дольше этого. 6 сек достаточно для одной HTTPS-GET'а
        // GitHub API + парсинга нескольких сотен релизов.
        private const val CHECK_TIMEOUT_MS = 6_000L
    }

    private var proceedJob: Job? = null
    private var alreadyProceeded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        UpdateCheckerHelper.resetSessionDialogFlag()

        // Параллельно: проверка апдейта (с таймаутом) и переход в Main.
        // Если апдейт найден — спросим юзера; если нет — после таймаута
        // / завершения проверки сразу пускаем в MainActivity.
        proceedJob = lifecycleScope.launch {
            val prefs = AppPreferences(this@SplashActivity)
            val update = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                runCatching { UpdateChecker.check(prefs.updateCheckUrl).getOrNull() }
                    .getOrNull()
            }
            // Закрепляем «последний раз проверяли — сейчас», чтобы
            // UpdateCheckerHelper.maybeCheck в MainActivity не запустил
            // вторую проверку сразу же.
            prefs.lastUpdateCheckMs = System.currentTimeMillis()

            if (!isActive) return@launch

            if (update != null && update.versionCode > BuildConfig.VERSION_CODE) {
                showUpdateDialog(update)
            } else {
                proceedToMain()
            }
        }
    }

    private fun showUpdateDialog(update: UpdateChecker.UpdateInfo) {
        if (isFinishing || isDestroyed || alreadyProceeded) return
        val message = buildString {
            append("${getString(R.string.current_version)}: " +
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            append("\n${getString(R.string.new_version)}: " +
                "${update.versionName} (${update.versionCode})")
            if (update.releaseNotes.isNotBlank()) {
                append("\n\n${update.releaseNotes.take(500)}")
            }
        }
        AlertDialog.Builder(this, R.style.Theme_TVViewer_Dialog)
            .setTitle(getString(R.string.update_available, update.versionName))
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.update_download) { _, _ ->
                UpdateInstaller.downloadAndInstall(this, update.downloadUrl)
                // Установщик откроет системный installer; MainActivity
                // запустим всё равно, чтобы юзер мог продолжить работу
                // пока скачивается / устанавливается.
                proceedToMain()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                proceedToMain()
            }
            .show()
    }

    private fun proceedToMain() {
        if (alreadyProceeded) return
        alreadyProceeded = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        proceedJob?.cancel()
        super.onDestroy()
    }
}
