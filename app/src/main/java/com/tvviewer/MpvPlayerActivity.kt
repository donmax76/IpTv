package com.tvviewer

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.jdtech.mpv.MPVLib

/**
 * Альтернативный плеер на libmpv (тот же движок что в Vimu Player).
 * Round B: полный UI к минимальному скелету Round A — переключение
 * каналов CH+/CH- (кнопки + dpad up/down + media keys), баннер
 * номер/название при переключении, верхний overlay с EPG (now/next),
 * pause/play, перехват MPV-лога и автореконнект при END_FILE с ошибкой.
 */
class MpvPlayerActivity : BaseActivity(),
    SurfaceHolder.Callback,
    MPVLib.EventObserver,
    MPVLib.LogObserver {

    companion object {
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_URL = "channel_url"
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        private const val TAG = "MpvPlayerActivity"
        private const val OVERLAY_HIDE_MS = 5000L
        private const val BANNER_HIDE_MS = 2500L
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private lateinit var prefs: AppPreferences
    private lateinit var surfaceView: SurfaceView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var topOverlay: LinearLayout
    private lateinit var nameLabel: TextView
    private lateinit var epgLabel: TextView
    private lateinit var channelNumber: TextView
    private lateinit var bottomOverlay: LinearLayout
    private lateinit var btnPrev: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: TextView
    private lateinit var bannerView: TextView

    private var channels: List<Channel> = emptyList()
    private var currentIndex: Int = 0
    private var mpvInitialized = false
    private var observersRegistered = false
    private var isPaused = false
    private var reconnectAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlay() }
    private val hideBannerRunnable = Runnable { bannerView.visibility = View.GONE }
    private val reconnectRunnable = Runnable { reloadCurrent() }

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
            prefs = AppPreferences(this)

            channels = ChannelDataHolder.allChannels
            currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0)
                .coerceIn(0, (channels.size - 1).coerceAtLeast(0))
            // Если список пустой (запуск напрямую с одним url) —
            // соберём виртуальный список из одного канала чтобы UI работал.
            if (channels.isEmpty()) {
                val name = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
                val url = intent.getStringExtra(EXTRA_CHANNEL_URL) ?: ""
                if (url.isBlank()) {
                    logStep("No URL given - finishing")
                    finish(); return
                }
                channels = listOf(Channel(name = name, url = url))
                currentIndex = 0
            }

            buildUi()
            initMpv()
            // На старте показываем overlay чтобы юзер увидел название и
            // EPG, через 5с он скрывается автоматически.
            updateChannelUi()
            showOverlay()
        } catch (e: Throwable) {
            logError("onCreate", e)
            android.widget.Toast.makeText(this,
                "Ошибка MPV: ${e.javaClass.simpleName}: ${e.message?.take(80)}",
                android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@MpvPlayerActivity)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))

        loadingIndicator = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(loadingIndicator, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))

        // Top overlay: channel number + name + EPG (now/next).
        topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        channelNumber = TextView(this).apply {
            setTextColor(0xFFFFC107.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, dp(12), 0)
        }
        nameLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        topRow.addView(channelNumber)
        topRow.addView(nameLabel)
        epgLabel = TextView(this).apply {
            setTextColor(0xFFCCCCCC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, 0)
        }
        topOverlay.addView(topRow)
        topOverlay.addView(epgLabel)
        root.addView(topOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP))

        // Bottom overlay: prev / play-pause / next.
        bottomOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        btnPrev = makeTextButton("CH-")
        btnPrev.setOnClickListener { switchChannel(-1) }
        btnPlayPause = ImageButton(this).apply {
            setImageResource(R.drawable.ic_pause)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(dp(24), dp(8), dp(24), dp(8))
            setOnClickListener { togglePause() }
        }
        btnNext = makeTextButton("CH+")
        btnNext.setOnClickListener { switchChannel(1) }
        bottomOverlay.addView(btnPrev)
        bottomOverlay.addView(btnPlayPause)
        bottomOverlay.addView(btnNext)
        root.addView(bottomOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        // Center banner for channel switch (number + name, brief).
        bannerView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(24), dp(16), dp(24), dp(16))
            visibility = View.GONE
            gravity = Gravity.CENTER
        }
        root.addView(bannerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(80)
            })

        setContentView(root)
    }

    private fun makeTextButton(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        setBackgroundColor(0x40FFFFFF)
        gravity = Gravity.CENTER
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun initMpv() {
        try {
            logStep("initMpv: preload FFmpeg deps + mpv")
            for (lib in listOf(
                "c++_shared", "avutil", "swresample", "swscale",
                "avcodec", "avformat", "avfilter", "avdevice", "postproc",
                "mpv", "player",
            )) {
                try {
                    System.loadLibrary(lib)
                    logStep("loaded $lib")
                } catch (e: UnsatisfiedLinkError) {
                    if (lib == "mpv") {
                        logError("loadLibrary $lib", e); throw e
                    } else {
                        logStep("skip $lib: ${e.message?.take(80)}")
                    }
                }
            }
            val configDir = filesDir.absolutePath
            logStep("MPVLib.create(this), configDir=$configDir")
            MPVLib.create(this)
            MPVLib.setOptionString("msg-level", "all=v")
            MPVLib.setOptionString("config", "yes")
            MPVLib.setOptionString("config-dir", configDir)
            MPVLib.setOptionString("hwdec", "auto-safe")
            // vo=null до прикрепления Surface (см. Round 172).
            MPVLib.setOptionString("vo", "null")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("ao", "audiotrack,opensles")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "yes")
            MPVLib.setOptionString("cache-secs", "10")
            logStep("MPVLib.init()")
            MPVLib.init()
            mpvInitialized = true

            // Логи MPV (warn+) теперь идут к нам в ErrorLogger — увидим
            // причину "no video" / "decoder failed" / "stream error".
            MPVLib.addLogObserver(this)
            MPVLib.addObserver(this)
            // Подписка на свойства: pause + буферизация. Нужно до loadfile.
            MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("core-idle", MPVLib.MPV_FORMAT_FLAG)
            observersRegistered = true
            logStep("MPV initialized OK")
        } catch (e: Throwable) {
            logError("initMpv", e)
            android.widget.Toast.makeText(this,
                "MPV не работает, использую встроенный плеер",
                android.widget.Toast.LENGTH_LONG).show()
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

    // ---------- Channel switching / playback ----------

    private fun switchChannel(direction: Int) {
        val n = channels.size
        if (n <= 1) return
        val target = ((currentIndex + direction) % n + n) % n
        loadChannel(target)
    }

    private fun loadChannel(index: Int) {
        if (index !in channels.indices) return
        currentIndex = index
        val channel = channels[index]
        ChannelDataHolder.currentChannelIndex = index
        try { prefs.pushRecentChannel(channel) } catch (_: Throwable) {}
        reconnectAttempts = 0
        mainHandler.removeCallbacks(reconnectRunnable)
        updateChannelUi()
        showBanner("${index + 1}/${channels.size}\n${channel.name}")
        showOverlay()
        if (mpvInitialized) {
            try {
                loadingIndicator.visibility = View.VISIBLE
                MPVLib.command(arrayOf("loadfile", channel.url))
                isPaused = false
                btnPlayPause.setImageResource(R.drawable.ic_pause)
            } catch (e: Throwable) { logError("loadChannel", e) }
        }
    }

    private fun reloadCurrent() {
        val ch = channels.getOrNull(currentIndex) ?: return
        if (!mpvInitialized) return
        try {
            logStep("reconnect attempt=$reconnectAttempts url=${ch.url.take(60)}")
            loadingIndicator.visibility = View.VISIBLE
            MPVLib.command(arrayOf("loadfile", ch.url))
        } catch (e: Throwable) { logError("reloadCurrent", e) }
    }

    private fun togglePause() {
        if (!mpvInitialized) return
        try {
            isPaused = !isPaused
            MPVLib.setPropertyBoolean("pause", isPaused)
            btnPlayPause.setImageResource(
                if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
            showOverlay()
        } catch (e: Throwable) { logError("togglePause", e) }
    }

    private fun updateChannelUi() {
        val channel = channels.getOrNull(currentIndex) ?: return
        nameLabel.text = channel.name
        channelNumber.text = "${currentIndex + 1}/${channels.size}"
        try {
            val (now, next) = EpgRepository.getNowNext(
                ChannelDataHolder.epgData, channel.tvgId, channel.name)
            val sb = StringBuilder()
            if (!now.isNullOrBlank()) sb.append("Сейчас: ").append(now)
            if (!next.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("Далее: ").append(next)
            }
            epgLabel.text = sb
            epgLabel.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
        } catch (_: Throwable) {
            epgLabel.visibility = View.GONE
        }
    }

    // ---------- Overlay / banner visibility ----------

    private fun showOverlay() {
        topOverlay.visibility = View.VISIBLE
        bottomOverlay.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideOverlayRunnable)
        mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_MS)
    }

    private fun hideOverlay() {
        topOverlay.visibility = View.GONE
        bottomOverlay.visibility = View.GONE
    }

    private fun showBanner(text: String) {
        bannerView.text = text
        bannerView.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideBannerRunnable)
        mainHandler.postDelayed(hideBannerRunnable, BANNER_HIDE_MS)
    }

    // ---------- Key handling (TV remote / dpad) ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                switchChannel(-1); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                switchChannel(1); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                togglePause(); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (topOverlay.visibility != View.VISIBLE) showOverlay()
                else togglePause()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (topOverlay.visibility == View.VISIBLE) {
                    hideOverlay(); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- MPV event/log callbacks (any thread → main) ----------

    override fun event(eventId: Int) {
        mainHandler.post {
            when (eventId) {
                MPVLib.MPV_EVENT_FILE_LOADED,
                MPVLib.MPV_EVENT_PLAYBACK_RESTART -> {
                    loadingIndicator.visibility = View.GONE
                    reconnectAttempts = 0
                }
                MPVLib.MPV_EVENT_END_FILE -> {
                    // Live-стрим закончился преждевременно — пытаемся
                    // переподключиться (как в PlayerActivity).
                    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        reconnectAttempts++
                        logStep("END_FILE → reconnect in ${RECONNECT_DELAY_MS}ms (attempt $reconnectAttempts)")
                        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
                    } else {
                        loadingIndicator.visibility = View.GONE
                        android.widget.Toast.makeText(this@MpvPlayerActivity,
                            "Канал не отвечает", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    override fun eventProperty(name: String) {}
    override fun eventProperty(name: String, value: Long) {}
    override fun eventProperty(name: String, value: Double) {}
    override fun eventProperty(name: String, value: Boolean) {
        mainHandler.post {
            when (name) {
                "paused-for-cache", "core-idle" -> {
                    loadingIndicator.visibility = if (value) View.VISIBLE else View.GONE
                }
                "pause" -> {
                    isPaused = value
                    btnPlayPause.setImageResource(
                        if (value) R.drawable.ic_play else R.drawable.ic_pause)
                }
            }
        }
    }
    override fun eventProperty(name: String, value: String) {}

    override fun logMessage(prefix: String, level: Int, text: String) {
        // Только warn/error/fatal в наш ErrorLogger чтобы не залить лог.
        if (level <= MPVLib.MPV_LOG_LEVEL_WARN) {
            try { ErrorLogger.info(this, "MPV", "[$prefix] ${text.trim()}") } catch (_: Throwable) {}
        } else {
            Log.d(TAG, "[$prefix] ${text.trim()}")
        }
    }

    // ---------- Surface lifecycle ----------

    override fun surfaceCreated(holder: SurfaceHolder) {
        logStep("surfaceCreated mpv=$mpvInitialized")
        if (!mpvInitialized) return
        try {
            MPVLib.attachSurface(holder.surface)
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setOptionString("vo", "gpu")
            logStep("vo=gpu attached")
            // Стартуем воспроизведение текущего канала (по индексу,
            // переданному в Intent или дефолт 0).
            val ch = channels.getOrNull(currentIndex)
            if (ch != null) {
                logStep("loadfile ${ch.url.take(80)}")
                loadingIndicator.visibility = View.VISIBLE
                MPVLib.command(arrayOf("loadfile", ch.url))
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
            MPVLib.setOptionString("vo", "null")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.detachSurface()
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        if (mpvInitialized) {
            if (observersRegistered) {
                try { MPVLib.removeObserver(this) } catch (_: Throwable) {}
                try { MPVLib.removeLogObserver(this) } catch (_: Throwable) {}
            }
            try { MPVLib.command(arrayOf("stop")) } catch (_: Throwable) {}
            try { MPVLib.destroy() } catch (_: Throwable) {}
            mpvInitialized = false
        }
    }
}
