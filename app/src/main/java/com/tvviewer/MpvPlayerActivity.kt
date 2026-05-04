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
 * Используется когда ExoPlayer + nextlib не вытягивает поток
 * (HEVC/H.265, нестандартный HLS, например ARB на X4 X4).
 *
 * Round A: минимальный скелет — открывает URL, играет на SurfaceView,
 * выход по BACK. Без overlay/EPG/переключения каналов. Достаточно
 * чтобы проверить вытягивает ли libmpv проблемные каналы.
 *
 * Дальнейшие раунды: оверлей со списком + EPG + CH+/CH- + gestures.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        if (currentUrl.isNullOrBlank()) {
            Log.e(TAG, "No URL given")
            finish()
            return
        }
        initMpv()
    }

    private fun initMpv() {
        try {
            val configDir = filesDir.absolutePath
            // libmpv API: create() → setOptionString*() → init().
            MPVLib.create(this, "v")  // 'v' = verbose log level
            MPVLib.setOptionString("config", "yes")
            MPVLib.setOptionString("config-dir", configDir)
            // hwdec auto-safe: пытается аппаратный декодер где безопасно,
            // иначе software. На X4 X4 H.265 уйдёт в software FFmpeg
            // (как в Vimu).
            MPVLib.setOptionString("hwdec", "auto-safe")
            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("ao", "audiotrack,opensles")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "yes")
            MPVLib.setOptionString("cache-secs", "10")
            MPVLib.init()
            mpvInitialized = true
            Log.d(TAG, "MPV initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "MPV init failed", e)
            finish()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!mpvInitialized) return
        try {
            MPVLib.attachSurface(holder.surface)
            currentUrl?.let { url ->
                MPVLib.command(arrayOf("loadfile", url))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "surfaceCreated failed", e)
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
