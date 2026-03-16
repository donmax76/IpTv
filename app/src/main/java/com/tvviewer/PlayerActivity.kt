package com.tvviewer

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActivity : BaseActivity() {

    companion object {
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_URL = "channel_url"
        const val EXTRA_CHANNEL_INDEX = "channel_index"
    }

    private lateinit var playerView: PlayerView
    private lateinit var controlsOverlay: RelativeLayout
    private lateinit var channelName: TextView
    private lateinit var epgNow: TextView
    private lateinit var channelNumber: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var clockDisplay: TextView
    private lateinit var channelListOverlay: FrameLayout
    private lateinit var overlayChannelsList: RecyclerView
    private lateinit var numberInputDisplay: TextView
    private lateinit var sleepTimerIndicator: TextView
    private lateinit var prefs: AppPreferences

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var currentIndex: Int = 0
    private var controlsVisible = true
    private var channelListVisible = false
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30000)
        }
    }
    private var aspectRatioMode = 0

    // Number input for remote
    private var numberInput = ""
    private val numberHandler = Handler(Looper.getMainLooper())
    private val numberRunnable = Runnable { applyNumberInput() }

    // Sleep timer
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepTimerEnd: Long = 0
    private val sleepTimerRunnable = object : Runnable {
        override fun run() {
            val remaining = sleepTimerEnd - System.currentTimeMillis()
            if (remaining <= 0) {
                player?.pause()
                sleepTimerIndicator.visibility = View.GONE
                Toast.makeText(this@PlayerActivity, R.string.sleep_timer_off, Toast.LENGTH_LONG).show()
                return
            }
            val mins = (remaining / 60000).toInt()
            sleepTimerIndicator.text = "${getString(R.string.sleep_timer)}: ${mins + 1} мин"
            sleepTimerIndicator.visibility = View.VISIBLE
            sleepHandler.postDelayed(this, 60000)
        }
    }

    private var overlayAdapter: OverlayChannelAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        prefs = AppPreferences(this)
        initViews()
        hideSystemUI()

        val name = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        currentUrl = intent.getStringExtra(EXTRA_CHANNEL_URL) ?: ""
        currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0)

        channelName.text = name
        channelNumber.text = "${currentIndex + 1} / ${ChannelDataHolder.allChannels.size}"

        updateEpg()
        initPlayer()
        playStream(currentUrl!!)
        scheduleHideControls()
        startClock()

        // Save last channel
        prefs.lastChannelUrl = currentUrl

        // Setup sleep timer if configured
        val timerMins = prefs.sleepTimerMinutes
        if (timerMins > 0) {
            startSleepTimer(timerMins)
        }
    }

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        controlsOverlay = findViewById(R.id.controlsOverlay)
        channelName = findViewById(R.id.channelName)
        epgNow = findViewById(R.id.epgNow)
        channelNumber = findViewById(R.id.channelNumber)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorLayout = findViewById(R.id.errorLayout)
        errorText = findViewById(R.id.errorText)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        clockDisplay = findViewById(R.id.clockDisplay)
        channelListOverlay = findViewById(R.id.channelListOverlay)
        overlayChannelsList = findViewById(R.id.overlayChannelsList)
        numberInputDisplay = findViewById(R.id.numberInputDisplay)
        sleepTimerIndicator = findViewById(R.id.sleepTimerIndicator)

        // Show clock based on settings
        if (prefs.timeDisplayPosition != "off") {
            clockDisplay.visibility = View.VISIBLE
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        btnPlayPause.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                    btnPlayPause.setImageResource(R.drawable.ic_play)
                } else {
                    p.play()
                    btnPlayPause.setImageResource(R.drawable.ic_pause)
                }
            }
            scheduleHideControls()
        }

        findViewById<ImageButton>(R.id.btnAspectRatio).setOnClickListener {
            cycleAspectRatio()
            scheduleHideControls()
        }

        findViewById<ImageButton>(R.id.btnChannelList).setOnClickListener {
            toggleChannelList()
        }

        findViewById<ImageButton>(R.id.btnPrevChannel).setOnClickListener { switchChannel(-1) }
        findViewById<ImageButton>(R.id.btnNextChannel).setOnClickListener { switchChannel(1) }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRetry).setOnClickListener {
            errorLayout.visibility = View.GONE
            currentUrl?.let { playStream(it) }
        }

        controlsOverlay.setOnClickListener { toggleControls() }

        // Channel list overlay
        overlayChannelsList.layoutManager = LinearLayoutManager(this)
        findViewById<View>(R.id.channelListDimBg).setOnClickListener { hideChannelList() }

        setupOverlayChannelList()
    }

    private fun setupOverlayChannelList() {
        val channels = ChannelDataHolder.allChannels
        if (channels.isEmpty()) return

        overlayAdapter = OverlayChannelAdapter(channels, ChannelDataHolder.epgData, currentIndex) { index ->
            switchToChannel(index)
            hideChannelList()
        }
        overlayChannelsList.adapter = overlayAdapter
    }

    private fun initPlayer() {
        val loadControl = when (prefs.bufferMode) {
            "low" -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, 15000, 1000, 2000)
                .build()
            "high" -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(30000, 60000, 3000, 5000)
                .build()
            else -> DefaultLoadControl()
        }

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().also { p ->
                playerView.player = p
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                loadingIndicator.visibility = View.VISIBLE
                                errorLayout.visibility = View.GONE
                            }
                            Player.STATE_READY -> {
                                loadingIndicator.visibility = View.GONE
                                errorLayout.visibility = View.GONE
                                btnPlayPause.setImageResource(R.drawable.ic_pause)
                            }
                            Player.STATE_ENDED, Player.STATE_IDLE -> {
                                loadingIndicator.visibility = View.GONE
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        loadingIndicator.visibility = View.GONE
                        errorLayout.visibility = View.VISIBLE
                        errorText.text = getString(R.string.error_playback)
                        ErrorLogger.logException(this@PlayerActivity, error)
                    }
                })
            }
    }

    private fun playStream(url: String) {
        loadingIndicator.visibility = View.VISIBLE
        errorLayout.visibility = View.GONE
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    private fun switchChannel(direction: Int) {
        val channels = ChannelDataHolder.allChannels
        if (channels.isEmpty()) return

        currentIndex = (currentIndex + direction + channels.size) % channels.size
        switchToChannel(currentIndex)
    }

    private fun switchToChannel(index: Int) {
        val channels = ChannelDataHolder.allChannels
        if (index !in channels.indices) return

        currentIndex = index
        val channel = channels[currentIndex]

        currentUrl = channel.url
        channelName.text = channel.name
        channelNumber.text = "${currentIndex + 1} / ${channels.size}"
        ChannelDataHolder.currentChannelIndex = currentIndex

        prefs.lastChannelUrl = currentUrl

        overlayAdapter?.updateCurrentIndex(currentIndex)

        updateEpg()
        playStream(channel.url)
        scheduleHideControls()
    }

    private fun updateEpg() {
        val channels = ChannelDataHolder.allChannels
        if (currentIndex in channels.indices) {
            val channel = channels[currentIndex]
            val (now, _) = EpgRepository.getNowNext(ChannelDataHolder.epgData, channel.tvgId)
            if (now != null) {
                epgNow.text = now
                epgNow.visibility = View.VISIBLE
            } else {
                epgNow.visibility = View.GONE
            }
        }
    }

    private fun updateClock() {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        clockDisplay.text = time
    }

    private fun startClock() {
        updateClock()
        clockHandler.postDelayed(clockRunnable, 30000)
    }

    private fun cycleAspectRatio() {
        aspectRatioMode = (aspectRatioMode + 1) % 4
        when (aspectRatioMode) {
            0 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            1 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            2 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            3 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        val names = arrayOf(
            getString(R.string.aspect_fit),
            getString(R.string.aspect_16_9),
            getString(R.string.aspect_4_3),
            getString(R.string.aspect_fill)
        )
        Toast.makeText(this, names[aspectRatioMode], Toast.LENGTH_SHORT).show()
    }

    // === Channel list overlay ===

    private fun toggleChannelList() {
        if (channelListVisible) hideChannelList() else showChannelList()
    }

    private fun showChannelList() {
        channelListOverlay.visibility = View.VISIBLE
        channelListVisible = true
        hideHandler.removeCallbacks(hideRunnable)

        // Scroll to current channel
        overlayChannelsList.scrollToPosition(currentIndex.coerceAtLeast(0))
    }

    private fun hideChannelList() {
        channelListOverlay.visibility = View.GONE
        channelListVisible = false
        scheduleHideControls()
    }

    // === Controls visibility ===

    private fun toggleControls() {
        if (channelListVisible) {
            hideChannelList()
            return
        }
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsOverlay.visibility = View.VISIBLE
        controlsVisible = true
        scheduleHideControls()
    }

    private fun hideControls() {
        controlsOverlay.visibility = View.GONE
        controlsVisible = false
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        val seconds = prefs.channelListAutoHideSeconds
        hideHandler.postDelayed(hideRunnable, seconds * 1000L)
    }

    // === Sleep timer ===

    private fun startSleepTimer(minutes: Int) {
        sleepHandler.removeCallbacks(sleepTimerRunnable)
        if (minutes <= 0) {
            sleepTimerEnd = 0
            sleepTimerIndicator.visibility = View.GONE
            return
        }
        sleepTimerEnd = System.currentTimeMillis() + minutes * 60000L
        sleepTimerRunnable.run()
    }

    // === D-pad / Remote control ===

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            // D-pad center / Enter - toggle controls or select
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (channelListVisible) return super.onKeyDown(keyCode, event)
                toggleControls()
                return true
            }
            // D-pad Up - previous channel
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (channelListVisible) return super.onKeyDown(keyCode, event)
                switchChannel(-1)
                showControls()
                return true
            }
            // D-pad Down - next channel
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (channelListVisible) return super.onKeyDown(keyCode, event)
                switchChannel(1)
                showControls()
                return true
            }
            // D-pad Left - rewind / show channel list
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (channelListVisible) return super.onKeyDown(keyCode, event)
                toggleChannelList()
                return true
            }
            // D-pad Right - close channel list
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (channelListVisible) {
                    hideChannelList()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            // Play/Pause
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.let { p ->
                    if (p.isPlaying) {
                        p.pause()
                        btnPlayPause.setImageResource(R.drawable.ic_play)
                    } else {
                        p.play()
                        btnPlayPause.setImageResource(R.drawable.ic_pause)
                    }
                }
                showControls()
                return true
            }
            // Back
            KeyEvent.KEYCODE_BACK -> {
                if (channelListVisible) {
                    hideChannelList()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            // Menu key - show channel list
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_GUIDE -> {
                toggleChannelList()
                return true
            }
            // Volume keys - pass to system
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                return super.onKeyDown(keyCode, event)
            }
            // Info key
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_TV_DATA_SERVICE -> {
                showControls()
                return true
            }
        }

        // Number keys for direct channel input (0-9)
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            val digit = keyCode - KeyEvent.KEYCODE_0
            handleNumberInput(digit)
            return true
        }
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
            val digit = keyCode - KeyEvent.KEYCODE_NUMPAD_0
            handleNumberInput(digit)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun handleNumberInput(digit: Int) {
        numberInput += digit.toString()
        numberInputDisplay.text = numberInput
        numberInputDisplay.visibility = View.VISIBLE

        numberHandler.removeCallbacks(numberRunnable)
        numberHandler.postDelayed(numberRunnable, 1500)
    }

    private fun applyNumberInput() {
        val num = numberInput.toIntOrNull()
        numberInput = ""
        numberInputDisplay.visibility = View.GONE

        if (num != null && num > 0 && num <= ChannelDataHolder.allChannels.size) {
            switchToChannel(num - 1)
            showControls()
        }
    }

    // === System UI ===

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
        hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        numberHandler.removeCallbacks(numberRunnable)
        sleepHandler.removeCallbacks(sleepTimerRunnable)
        player?.release()
        player = null
    }
}
