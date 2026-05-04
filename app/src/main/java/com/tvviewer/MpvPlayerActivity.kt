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
            logStep("initMpv: preload FFmpeg deps + mpv")
            // Round 172: явная предзагрузка зависимостей libmpv.so в порядке
            // dlopen-цепочки. Без этого 32-битный bionic-линкер на X4 X4 не
            // разрешал GNU-version-симвоы (av_default_item_name@LIBAVUTIL_59
            // → libavutil.so), хотя символ присутствует. Загрузка по одной
            // .so заставляет линкер кешировать каждую как RTLD_GLOBAL.
            for (lib in listOf(
                "c++_shared", "avutil", "swresample", "swscale",
                "avcodec", "avformat", "avfilter", "avdevice", "postproc",
                "mpv", "player",
            )) {
                try {
                    System.loadLibrary(lib)
                    logStep("loaded $lib")
                } catch (e: UnsatisfiedLinkError) {
                    if (lib == "player") {
                        // JNI-bridge не критичен — MPVLib статически грузит сам.
                        logStep("skip optional $lib: ${e.message?.take(80)}")
                    } else if (lib == "mpv") {
                        logError("loadLibrary $lib", e)
                        throw e
                    } else {
                        // Остальные .so могут не быть прямыми зависимостями
                        // на других ABI — не валим.
                        logStep("skip $lib: ${e.message?.take(80)}")
                    }
                }
            }
            val configDir = filesDir.absolutePath
            logStep("MPVLib.create(this), configDir=$configDir")
            MPVLib.create(this)
            logStep("create OK, setting options")
            MPVLib.setOptionString("msg-level", "all=info")
            MPVLib.setOptionString("config", "yes")
            MPVLib.setOptionString("config-dir", configDir)
            MPVLib.setOptionString("hwdec", "auto-safe")
            // Стартуем БЕЗ видеовыхода — переключим на gpu в surfaceCreated
            // когда уже есть валидный Surface. Иначе MPV пытается
            // инициализировать gpu-renderer на null-surface и навсегда
            // остаётся в "audio-only" состоянии, как сейчас у юзера.
            MPVLib.setOptionString("vo", "null")
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
                "MPV не работает, использую встроенный плеер",
                android.widget.Toast.LENGTH_LONG).show()
            // Round 172: автофоллбек на ExoPlayer если libmpv не загружается
            // (старый bionic не понимает GNU symbol versioning, etc).
            // Юзер не остаётся со сломанным экраном.
            fallbackToExoPlayer()
        }
    }

    private fun fallbackToExoPlayer() {
        try {
            val src = intent
            val target = android.content.Intent(this, PlayerActivity::class.java)
            target.putExtra(PlayerActivity.EXTRA_CHANNEL_NAME,
                src.getStringExtra(EXTRA_CHANNEL_NAME))
            target.putExtra(PlayerActivity.EXTRA_CHANNEL_URL,
                src.getStringExtra(EXTRA_CHANNEL_URL))
            target.putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX,
                src.getIntExtra(EXTRA_CHANNEL_INDEX, 0))
            target.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(target)
        } catch (_: Throwable) {}
        finish()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        logStep("surfaceCreated mpv=$mpvInitialized")
        if (!mpvInitialized) return
        try {
            MPVLib.attachSurface(holder.surface)
            // Включаем видеовыход теперь, когда у нас есть валидный Surface.
            // Это тот самый момент когда MPV может инициализировать
            // gpu-renderer и начать показывать видео (звук уже работал
            // через ao=audiotrack независимо от vo).
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setOptionString("vo", "gpu")
            logStep("vo=gpu attached")
            currentUrl?.let { url ->
                logStep("loadfile $url")
                MPVLib.command(arrayOf("loadfile", url))
            }
        } catch (e: Throwable) {
            logError("surfaceCreated", e)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        logStep("surfaceChanged ${width}x${height}")
        try {
            MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
        } catch (_: Throwable) {}
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!mpvInitialized) return
        try {
            // Безопасное отсоединение по канону mpv-android: сначала
            // глушим vo, потом detach. Иначе MPV может попытаться отрисовать
            // в уже разрушенный Surface и упасть.
            MPVLib.setOptionString("vo", "null")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.detachSurface()
        } catch (_: Throwable) {}
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
