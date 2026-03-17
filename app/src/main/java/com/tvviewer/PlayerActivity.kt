package com.tvviewer

import android.app.PictureInPictureParams
import android.content.Context
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
            override fun afterTextChanged(s: Editable?) { filterOverlayChannels() }
        })

        // Category chips in overlay
        val overlayCategoriesList = findViewById<RecyclerView>(R.id.overlayCategoriesList)
        overlayCategoriesList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val channels = ChannelDataHolder.allChannels
        val cats = listOf(getString(R.string.all)) + channels.mapNotNull { it.group }.distinct().sorted()
        val catAdapter = CategoryAdapter(cats) { category ->
            overlaySelectedCategory = category
            filterOverlayChannels()
        }
        overlayCategoriesList.adapter = catAdapter

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

        overlayAdapter = OverlayChannelAdapter(channels, ChannelDataHolder.epgData, currentIndex) { index ->
            switchToChannel(index)
            hideChannelList()
        }
        overlayChannelsList.adapter = overlayAdapter
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

        overlayAdapter = OverlayChannelAdapter(overlayFilteredChannels, ChannelDataHolder.epgData, filteredCurrentIndex) { filteredIndex ->
            if (filteredIndex in overlayFilteredIndices.indices) {
                val realIndex = overlayFilteredIndices[filteredIndex]
                switchToChannel(realIndex)
                hideChannelList()
            }
        }
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

        val audioTracks = mutableListOf<Pair<String, Int>>() // label, groupIndex
        var groupIndex = 0
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = buildString {
                        append(format.label ?: format.language ?: "Track ${audioTracks.size + 1}")
                        if (format.channelCount > 0) append(" (${format.channelCount}ch)")
                        if (format.sampleRate > 0) append(" ${format.sampleRate / 1000}kHz")
                    }
                    audioTracks.add(label to groupIndex)
                }
                groupIndex++
            } else {
                groupIndex++
            }
        }

        if (audioTracks.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_audio_tracks), Toast.LENGTH_SHORT).show()
            return
        }

        val names = audioTracks.map { it.first }.toTypedArray()
        android.app.AlertDialog.Builder(this, R.style.Theme_TVViewer_Dialog)
            .setTitle(getString(R.string.audio_track))
            .setItems(names) { _, which ->
                selectAudioTrack(which)
            }
            .show()
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
                                updateAudioTrackInfo()
                            }
                            Player.STATE_ENDED, Player.STATE_IDLE -> {
                                loadingIndicator.visibility = View.GONE
                            }
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateAudioTrackInfo()
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
        showChannelBanner()
        scheduleHideControls()

        // Reset speed to 1x on channel switch
        if (currentSpeedIndex != 2) {
            currentSpeedIndex = 2
            player?.playbackParameters = PlaybackParameters(1f)
        }
    }

    private fun updateEpg() {
        val channels = ChannelDataHolder.allChannels
        if (currentIndex in channels.indices) {
            val channel = channels[currentIndex]
            val (nowProg, nextProg) = EpgRepository.getNowNextDetailed(ChannelDataHolder.epgData, channel.tvgId)
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

        val (nowProg, nextProg) = EpgRepository.getNowNextDetailed(ChannelDataHolder.epgData, channel.tvgId)
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
        val scrollIndex = overlayFilteredIndices.indexOf(currentIndex)
        if (scrollIndex >= 0) {
            overlayChannelsList.scrollToPosition(scrollIndex)
        } else {
            overlayChannelsList.scrollToPosition(currentIndex.coerceAtLeast(0))
        }
    }

    private fun hideChannelList() {
        channelListOverlay.visibility = View.GONE
        channelListVisible = false
        scheduleHideControls()
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
        if (isScreenLocked) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                toggleScreenLock()
                return true
            }
            return true
        }

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
            // D-pad Left - show channel list
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
                showChannelBanner()
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
        bannerHandler.removeCallbacks(bannerHideRunnable)
        player?.release()
        player = null
    }
}
