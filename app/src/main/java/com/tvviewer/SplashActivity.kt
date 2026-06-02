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
 * Round 222c: launcher activity. Полностью прозрачная (тема
 * Theme.TVViewer.Transparent) — пока идёт проверка апдейта юзер
 * НИЧЕГО не видит. Если апдейта нет — finish + MainActivity.
 * Если есть — AlertDialog поверх пустого экрана. Если юзер
 * соглашается обновиться — только тогда setContentView с
 * progress-индикатором; во время download + install splash
 * остаётся видимым, чтобы юзер не попадал в MainActivity до
 * установки новой версии. Если установка отменена или провалилась
 * — UpdateInstaller.onFinishedCallback → MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        // Round 228: 5 → 3 сек таймаут на сеть.
        private const val CHECK_TIMEOUT_MS = 3_000L
        // Round 228: TTL кэша результата проверки. 10 минут «нет
        // апдейта» = пропускаем запрос целиком, splash не открывается
        // вообще.
        private const val CACHE_TTL_MS = 10L * 60_000L
        private const val DOWNLOAD_FALLBACK_MS = 5L * 60_000L
    }

    private var proceedJob: Job? = null
    private var alreadyProceeded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContentView НЕ вызываем — тема прозрачная, окна не видно.
        UpdateCheckerHelper.resetSessionDialogFlag()

        // Round 228: попытка use-cache до сетевого запроса. Если в
        // последние 10 минут проверка уже выполнялась — действуем по
        // её результату без сети.
        val prefs = AppPreferences(this)
        val sinceCheck = System.currentTimeMillis() - prefs.lastUpdateCheckMs
        if (sinceCheck < CACHE_TTL_MS) {
            val cachedCode = prefs.cachedUpdateBuildCode
            if (cachedCode > BuildConfig.VERSION_CODE &&
                prefs.cachedUpdateDownloadUrl.isNotBlank()) {
                val cached = UpdateChecker.UpdateInfo(
                    versionCode = cachedCode,
                    versionName = prefs.cachedUpdateVersionName,
                    downloadUrl = prefs.cachedUpdateDownloadUrl,
                    releaseNotes = prefs.cachedUpdateNotes
                )
                showUpdateDialog(cached)
                return
            }
            // Кэш говорит «апдейта нет» — мгновенно открываем MainActivity.
            proceedToMain()
            return
        }

        proceedJob = lifecycleScope.launch {
            val checkResult = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                runCatching { UpdateChecker.check(prefs.updateCheckUrl).getOrNull() }
                    .getOrNull().let { Result.success(it) }
            }
            if (!isActive) return@launch

            val update = checkResult?.getOrNull()
            if (checkResult != null) {
                prefs.lastUpdateCheckMs = System.currentTimeMillis()
                // Сохраняем результат в кэш для следующего запуска.
                if (update != null && update.versionCode > BuildConfig.VERSION_CODE) {
                    prefs.cachedUpdateBuildCode = update.versionCode
                    prefs.cachedUpdateVersionName = update.versionName
                    prefs.cachedUpdateDownloadUrl = update.downloadUrl
                    prefs.cachedUpdateNotes = update.releaseNotes
                } else {
                    // «Апдейта нет» — обнуляем кэш чтобы следующий
                    // запуск его не показал.
                    prefs.cachedUpdateBuildCode = 0
                    prefs.cachedUpdateDownloadUrl = ""
                }
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
        // Только сейчас инфлейтим лого + прогресс — до этого splash был
        // прозрачный.
        setContentView(R.layout.activity_splash)
        val bar = findViewById<ProgressBar>(R.id.splashProgress).apply {
            visibility = View.VISIBLE
            progress = 0
        }
        val status = findViewById<TextView>(R.id.splashStatus).apply {
            visibility = View.VISIBLE
            text = getString(R.string.update_downloading) + " 0%"
        }
        // Round 227: реальный прогресс с UpdateInstaller — обновляем
        // progress bar и текст «Загрузка X%» по мере скачивания APK.
        UpdateInstaller.onProgressCallback = { pct ->
            bar.progress = pct
            status.text = getString(R.string.update_downloading) + " $pct%"
        }
        UpdateInstaller.onFinishedCallback = { proceedToMain() }
        UpdateInstaller.downloadAndInstall(this, url)
        status.postDelayed({ proceedToMain() }, DOWNLOAD_FALLBACK_MS)
    }

    private fun proceedToMain() {
        if (alreadyProceeded) return
        alreadyProceeded = true
        UpdateInstaller.onFinishedCallback = null
        UpdateInstaller.onProgressCallback = null
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        proceedJob?.cancel()
        UpdateInstaller.onFinishedCallback = null
        UpdateInstaller.onProgressCallback = null
        super.onDestroy()
    }
}
