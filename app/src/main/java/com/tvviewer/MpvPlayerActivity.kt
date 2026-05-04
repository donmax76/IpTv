package com.tvviewer

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.jdtech.mpv.MPVLib

/**
 * Альтернативный плеер на libmpv (тот же движок что в Vimu Player).
 * Round A: минимальный скелет + диагностика — каждый шаг пишется
 * в ErrorLogger ("Сообщить о проблеме" в Settings), чтобы юзер
 * прислал лог если плеер не работает.
 */
class MpvPlayerActivity : BaseActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_URL = "channel_url"
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        private const val TAG = "MpvPlayerActivity"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var nameLabel: TextView
    private var currentUrl: String? = null
    private var mpvInitialized = false

    private fun logStep(step: String) {
        try { ErrorLogger.info(this, "MPV", step) } catch (_: Throwable) {}
        Log.d(TAG, step)
    }

    private fun logError(step: String, e: Throwable) {
        try {
            ErrorLogger.info(this, "MPV", "ERROR at $step: " +
                "${e.javaClass.simpleName}: ${e.message?.take(200)}")
            ErrorLogger.logException(this, e)
        } catch (_: Throwable) {}
        Log.e(TAG, step, e)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logStep("onCreate started")
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val root = FrameLayout(this).apply {
                setBackgroundColor(0xFF000000.toInt())
            }
            surfaceView = SurfaceView(this).apply {
                holder.addCallback(this@MpvPlayerActivity)
            }
            root.addView(surfaceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            loadingIndicator = ProgressBar(this).apply { isIndeterminate = true }
            root.addView(loadingIndicator, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            ))
            val labelHolder = LinearLayout(this).apply {
                setBackgroundColor(0x80000000.toInt())
                setPadding(24, 16, 24, 16)
            }
            nameLabel = TextView(this).apply {
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
            }
            labelHolder.addView(nameLabel)
            root.addView(labelHolder, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.START
            ))
            setContentView(root)

            nameLabel.text = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
            currentUrl = intent.getStringExtra(EXTRA_CHANNEL_URL)
            logStep("intent url=${currentUrl?.take(80)} name=${nameLabel.text}")
            if (currentUrl.isNullOrBlank()) {
                logStep("No URL given - finishing")
                finish()
                return
            }
            initMpv()
        } catch (e: Throwable) {
            logError("onCreate", e)
            android.widget.Toast.makeText(this,
                "Ошибка MPV: ${e.javaClass.simpleName}: ${e.message?.take(80)}",
                android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initMpv() {
        try {
            logStep("initMpv: loadLibrary mpv")
            // Ручной load на случай auto-load не сработал (тесты на X4 X4
            // показали что иногда static init MPVLib не загружает .so).
            try { System.loadLibrary("mpv") } catch (e: Throwable) {
                logError("loadLibrary mpv", e)
                throw e
            }
            try { System.loadLibrary("player") } catch (_: Throwable) {
                // 'player' — JNI bridge, бывает называется иначе. Не критично.
            }
            val configDir = filesDir.absolutePath
            logStep("MPVLib.create(this), configDir=$configDir")
            MPVLib.create(this)
            logStep("create OK, setting options")
            MPVLib.setOptionString("msg-level", "all=info")
            MPVLib.setOptionString("config", "yes")
            MPVLib.setOptionString("config-dir", configDir)
            MPVLib.setOptionString("hwdec", "auto-safe")
            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("ao", "audiotrack,opensles")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "yes")
            MPVLib.setOptionString("cache-secs", "10")
            logStep("MPVLib.init()")
            MPVLib.init()
            mpvInitialized = true
            logStep("MPV initialized OK")
        } catch (e: Throwable) {
            logError("initMpv", e)
            android.widget.Toast.makeText(this,
                "MPV init failed: ${e.javaClass.simpleName}",
                android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        logStep("surfaceCreated mpv=$mpvInitialized")
        if (!mpvInitialized) return
        try {
            MPVLib.attachSurface(holder.surface)
            currentUrl?.let { url ->
                logStep("loadfile $url")
                MPVLib.command(arrayOf("loadfile", url))
            }
        } catch (e: Throwable) {
            logError("surfaceCreated", e)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        try {
            MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
        } catch (_: Throwable) {}
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!mpvInitialized) return
        try { MPVLib.detachSurface() } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mpvInitialized) {
            try { MPVLib.command(arrayOf("stop")) } catch (_: Throwable) {}
            try { MPVLib.destroy() } catch (_: Throwable) {}
            mpvInitialized = false
        }
    }
}
