package com.tvviewer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Round 222b: запускается раньше MainActivity. В фоне проверяет
 * апдейт. Если нет — finish + start MainActivity. Если есть —
 * AlertDialog «Обновить / Пропустить».
 * «Пропустить» → MainActivity.
 * «Обновить» → splash остаётся видим, показывает «Загрузка
 *   обновления…» + спиннер. UpdateInstaller качает APK, потом
 *   PackageInstaller вызывает системный диалог установки. После
 *   успешной установки система перезапустит app и мы попадём в
 *   новый MainActivity. Если юзер отменил установку или загрузка
 *   упала — UpdateInstaller.onFinishedCallback → MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val CHECK_TIMEOUT_MS = 5_000L
        // Страховка: если splash висит дольше 5 минут на «Загрузке»
        // — что-то пошло не так, открываем MainActivity чтобы юзер
        // не остался на чёрном экране.
        private const val DOWNLOAD_FALLBACK_MS = 5L * 60_000L
    }

    private var proceedJob: Job? = null
    private var alreadyProceeded = false
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        progressBar = findViewById(R.id.splashProgress)
        statusText = findViewById(R.id.splashStatus)

        UpdateCheckerHelper.resetSessionDialogFlag()

        proceedJob = lifecycleScope.launch {
            val prefs = AppPreferences(this@SplashActivity)
            val checkResult = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                runCatching { UpdateChecker.check(prefs.updateCheckUrl).getOrNull() }
                    .getOrNull().let { Result.success(it) }
            }
            if (!isActive) return@launch

            val update = checkResult?.getOrNull()
            if (checkResult != null) {
                prefs.lastUpdateCheckMs = System.currentTimeMillis()
            }

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
                startUpdate(update.downloadUrl)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                proceedToMain()
            }
            .show()
    }

    private fun startUpdate(url: String) {
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.update_downloading)
        UpdateInstaller.onFinishedCallback = { proceedToMain() }
        UpdateInstaller.downloadAndInstall(this, url)
        statusText.postDelayed({ proceedToMain() }, DOWNLOAD_FALLBACK_MS)
    }

    private fun proceedToMain() {
        if (alreadyProceeded) return
        alreadyProceeded = true
        UpdateInstaller.onFinishedCallback = null
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        proceedJob?.cancel()
        UpdateInstaller.onFinishedCallback = null
        super.onDestroy()
    }
}
