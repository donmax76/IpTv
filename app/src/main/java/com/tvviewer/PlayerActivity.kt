package com.tvviewer

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import coil.load
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class PlayerActivity : BaseActivity() {

    companion object {
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_URL = "channel_url"
        const val EXTRA_CHANNEL_INDEX = "channel_index"
    }

    private lateinit var playerView: PlayerView
    private lateinit var controlsOverlay: RelativeLayout
    private lateinit var topBar: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var channelName: TextView
    private lateinit var epgNow: TextView
    private lateinit var channelNumber: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var clockDisplay: TextView
    private lateinit var persistentClock: TextView
    private lateinit var channelListOverlay: FrameLayout
    private lateinit var playerDrawerOverlay: FrameLayout
    private lateinit var playerRightMenuOverlay: FrameLayout
    private var streamDataFactory: androidx.media3.datasource.DataSource.Factory? = null
    private lateinit var overlayChannelsList: RecyclerView
    private lateinit var numberInputDisplay: TextView
    private lateinit var sleepTimerIndicator: TextView
    private lateinit var prefs: AppPreferences

    // Gesture overlay indicators
    private lateinit var gestureIndicator: LinearLayout
    private lateinit var gestureIcon: ImageView
    private lateinit var gestureText: TextView
    private lateinit var gestureProgress: ProgressBar

    // Screen lock
    private lateinit var lockOverlay: FrameLayout
    private lateinit var btnLock: ImageButton
    private var isScreenLocked = false

    // Audio/Subtitle info
    private lateinit var btnAudioTrack: ImageButton
    private lateinit var btnSpeed: ImageButton
    private lateinit var audioTrackInfo: TextView

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var currentIndex: Int = 0
    private var controlsVisible = true
    private var channelListVisible = false
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private val channelListHideHandler = Handler(Looper.getMainLooper())
    private val channelListHideRunnable = Runnable { hideChannelList() }
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

    // Channel info banner
    private lateinit var channelInfoBanner: LinearLayout
    private lateinit var bannerChannelNumber: TextView
    private lateinit var bannerChannelName: TextView
    private lateinit var bannerChannelLogo: ImageView
    private lateinit var bannerEpgNow: TextView
    private lateinit var bannerEpgNext: TextView
    private lateinit var bannerClock: TextView
    private lateinit var bannerEpgProgress: ProgressBar
    private val bannerHandler = Handler(Looper.getMainLooper())
    private val bannerHideRunnable = Runnable { channelInfoBanner.visibility = View.GONE }

    // Auto-reconnect on playback failure / unexpected stream end
    private var reconnectAttempts = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        val url = currentUrl
        if (url != null) {
            errorText.text = getString(R.string.reconnecting)
            playStream(url)
        }
    }
    private val MAX_RECONNECT = 8

    // Когда true — следующий onPause не ставит плеер на паузу.
    // Используется при открытии Настроек из выдвижного меню плеера: плеер
    // продолжает играть, пока пользователь меняет настройки.
    private var keepPlayingInBackground = false

    private var overlayAdapter: OverlayChannelAdapter? = null
    private var overlaySearchEdit: EditText? = null
    private var overlayChannelCount: TextView? = null
    private var overlayFilteredChannels: List<Channel> = emptyList()
    private var overlayFilteredIndices: List<Int> = emptyList()

    // Gesture control
    private lateinit var audioManager: AudioManager
    private var gestureDetector: GestureDetector? = null
    private var isSwipingVolume = false
    private var isSwipingBrightness = false
    private var swipeStartVolume = 0
    private var swipeStartBrightness = 0f
    private var swipeStartY = 0f

    // Playback speed
    private var currentSpeedIndex = 2 // index into speedValues
    private val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        prefs = AppPreferences(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initViews()
        hideSystemUI()
        setupGestures()

        val name = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        currentUrl = intent.getStringExtra(EXTRA_CHANNEL_URL) ?: ""
        currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0)

        channelName.text = name
        channelNumber.text = "${currentIndex + 1} / ${ChannelDataHolder.allChannels.size}"

        updateEpg()
        initPlayer()
        playStream(currentUrl!!)
        showChannelBanner()
        scheduleHideControls()
        startClock()

        // Save last channel + push to recent history
        prefs.lastChannelUrl = currentUrl
        currentUrl?.let { prefs.pushRecent(it) }

        // Setup sleep timer if configured
        val timerMins = prefs.sleepTimerMinutes
        if (timerMins > 0) {
            startSleepTimer(timerMins)
        }
    }

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        controlsOverlay = findViewById(R.id.controlsOverlay)
        topBar = findViewById(R.id.topBar)
        btnBack = findViewById(R.id.btnBack)
        channelName = findViewById(R.id.channelName)
        epgNow = findViewById(R.id.epgNow)
        channelNumber = findViewById(R.id.channelNumber)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorLayout = findViewById(R.id.errorLayout)
        errorText = findViewById(R.id.errorText)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        clockDisplay = findViewById(R.id.clockDisplay)
        persistentClock = findViewById(R.id.persistentClock)
        channelListOverlay = findViewById(R.id.channelListOverlay)

        // In-player drawer (shown over the channel list on 2nd LEFT)
        playerDrawerOverlay = findViewById(R.id.playerDrawerOverlay)
        findViewById<View>(R.id.playerDrawerDimBg).setOnClickListener { hidePlayerDrawer() }
        val gotoMain: (Int) -> Unit = { tabIdx ->
            ChannelDataHolder.openDrawerOnReturn = false
            ChannelDataHolder.returnToTabIndex = tabIdx
            finish()
        }
        findViewById<View>(R.id.playerDrawerPlaylists).setOnClickListener { gotoMain(0) }
        findViewById<View>(R.id.playerDrawerChannels).setOnClickListener { gotoMain(1) }
        findViewById<View>(R.id.playerDrawerTvGuide).setOnClickListener { gotoMain(2) }
        findViewById<View>(R.id.playerDrawerFavorites).setOnClickListener { gotoMain(3) }
        findViewById<View>(R.id.playerDrawerRecent).setOnClickListener { gotoMain(4) }
        // Настройки открываем поверх плеера, не завершая активити: трансляция
        // продолжается (звук+картинка), пользователь меняет параметры,
        // возвращается обратно — плеер идёт без перезапуска.
        findViewById<View>(R.id.playerDrawerSettings).setOnClickListener {
            keepPlayingInBackground = true
            hidePlayerDrawer()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Правое выпадающее меню плеера (DPAD_RIGHT). Все эти действия
        // раньше торчали кнопками в верхнем правом углу — теперь они
        // спрятаны и доступны только из этого меню.
        playerRightMenuOverlay = findViewById(R.id.playerRightMenuOverlay)
        findViewById<View>(R.id.playerRightMenuDimBg).setOnClickListener { hidePlayerRightMenu() }
        findViewById<View>(R.id.rightMenuChannelList).setOnClickListener {
            hidePlayerRightMenu()
            toggleChannelList()
        }
        findViewById<View>(R.id.rightMenuAudio).setOnClickListener {
            hidePlayerRightMenu()
            showAudioTrackDialog()
        }
        findViewById<View>(R.id.rightMenuSpeed).setOnClickListener {
            hidePlayerRightMenu()
            cycleSpeed()
        }
        findViewById<View>(R.id.rightMenuAspect).setOnClickListener {
            hidePlayerRightMenu()
            cycleAspectRatio()
        }
        findViewById<View>(R.id.rightMenuPip).setOnClickListener {
            hidePlayerRightMenu()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) enterPipMode()
        }
        findViewById<View>(R.id.rightMenuLock).setOnClickListener {
            hidePlayerRightMenu()
            toggleScreenLock()
        }
        overlayChannelsList = findViewById(R.id.overlayChannelsList)
        numberInputDisplay = findViewById(R.id.numberInputDisplay)
        sleepTimerIndicator = findViewById(R.id.sleepTimerIndicator)

        // Channel info banner
        channelInfoBanner = findViewById(R.id.channelInfoBanner)
        bannerChannelNumber = findViewById(R.id.bannerChannelNumber)
        bannerChannelName = findViewById(R.id.bannerChannelName)
        bannerChannelLogo = findViewById(R.id.bannerChannelLogo)
        bannerEpgNow = findViewById(R.id.bannerEpgNow)
        bannerEpgNext = findViewById(R.id.bannerEpgNext)
        bannerClock = findViewById(R.id.bannerClock)
        bannerEpgProgress = findViewById(R.id.bannerEpgProgress)

        // Gesture indicator
        gestureIndicator = findViewById(R.id.gestureIndicator)
        gestureIcon = findViewById(R.id.gestureIcon)
        gestureText = findViewById(R.id.gestureText)
        gestureProgress = findViewById(R.id.gestureProgress)

        // Screen lock
        lockOverlay = findViewById(R.id.lockOverlay)
        btnLock = findViewById(R.id.btnLock)

        // Audio track and speed buttons
        btnAudioTrack = findViewById(R.id.btnAudioTrack)
        btnSpeed = findViewById(R.id.btnSpeed)
        audioTrackInfo = findViewById(R.id.audioTrackInfo)

        // Show clock based on settings — both the in-bar clock (which is
        // hidden when controls auto-hide) and the persistent clock that
        // stays on top of the video.
        if (prefs.timeDisplayPosition != "off") {
            clockDisplay.visibility = View.VISIBLE
            persistentClock.visibility = View.VISIBLE
        }

        // Стрелка "Назад" в плеере: возвращаемся не в пустой экран Каналов,
        // а в текущий плейлист с подсвеченным текущим каналом. Сам индекс
        // сохраняется в ChannelDataHolder.currentChannelIndex при каждом
        // переключении канала, ChannelsFragment подхватит его.
        btnBack.setOnClickListener {
            ChannelDataHolder.openDrawerOnReturn = false
            ChannelDataHolder.returnToTabIndex = 1 // index of Channels
            finish()
        }

        // Reset the auto-hide timer whenever the user moves focus between
        // any top-bar / control buttons or interacts with the layout.
        // Without this, after 5 s of "navigating" the bar disappears even
        // though the user is actively trying to use it.
        val keepAliveListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) scheduleHideControls()
        }
        for (i in 0 until topBar.childCount) {
            topBar.getChildAt(i).onFocusChangeListener = keepAliveListener
        }
        // Same for the centre play / nav row, which has buttons too.
        controlsOverlay.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && controlsVisible &&
                (isFocusInTopBar() || isInsideControlsOverlay(newFocus))) {
                scheduleHideControls()
            }
        }

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

        findViewById<ImageButton>(R.id.btnPip).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setOnClickListener { enterPipMode() }
            } else {
                visibility = View.GONE
            }
        }

        findViewById<ImageButton>(R.id.btnAspectRatio).setOnClickListener {
            cycleAspectRatio()
            scheduleHideControls()
        }

        findViewById<ImageButton>(R.id.btnChannelList).setOnClickListener {
            toggleChannelList()
        }

        // Lock button
        btnLock.setOnClickListener {
            toggleScreenLock()
        }

        // Audio track selection
        btnAudioTrack.setOnClickListener {
            showAudioTrackDialog()
            scheduleHideControls()
        }

        // Speed button
        btnSpeed.setOnClickListener {
            cycleSpeed()
            scheduleHideControls()
        }

        // Lock overlay - tap to unlock
        lockOverlay.setOnClickListener {
            showUnlockHint()
        }

        findViewById<ImageButton>(R.id.btnUnlock)?.setOnClickListener {
            toggleScreenLock()
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

        overlaySearchEdit = findViewById(R.id.overlaySearchEdit)
        overlayChannelCount = findViewById(R.id.overlayChannelCount)

        overlaySearchEdit?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterOverlayChannels()
                bumpChannelListIdleTimer()
            }
        })

        // Reset auto-hide timer on any scroll/touch in the channel list
        overlayChannelsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0 || dy != 0) bumpChannelListIdleTimer()
            }
        })

        // Category chips in overlay
        val overlayCategoriesList = findViewById<RecyclerView>(R.id.overlayCategoriesList)
        overlayCategoriesList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val channels = ChannelDataHolder.allChannels
        val cats = listOf(getString(R.string.all)) + channels.mapNotNull { it.group }.distinct().sorted()
        val catAdapter = CategoryAdapter(cats) { category ->
            overlaySelectedCategory = category
            filterOverlayChannels()
            bumpChannelListIdleTimer()
        }
        overlayCategoriesList.adapter = catAdapter

        // Любая прокрутка ленты категорий или смена фокуса внутри неё
        // продлевает таймер автоскрытия — иначе пользователь не успевает
        // выбрать категорию, пока перемещается по чипам.
        overlayCategoriesList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0 || dy != 0) bumpChannelListIdleTimer()
            }
        })
        overlayCategoriesList.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && channelListVisible) {
                var p: View? = newFocus
                while (p != null) {
                    if (p == channelListOverlay) { bumpChannelListIdleTimer(); break }
                    p = p.parent as? View
                }
            }
        }

        setupOverlayChannelList()
    }

    private var overlaySelectedCategory: String = ""

    private fun setupOverlayChannelList() {
        val channels = ChannelDataHolder.allChannels
        if (channels.isEmpty()) return

        overlaySelectedCategory = getString(R.string.all)
        overlayFilteredChannels = channels
        overlayFilteredIndices = channels.indices.toList()
        overlayChannelCount?.text = "${channels.size}"

        overlayAdapter = OverlayChannelAdapter(channels, ChannelDataHolder.epgData, currentIndex,
            favorites = prefs.favorites,
            onChannelClick = { index ->
                switchToChannel(index)
                hideChannelList()
            },
            onFavoriteClick = { channel ->
                toggleFavorite(channel)
            }
        )
        overlayChannelsList.adapter = overlayAdapter
    }

    private fun toggleFavorite(channel: Channel) {
        if (prefs.isFavorite(channel.url)) {
            prefs.removeFavorite(channel.url)
        } else {
            prefs.addFavorite(channel.url)
        }
        overlayAdapter?.updateFavorites(prefs.favorites)
    }

    private fun filterOverlayChannels() {
        val channels = ChannelDataHolder.allChannels
        if (channels.isEmpty()) return

        val query = overlaySearchEdit?.text?.toString()?.trim()?.lowercase() ?: ""
        val allLabel = getString(R.string.all)

        val filtered = channels.withIndex().filter { (_, ch) ->
            val matchesSearch = query.isEmpty() || ch.name.lowercase().contains(query)
            val matchesCat = overlaySelectedCategory.isEmpty() || overlaySelectedCategory == allLabel ||
                ch.group == overlaySelectedCategory
            matchesSearch && matchesCat
        }

        overlayFilteredChannels = filtered.map { it.value }
        overlayFilteredIndices = filtered.map { it.index }
        overlayChannelCount?.text = "${overlayFilteredChannels.size}"

        // Find current channel position in filtered list
        val filteredCurrentIndex = overlayFilteredIndices.indexOf(currentIndex)

        overlayAdapter = OverlayChannelAdapter(overlayFilteredChannels, ChannelDataHolder.epgData, filteredCurrentIndex,
            favorites = prefs.favorites,
            onChannelClick = { filteredIndex ->
                if (filteredIndex in overlayFilteredIndices.indices) {
                    val realIndex = overlayFilteredIndices[filteredIndex]
                    switchToChannel(realIndex)
                    hideChannelList()
                }
            },
            onFavoriteClick = { channel ->
                toggleFavorite(channel)
            }
        )
        overlayChannelsList.adapter = overlayAdapter
    }

    // === Gesture support (volume/brightness) ===

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isScreenLocked) {
                    showUnlockHint()
                    return true
                }
                if (channelListVisible) {
                    hideChannelList()
                    return true
                }
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isScreenLocked) return true
                // Double tap left/right to switch channels
                val screenWidth = playerView.width
                if (e.x < screenWidth / 3f) {
                    switchChannel(-1)
                } else if (e.x > screenWidth * 2f / 3f) {
                    switchChannel(1)
                } else {
                    // Double tap center to play/pause
                    player?.let { p ->
                        if (p.isPlaying) {
                            p.pause()
                            btnPlayPause.setImageResource(R.drawable.ic_play)
                        } else {
                            p.play()
                            btnPlayPause.setImageResource(R.drawable.ic_pause)
                        }
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isScreenLocked) return
                // Long press to show channel list
                if (!channelListVisible) {
                    showChannelList()
                }
            }
        })

        playerView.setOnTouchListener { _, event ->
            if (isScreenLocked) {
                gestureDetector?.onTouchEvent(event)
                return@setOnTouchListener true
            }

            gestureDetector?.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartY = event.y
                    isSwipingVolume = false
                    isSwipingBrightness = false

                    val screenWidth = playerView.width
                    if (event.x > screenWidth / 2f) {
                        // Right side - volume
                        swipeStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    } else {
                        // Left side - brightness
                        swipeStartBrightness = window.attributes.screenBrightness
                        if (swipeStartBrightness < 0) {
                            swipeStartBrightness = try {
                                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                            } catch (e: Exception) { 0.5f }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = swipeStartY - event.y
                    val screenWidth = playerView.width
                    val screenHeight = playerView.height

                    if (abs(dy) > 30 && !channelListVisible) {
                        if (event.x > screenWidth / 2f) {
                            // Volume control (right side)
                            isSwipingVolume = true
                            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val volumeChange = (dy / screenHeight * maxVolume * 1.5f).toInt()
                            val newVolume = (swipeStartVolume + volumeChange).coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                            showGestureIndicator(
                                R.drawable.ic_volume,
                                "${getString(R.string.volume)}: ${(newVolume * 100 / maxVolume)}%",
                                newVolume * 100 / maxVolume
                            )
                        } else {
                            // Brightness control (left side)
                            isSwipingBrightness = true
                            val brightnessChange = dy / screenHeight
                            val newBrightness = (swipeStartBrightness + brightnessChange).coerceIn(0.01f, 1f)
                            val layoutParams = window.attributes
                            layoutParams.screenBrightness = newBrightness
                            window.attributes = layoutParams
                            showGestureIndicator(
                                R.drawable.ic_brightness,
                                "${getString(R.string.brightness)}: ${(newBrightness * 100).toInt()}%",
                                (newBrightness * 100).toInt()
                            )
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSwipingVolume || isSwipingBrightness) {
                        hideGestureIndicator()
                    }
                    isSwipingVolume = false
                    isSwipingBrightness = false
                }
            }
            true
        }
    }

    private fun showGestureIndicator(iconRes: Int, text: String, progress: Int) {
        gestureIcon.setImageResource(iconRes)
        gestureText.text = text
        gestureProgress.progress = progress
        gestureIndicator.visibility = View.VISIBLE
    }

    private fun hideGestureIndicator() {
        Handler(Looper.getMainLooper()).postDelayed({
            gestureIndicator.visibility = View.GONE
        }, 500)
    }

    // === Screen lock ===

    private fun toggleScreenLock() {
        isScreenLocked = !isScreenLocked
        if (isScreenLocked) {
            hideControls()
            lockOverlay.visibility = View.VISIBLE
            Toast.makeText(this, getString(R.string.screen_locked), Toast.LENGTH_SHORT).show()
        } else {
            lockOverlay.visibility = View.GONE
            showControls()
            Toast.makeText(this, getString(R.string.screen_unlocked), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUnlockHint() {
        val btnUnlock = lockOverlay.findViewById<ImageButton>(R.id.btnUnlock)
        btnUnlock?.visibility = View.VISIBLE
        Handler(Looper.getMainLooper()).postDelayed({
            btnUnlock?.visibility = View.GONE
        }, 3000)
    }

    // === Audio track selection ===

    private fun showAudioTrackDialog() {
        val p = player ?: return
        val tracks = p.currentTracks

        // Собираем ВСЕ аудио-дорожки: и поддерживаемые, и нет. Раньше
        // отфильтрованные по поддержке дорожки скрывались, и при
        // не-AAC-потоках (AC3/EAC3 без аппаратного декодера) список
        // оказывался пустым — пользователь видел "no audio tracks", хоть
        // дорожка в потоке есть. Помечаем неподдерживаемые как
        // "неподдерж." — пускай хотя бы видно, что есть в потоке.
        val audioTracks = mutableListOf<Pair<String, Int>>() // label, groupIndex
        var groupIndex = 0
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val supported = group.isTrackSupported(i)
                    val label = buildString {
                        append(format.label ?: format.language ?: "Track ${audioTracks.size + 1}")
                        format.codecs?.let { append(" [$it]") }
                            ?: format.sampleMimeType?.let { append(" [${it.substringAfter('/')}]") }
                        if (format.channelCount > 0) append(" (${format.channelCount}ch)")
                        if (format.sampleRate > 0) append(" ${format.sampleRate / 1000}kHz")
                        if (!supported) append(" — неподдерж.")
                    }
                    audioTracks.add(label to groupIndex)
                }
                groupIndex++
            } else {
                groupIndex++
            }
        }

        if (audioTracks.isEmpty()) {
            // Соберём диагностику: ExoPlayer не нашёл ни одной аудио-
            // группы. Показываем что есть в потоке, чтобы видеть
            // действительно ли аудио отсутствует или её просто не парсит
            // экстрактор.
            val diag = buildString {
                append(getString(R.string.no_audio_tracks))
                append("\n")
                if (tracks.groups.isEmpty()) {
                    append("В потоке нет дорожек вовсе.")
                } else {
                    append("В потоке найдено: ")
                    val parts = tracks.groups.mapIndexed { idx, g ->
                        val type = when (g.type) {
                            C.TRACK_TYPE_VIDEO -> "video"
                            C.TRACK_TYPE_AUDIO -> "audio"
                            C.TRACK_TYPE_TEXT -> "text"
                            else -> "type${g.type}"
                        }
                        val mime = if (g.length > 0) g.getTrackFormat(0).sampleMimeType ?: "?" else "?"
                        "$type[$mime]"
                    }
                    append(parts.joinToString(", "))
                }
            }
            Toast.makeText(this, diag, Toast.LENGTH_LONG).show()
            return
        }

        val names = audioTracks.map { it.first }.toTypedArray()
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_TVViewer_Dialog)
            .setTitle(getString(R.string.audio_track))
            .setItems(names) { _, which ->
                selectAudioTrack(which)
            }
            .create()
        // Anchor to the right side as a narrow side sheet so it doesn't
        // span the whole screen ("слишком растянуто в лево").
        dialog.window?.let { w ->
            val params = w.attributes
            params.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            params.width = (resources.displayMetrics.widthPixels * 0.32f).toInt().coerceIn(320, 520)
            params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            w.attributes = params
        }
        dialog.show()
    }

    private fun selectAudioTrack(trackIndex: Int) {
        val p = player ?: return
        var audioGroupIdx = 0
        var audioTrackIdx = 0
        var currentAudioTrack = 0

        for (group in p.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    if (currentAudioTrack == trackIndex) {
                        val override = TrackSelectionOverride(group.mediaTrackGroup, i)
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(override)
                            .build()
                        Toast.makeText(this, "${getString(R.string.audio_track)}: ${group.getTrackFormat(i).label ?: "Track ${trackIndex + 1}"}", Toast.LENGTH_SHORT).show()
                        return
                    }
                    currentAudioTrack++
                }
            }
        }
    }

    // === Playback speed ===

    private fun cycleSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speedValues.size
        val speed = speedValues[currentSpeedIndex]
        player?.playbackParameters = PlaybackParameters(speed)
        Toast.makeText(this, "${getString(R.string.playback_speed)}: ${speedLabels[currentSpeedIndex]}", Toast.LENGTH_SHORT).show()
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

        // Apply the user-configured User-Agent + Referer to every HTTP
        // request. Many regional streams (especially Azerbaijani / CIS)
        // reject the default ExoPlayer UA or require a same-origin
        // Referer; default Referer is derived from the stream URL's
        // origin so that case "just works" out of the box.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(prefs.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
        val headers = HashMap<String, String>()
        prefs.httpReferer.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        if (headers.isNotEmpty()) httpDataSourceFactory.setDefaultRequestProperties(headers)
        val wrappedFactory = androidx.media3.datasource.DataSource.Factory {
            val ds = httpDataSourceFactory.createDataSource()
            // Auto-Referer: when the user has nothing configured,
            // use the stream URL's scheme://host so picky servers
            // (tv.izone.az etc.) accept the request.
            val streamUrl = currentUrl
            if (prefs.httpReferer.isBlank() && !streamUrl.isNullOrBlank()) {
                try {
                    val u = java.net.URI(streamUrl)
                    val origin = "${u.scheme}://${u.host}" +
                        (if (u.port > 0) ":${u.port}" else "") + "/"
                    ds.setRequestProperty("Referer", origin)
                    ds.setRequestProperty("Origin", origin.trimEnd('/'))
                } catch (_: Exception) {}
            }
            ds
        }
        // Stash so playStream's createMediaSourceFor() can build sources.
        this.streamDataFactory = wrappedFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(wrappedFactory)

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also { p ->
                playerView.player = p
                // Параметры выбора дорожек по умолчанию режут аудио, если
                // ни одна дорожка не подходит под "preferredAudioLanguages".
                // Снимаем фильтр — пускай играет любая поддерживаемая
                // аудио-дорожка (важно для izone.az и других стримов с
                // одной аудио без объявленного языка).
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage(null)
                    .setPreferredAudioMimeTypes()
                    .build()
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
                                updateAudioTrackInfo()
                                // Successful playback resets the back-off
                                reconnectAttempts = 0
                                reconnectHandler.removeCallbacks(reconnectRunnable)
                            }
                            Player.STATE_ENDED -> {
                                loadingIndicator.visibility = View.GONE
                                // Live streams shouldn't normally end —
                                // treat unexpected end as a transient
                                // failure and reconnect.
                                scheduleReconnect()
                            }
                            Player.STATE_IDLE -> {
                                loadingIndicator.visibility = View.GONE
                            }
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        ensureAudioTrackSelected(tracks)
                        updateAudioTrackInfo()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        loadingIndicator.visibility = View.GONE
                        ErrorLogger.logException(this@PlayerActivity, error)
                        // BehindLiveWindowException: HLS playback fell off
                        // the back of the rolling live window. Don't tear
                        // the source down — just jump to the live edge.
                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            try {
                                player?.seekToDefaultPosition()
                                player?.prepare()
                                // If the seek alone doesn't bring us back
                                // (rare but happens on some HLS streams),
                                // schedule a reconnect 5s later as a safety
                                // net. STATE_READY will cancel it.
                                reconnectHandler.postDelayed(reconnectRunnable, 5_000)
                                return
                            } catch (_: Exception) {}
                        }
                        scheduleReconnect()
                    }
                })
            }
    }

    private fun scheduleReconnect() {
        reconnectHandler.removeCallbacks(reconnectRunnable)
        if (currentUrl == null) return
        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT) {
            // Give up: show the manual retry button
            errorLayout.visibility = View.VISIBLE
            errorText.text = getString(R.string.error_playback)
            return
        }
        // Exponential back-off, capped at 30s: 1s, 2s, 4s, 8s, 16s, 30s, 30s …
        val delayMs = (1000L shl (reconnectAttempts - 1).coerceAtMost(5)).coerceAtMost(30_000)
        errorLayout.visibility = View.VISIBLE
        errorText.text = getString(R.string.reconnecting) +
            " (${reconnectAttempts}/$MAX_RECONNECT)"
        reconnectHandler.postDelayed(reconnectRunnable, delayMs)
    }

    /**
     * Многие HLS-стримы (izone.az, региональные порталы CIS) объявляют
     * аудио в отдельном #EXT-X-MEDIA:TYPE=AUDIO без флага DEFAULT=YES,
     * и ExoPlayer не выбирает ни одну дорожку — звук пропадает, видео
     * идёт. Здесь вручную включаем первую поддерживаемую аудио-дорожку,
     * если автовыбор оставил всё выключенным.
     */
    private fun ensureAudioTrackSelected(tracks: Tracks) {
        val p = player ?: return
        val hasSelectedAudio = tracks.groups.any { g ->
            g.type == C.TRACK_TYPE_AUDIO && g.isSelected
        }
        if (hasSelectedAudio) return
        val firstSupported = tracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && hasAnySupportedTrack(it) }
            ?: return
        for (i in 0 until firstSupported.length) {
            if (firstSupported.isTrackSupported(i)) {
                val override = TrackSelectionOverride(firstSupported.mediaTrackGroup, i)
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(override)
                    .build()
                Log.d("PlayerActivity", "Force-selected audio track: ${firstSupported.getTrackFormat(i).label ?: firstSupported.getTrackFormat(i).language}")
                return
            }
        }
    }

    private fun hasAnySupportedTrack(group: Tracks.Group): Boolean {
        for (i in 0 until group.length) {
            if (group.isTrackSupported(i)) return true
        }
        return false
    }

    private fun updateAudioTrackInfo() {
        val p = player ?: return
        var audioCount = 0
        for (group in p.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                audioCount += group.length
            }
        }
        if (audioCount > 1) {
            btnAudioTrack.visibility = View.VISIBLE
            audioTrackInfo.text = "$audioCount"
            audioTrackInfo.visibility = View.VISIBLE
        } else {
            audioTrackInfo.visibility = View.GONE
        }
    }

    /**
     * Build a MediaItem with a sensible mime-type hint so ExoPlayer picks
     * the right source factory. Many IPTV portals serve HLS at URLs that
     * don't end in .m3u8 (e.g. http://host:8080/play/abc?token=xxx). With
     * no hint, ExoPlayer treats them as progressive and fails with
     * UnrecognizedInputFormatException. We default to HLS for any URL
     * that has no clearly-progressive extension.
     */
    private fun buildMediaItem(url: String): MediaItem {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val mime = when {
            path.contains(".m3u8") || path.contains(".m3u") ->
                androidx.media3.common.MimeTypes.APPLICATION_M3U8
            path.contains(".mpd") ->
                androidx.media3.common.MimeTypes.APPLICATION_MPD
            // Clearly progressive containers — keep them progressive.
            path.endsWith(".mp4") || path.endsWith(".m4v") ->
                androidx.media3.common.MimeTypes.VIDEO_MP4
            path.endsWith(".webm") ->
                androidx.media3.common.MimeTypes.VIDEO_WEBM
            path.endsWith(".flv") ->
                androidx.media3.common.MimeTypes.VIDEO_FLV
            path.endsWith(".mkv") ->
                androidx.media3.common.MimeTypes.VIDEO_MATROSKA
            // RTMP / RTSP — leave to default detection
            url.startsWith("rtmp", true) || url.startsWith("rtsp", true) -> null
            // Everything else (including bare URLs and URLs that end in
            // .ts) — assume HLS. Many IPTV portals (izone.az and similar)
            // serve HLS via URLs that look progressive but actually
            // return an m3u8 manifest. Treating them as HLS lets
            // HlsMediaSource handle the manifest; if the server really
            // returns raw MPEG-TS, HlsMediaSource still falls back
            // gracefully via single-segment HLS handling.
            else -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
        }
        val builder = MediaItem.Builder().setUri(url)
        if (mime != null) builder.setMimeType(mime)
        return builder.build()
    }

    private fun createMediaSourceFor(url: String): androidx.media3.exoplayer.source.MediaSource {
        val factory = streamDataFactory
            ?: androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(prefs.userAgent)
                .setAllowCrossProtocolRedirects(true)
        val item = buildMediaItem(url)
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm")
                || path.endsWith(".mkv") || path.endsWith(".flv") ->
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(item)
            path.contains(".mpd") ->
                // DASH — теперь зависимость media3-exoplayer-dash подключена,
                // строим MediaSource напрямую, без обхода через
                // DefaultMediaSourceFactory.
                androidx.media3.exoplayer.dash.DashMediaSource.Factory(factory)
                    .createMediaSource(item)
            url.startsWith("rtsp", true) ->
                androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                    .createMediaSource(item)
            else ->
                // HLS by default — covers .m3u8, .ts, no-extension, query-
                // string-only IPTV portal URLs (izone.az, ucoz, …).
                // setAllowChunklessPreparation(false): принудительно
                // подгружаем все renditions сразу (включая отдельные
                // аудио-дорожки), иначе на izone.az звуковая дорожка не
                // обнаруживается до первого segment'а.
                androidx.media3.exoplayer.hls.HlsMediaSource.Factory(factory)
                    .setAllowChunklessPreparation(false)
                    .createMediaSource(item)
        }
    }

    private fun playStream(url: String) {
        loadingIndicator.visibility = View.VISIBLE
        errorLayout.visibility = View.GONE
        // Restore per-channel saved state (speed, aspect, position).
        val savedState = prefs.getChannelState(url)
        val savedSpeed = savedState.optDouble("speed", 1.0).toFloat()
        val savedAspect = savedState.optInt("aspect", -1)
        val savedPos = savedState.optLong("pos", -1L)

        player?.apply {
            // Build the source explicitly so HLS / DASH / Progressive is
            // chosen by URL pattern. DefaultMediaSourceFactory's auto
            // detection in Media3 1.2 occasionally falls into the
            // progressive pipeline even when MediaItem.mimeType is set,
            // which is why streams from izone-style portals were getting
            // UnrecognizedInputFormatException.
            val source = createMediaSourceFor(url)
            setMediaSource(source)
            prepare()
            playWhenReady = true
            // Speed
            val speedIdx = speedValues.indexOfFirst { kotlin.math.abs(it - savedSpeed) < 0.01f }
            if (speedIdx >= 0) {
                currentSpeedIndex = speedIdx
                playbackParameters = PlaybackParameters(speedValues[speedIdx])
            }
            // Position — only seek for VOD-like content (duration known and remaining > 5%).
            if (savedPos > 30_000L) {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            val dur = duration
                            if (dur != C.TIME_UNSET && dur > 0 && savedPos < dur * 0.95) {
                                seekTo(savedPos)
                            }
                            removeListener(this)
                        }
                    }
                })
            }
        }
        if (savedAspect in 0..3) {
            aspectRatioMode = savedAspect
            applyAspectRatioMode()
        }
    }

    private fun applyAspectRatioMode() {
        when (aspectRatioMode) {
            0 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            1 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            2 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            3 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }

    private fun saveCurrentChannelState() {
        val url = currentUrl ?: return
        if (url.isBlank()) return
        val p = player
        try {
            val obj = org.json.JSONObject()
            obj.put("speed", speedValues[currentSpeedIndex].toDouble())
            obj.put("aspect", aspectRatioMode)
            if (p != null) {
                val dur = p.duration
                val pos = p.currentPosition
                // Only persist position for VOD (known duration). Skip live streams.
                if (dur != C.TIME_UNSET && dur > 0 && pos > 30_000L && pos < dur * 0.95) {
                    obj.put("pos", pos)
                } else {
                    obj.put("pos", -1L)
                }
                obj.put("volume", p.volume.toDouble())
            }
            prefs.saveChannelState(url, obj)
        } catch (_: Exception) {}
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

        // New channel — reset reconnect counter
        reconnectAttempts = 0
        reconnectHandler.removeCallbacks(reconnectRunnable)

        // Persist the state of the channel we're leaving before switching.
        saveCurrentChannelState()

        currentIndex = index
        val channel = channels[currentIndex]

        currentUrl = channel.url
        channelName.text = channel.name
        channelNumber.text = "${currentIndex + 1} / ${channels.size}"
        ChannelDataHolder.currentChannelIndex = currentIndex

        prefs.lastChannelUrl = currentUrl
        prefs.pushRecent(channel.url)

        overlayAdapter?.updateCurrentIndex(currentIndex)

        // Reset speed to 1x by default — playStream will restore saved speed if any.
        currentSpeedIndex = 2
        player?.playbackParameters = PlaybackParameters(1f)

        updateEpg()
        playStream(channel.url)
        showChannelBanner()
        scheduleHideControls()
    }

    private fun updateEpg() {
        val channels = ChannelDataHolder.allChannels
        if (currentIndex in channels.indices) {
            val channel = channels[currentIndex]
            val (nowProg, nextProg) = EpgRepository.getNowNextDetailed(ChannelDataHolder.epgData, channel.tvgId, channel.name)
            if (nowProg != null) {
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val nowTime = timeFormat.format(Date(nowProg.start))
                val nowEndTime = timeFormat.format(Date(nowProg.end))
                epgNow.text = "$nowTime - $nowEndTime  ${nowProg.title}"
                epgNow.visibility = View.VISIBLE
            } else {
                epgNow.visibility = View.GONE
            }
        }
    }

    private fun updateClock() {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        clockDisplay.text = time
        persistentClock.text = time
    }

    private fun startClock() {
        updateClock()
        clockHandler.postDelayed(clockRunnable, 30000)
    }

    private fun cycleAspectRatio() {
        aspectRatioMode = (aspectRatioMode + 1) % 4
        applyAspectRatioMode()
        val names = arrayOf(
            getString(R.string.aspect_fit),
            getString(R.string.aspect_16_9),
            getString(R.string.aspect_4_3),
            getString(R.string.aspect_fill)
        )
        Toast.makeText(this, names[aspectRatioMode], Toast.LENGTH_SHORT).show()
    }

    // === Channel info banner ===

    private fun showChannelBanner() {
        val channels = ChannelDataHolder.allChannels
        if (currentIndex !in channels.indices) return

        val channel = channels[currentIndex]
        bannerChannelNumber.text = "${currentIndex + 1}"
        bannerChannelName.text = channel.name

        channel.logoUrl?.let { url ->
            bannerChannelLogo.load(url) {
                crossfade(true)
                error(R.drawable.ic_channel_placeholder)
                placeholder(R.drawable.ic_channel_placeholder)
            }
        }

        val (nowProg, nextProg) = EpgRepository.getNowNextDetailed(ChannelDataHolder.epgData, channel.tvgId, channel.name)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        if (nowProg != null) {
            val nowTime = timeFormat.format(Date(nowProg.start))
            val nowEndTime = timeFormat.format(Date(nowProg.end))
            bannerEpgNow.text = "$nowTime - $nowEndTime  ${nowProg.title}"
            bannerEpgNow.visibility = View.VISIBLE
            // Show progress
            val progress = EpgRepository.getCurrentProgress(nowProg)
            bannerEpgProgress.progress = (progress * 100).toInt()
            bannerEpgProgress.visibility = View.VISIBLE
        } else {
            bannerEpgNow.visibility = View.GONE
            bannerEpgProgress.visibility = View.GONE
        }
        if (nextProg != null) {
            val nextTime = timeFormat.format(Date(nextProg.start))
            bannerEpgNext.text = "${getString(R.string.epg_next)}: $nextTime ${nextProg.title}"
            bannerEpgNext.visibility = View.VISIBLE
        } else {
            bannerEpgNext.visibility = View.GONE
        }

        val time = timeFormat.format(Date())
        bannerClock.text = time

        channelInfoBanner.visibility = View.VISIBLE
        bannerHandler.removeCallbacks(bannerHideRunnable)
        bannerHandler.postDelayed(bannerHideRunnable, 5000)

        // Tap banner to show full EPG info
        channelInfoBanner.setOnClickListener {
            showEpgDetailDialog()
        }
    }

    private fun showEpgDetailDialog() {
        val channels = ChannelDataHolder.allChannels
        if (currentIndex !in channels.indices) return
        val channel = channels[currentIndex]
        val epg = ChannelDataHolder.epgData
        val tvgId = channel.tvgId ?: return

        val normId = tvgId.lowercase().replace(Regex("[^a-z0-9]"), "")
        val programmes = epg[normId] ?: return
        if (programmes.isEmpty()) return

        val now = System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        // Show current + next 10 programmes
        val relevantProgs = programmes.filter { it.end >= now }.take(12)
        if (relevantProgs.isEmpty()) return

        val sb = StringBuilder()
        for (p in relevantProgs) {
            val startTime = timeFormat.format(Date(p.start))
            val endTime = timeFormat.format(Date(p.end))
            val isCurrent = now in p.start..p.end
            if (isCurrent) {
                sb.append("▶ $startTime - $endTime  ${p.title}")
                val progress = EpgRepository.getCurrentProgress(p)
                sb.append(" [${(progress * 100).toInt()}%]")
            } else {
                sb.append("   $startTime - $endTime  ${p.title}")
            }
            if (p.description.isNotEmpty()) {
                sb.append("\n     ${p.description.take(100)}")
            }
            sb.append("\n\n")
        }

        android.app.AlertDialog.Builder(this, R.style.Theme_TVViewer_Dialog)
            .setTitle("${channel.name} — ${getString(R.string.tv_guide)}")
            .setMessage(sb.toString().trim())
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // === Channel list overlay ===

    private fun toggleChannelList() {
        if (channelListVisible) hideChannelList() else showChannelList()
    }

    private fun showChannelList() {
        channelListOverlay.visibility = View.VISIBLE
        channelListVisible = true
        hideHandler.removeCallbacks(hideRunnable)

        // Scroll to current channel and focus it so the D-pad immediately
        // navigates inside the list (otherwise the user is stuck on the
        // PlayerView with no visible focus target).
        val scrollIndex = overlayFilteredIndices.indexOf(currentIndex)
        val target = if (scrollIndex >= 0) scrollIndex else currentIndex.coerceAtLeast(0)
        overlayChannelsList.scrollToPosition(target)
        overlayChannelsList.post {
            val vh = overlayChannelsList.findViewHolderForAdapterPosition(target)
            if (vh != null) {
                vh.itemView.requestFocus()
            } else {
                // The view holder isn't bound yet — request focus on the list
                // and let descendant focus pick the first child once laid out.
                overlayChannelsList.requestFocus()
            }
        }
        scheduleHideChannelList()
    }

    private fun hideChannelList() {
        channelListOverlay.visibility = View.GONE
        channelListVisible = false
        channelListHideHandler.removeCallbacks(channelListHideRunnable)
        scheduleHideControls()
    }

    private fun scheduleHideChannelList() {
        channelListHideHandler.removeCallbacks(channelListHideRunnable)
        val seconds = prefs.channelListAutoHideSeconds
        if (seconds > 0) {
            channelListHideHandler.postDelayed(channelListHideRunnable, seconds * 1000L)
        }
    }

    /** Reset the channel-list inactivity timer when the user interacts with it. */
    private fun bumpChannelListIdleTimer() {
        if (channelListVisible) scheduleHideChannelList()
    }

    private fun isFocusInTopBar(): Boolean {
        var v: View? = currentFocus ?: return false
        while (v != null) {
            if (v == topBar) return true
            v = (v.parent as? View)
        }
        return false
    }

    private fun playerDrawerVisible(): Boolean =
        ::playerDrawerOverlay.isInitialized &&
            playerDrawerOverlay.visibility == View.VISIBLE

    private fun showPlayerDrawer() {
        playerDrawerOverlay.visibility = View.VISIBLE
        playerDrawerOverlay.bringToFront()
        // Сдвигаем панель списка каналов вправо ровно на ширину выдвижного
        // меню (260dp — см. activity_player.xml), чтобы оба элемента
        // отображались рядом, а не перекрывали друг друга.
        val drawerWidth = (260 * resources.displayMetrics.density).toInt()
        findViewById<View>(R.id.channelListPanel)
            ?.animate()?.translationX(drawerWidth.toFloat())?.setDuration(150)?.start()
        playerDrawerOverlay.findViewById<View>(R.id.playerDrawerPlaylists)?.requestFocus()
    }

    private fun hidePlayerDrawer() {
        if (::playerDrawerOverlay.isInitialized) {
            playerDrawerOverlay.visibility = View.GONE
            findViewById<View>(R.id.channelListPanel)
                ?.animate()?.translationX(0f)?.setDuration(150)?.start()
            // Return focus to the channel list so the user can keep navigating
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.overlayChannelsList)
                ?.requestFocus()
        }
    }

    private fun rightMenuVisible(): Boolean =
        ::playerRightMenuOverlay.isInitialized &&
            playerRightMenuOverlay.visibility == View.VISIBLE

    private fun showPlayerRightMenu() {
        playerRightMenuOverlay.visibility = View.VISIBLE
        playerRightMenuOverlay.bringToFront()
        playerRightMenuOverlay.findViewById<View>(R.id.rightMenuChannelList)?.requestFocus()
    }

    private fun hidePlayerRightMenu() {
        if (::playerRightMenuOverlay.isInitialized) {
            playerRightMenuOverlay.visibility = View.GONE
        }
    }

    private fun isInsideControlsOverlay(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p == controlsOverlay) return true
            p = p.parent as? View
        }
        return false
    }

    private fun focusTopBar() {
        showControls()
        // Give the layout a tick to lay out before requesting focus
        topBar.post {
            // Pick first focusable child of the top bar
            for (i in 0 until topBar.childCount) {
                val c = topBar.getChildAt(i)
                if (c.isFocusable && c.visibility == View.VISIBLE) {
                    c.requestFocus()
                    break
                }
            }
            // Also reset the auto-hide timer so user has time to interact
            scheduleHideControls()
        }
    }

    // === Controls visibility ===

    private fun toggleControls() {
        if (isScreenLocked) return
        if (channelListVisible) {
            hideChannelList()
            return
        }
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        if (isScreenLocked) return
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
        // Any keypress while the channel list is visible counts as activity
        if (channelListVisible) bumpChannelListIdleTimer()

        // Правое выпадающее меню: BACK или DPAD_LEFT закрывают, остальное
        // — стандартная навигация по пунктам.
        if (rightMenuVisible()) {
            if (keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                hidePlayerRightMenu(); return true
            }
            return super.onKeyDown(keyCode, event)
        }

        // Player drawer: Back closes it; everything else falls through to
        // the default focus traversal so the user can move between menu
        // items and click them.
        if (playerDrawerVisible()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { hidePlayerDrawer(); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { hidePlayerDrawer(); return true }
            return super.onKeyDown(keyCode, event)
        }

        if (isScreenLocked) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                toggleScreenLock()
                return true
            }
            return true
        }

        // When focus is already inside the top bar, defer all D-pad keys to
        // Android's default focus traversal so the user can freely move
        // between buttons (otherwise UP/DOWN below would re-grab the keys
        // and switch channels instead).
        val inTopBar = isFocusInTopBar()
        if (inTopBar) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> return super.onKeyDown(keyCode, event)
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // С верхнего бара (там теперь только кнопка Назад)
                    // Right тоже открывает правое меню плеера, как и из
                    // основной зоны.
                    showPlayerRightMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Leave the top bar — return focus to the player area
                    playerView.requestFocus()
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    playerView.requestFocus()
                    return true
                }
            }
        }

        when (keyCode) {
            // D-pad center / Enter - toggle controls or select
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (channelListVisible) return super.onKeyDown(keyCode, event)
                toggleControls()
                return true
            }
            // D-pad Up - previous channel, OR enter top bar if controls already shown
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (channelListVisible) return super.onKeyDown(keyCode, event)
                if (controlsVisible && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    focusTopBar()
                    return true
                }
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
            // D-pad Left
            //   1st press → show channel list
            //   2nd press (channel list already visible AND focus already at
            //              the leftmost item) → leave the player and surface
            //              the side drawer in MainActivity
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (playerDrawerVisible()) {
                    return super.onKeyDown(keyCode, event)
                }
                if (channelListVisible) {
                    // 2nd LEFT inside the channel list overlay → show
                    // the in-player drawer ON TOP of the channel list,
                    // without closing the player.
                    showPlayerDrawer()
                    return true
                }
                toggleChannelList()
                return true
            }
            // D-pad Right
            //   • если открыт список каналов — закрываем его
            //   • иначе — открываем правое выпадающее меню плеера
            //     (Аудио / Скорость / Список каналов / PiP / Блокировка /
            //     Соотношение). Раньше эти кнопки висели в верхнем
            //     правом углу — теперь убраны и доступны только отсюда.
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (channelListVisible) {
                    hideChannelList()
                    return true
                }
                showPlayerRightMenu()
                return true
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
                showChannelBanner()
                return true
            }
            // Favourite hotkey: F / yellow remote button / bookmark
            KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_PROG_YELLOW,
            KeyEvent.KEYCODE_BOOKMARK -> {
                val channels = ChannelDataHolder.allChannels
                if (currentIndex in channels.indices) {
                    toggleFavorite(channels[currentIndex])
                    val msg = if (prefs.isFavorite(channels[currentIndex].url))
                        "★ ${channels[currentIndex].name}" else channels[currentIndex].name
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
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

    // === Picture-in-Picture ===

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            controlsOverlay.visibility = View.GONE
            channelListOverlay.visibility = View.GONE
            channelInfoBanner.visibility = View.GONE
            lockOverlay.visibility = View.GONE
            controlsVisible = false
            channelListVisible = false
        } else {
            // Пользователь закрыл окно PiP крестиком (а не вернулся в плеер):
            // активити уже в STOPPED, плеер продолжал крутить аудио в фоне —
            // надо прибить, иначе звук остаётся висеть до следующего onCreate.
            if (lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED ||
                lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED) {
                player?.stop()
                player?.release()
                player = null
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Покинули PiP-окно (свернули в фон, выключили картинку): глушим
        // плеер, чтобы звук не "залипал" в системе.
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip && !keepPlayingInBackground) {
            player?.stop()
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
        // Keep playing in Picture-in-Picture mode
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        // Если пользователь открыл Настройки прямо из плеера — не ставим
        // на паузу: пускай идёт трансляция, пока он крутит ползунки.
        if (!inPip && !keepPlayingInBackground) {
            player?.pause()
        }
        saveCurrentChannelState()
    }

    override fun onResume() {
        super.onResume()
        // Возвращаемся из Настроек / другой активити — флаг сбрасываем,
        // дальнейшие onPause работают как обычно.
        keepPlayingInBackground = false
        player?.play()
        hideSystemUI()
    }

    override fun onDestroy() {
        saveCurrentChannelState()
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        numberHandler.removeCallbacks(numberRunnable)
        sleepHandler.removeCallbacks(sleepTimerRunnable)
        bannerHandler.removeCallbacks(bannerHideRunnable)
        reconnectHandler.removeCallbacks(reconnectRunnable)
        channelListHideHandler.removeCallbacks(channelListHideRunnable)
        player?.release()
        player = null
    }
}
