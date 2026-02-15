package com.tvviewer

import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {

    companion object {
        private const val TAG = "TVViewer"
    }

    private lateinit var prefs: AppPreferences
    private lateinit var playerView: PlayerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyState: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var playlistSpinner: Spinner
    private lateinit var categorySpinner: Spinner
    private lateinit var btnFavorites: ImageButton
    private lateinit var leftSideContainer: View
    private lateinit var settingsPanel: View
    private lateinit var rightSettingsPanel: View
    private lateinit var channelPanel: LinearLayout
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnAspectRatio: ImageButton
    private lateinit var playerBottomBar: View
    private lateinit var searchChannels: EditText
    private lateinit var timeDisplay: TextView

    private var player: ExoPlayer? = null
    private var filteredChannels: List<Channel> = emptyList()
    private var leftSideVisible = false
    private var settingsPanelVisible = false
    private var rightPanelVisible = false
    private var aspectRatioMode = 0 // 0=fit, 1=16:9, 2=4:3, 3=fill
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null
    private lateinit var adapter: ChannelAdapter
    private var trackSelector: DefaultTrackSelector? = null
    private var loadJob: Job? = null
    private var allChannels: List<Channel> = emptyList()
    private var showFavoritesOnly = false
    private var allPlaylists: List<Playlist> = emptyList()
    private var epgData: Map<String, List<EpgRepository.Programme>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started")
        prefs = AppPreferences(this)

        try {
            setContentView(R.layout.activity_main)

            playerView = findViewById(R.id.playerView)
            loadingIndicator = findViewById(R.id.loadingIndicator)
            emptyState = findViewById(R.id.emptyState)
            recyclerView = findViewById(R.id.recyclerView)
            playlistSpinner = findViewById(R.id.playlistSpinner)
            categorySpinner = findViewById(R.id.categorySpinner)
            btnFavorites = findViewById(R.id.btnFavorites)
            leftSideContainer = findViewById(R.id.leftSideContainer)
            settingsPanel = findViewById(R.id.settingsPanel)
            rightSettingsPanel = findViewById(R.id.rightSettingsPanel)
            channelPanel = findViewById(R.id.channelPanel)
            btnFullscreen = findViewById(R.id.btnFullscreen)
            btnAspectRatio = findViewById(R.id.btnAspectRatio)
            playerBottomBar = findViewById(R.id.playerBottomBar)
            searchChannels = findViewById(R.id.searchChannels)
            timeDisplay = findViewById(R.id.timeDisplay)

            findViewById<View>(R.id.rightEdgeZone).setOnClickListener {
                if (rightPanelVisible) hideRightPanel()
                else if (leftSideVisible) hideLeftSide()
                else showRightPanel()
            }
            findViewById<View>(R.id.leftEdgeZone).setOnClickListener { showLeftSide() }
            findViewById<View>(R.id.tapOverlay).setOnClickListener {
                if (leftSideVisible) {
                    // OTT style: close only via right/back
                } else {
                    showLeftSide()
                }
            }
            findViewById<ImageButton>(R.id.btnMenuArrow).setOnClickListener { toggleSettingsPanel() }
            setupPlayer()
            setupLeftSideButtons()
            setupRightPanel()
            setupSearch()
            setupPlayerOverlay()
            setupRecyclerView()
            setupCategorySpinner()
            setupFavoritesButton()
            setupPlaylistSpinner()
            setupBackPress()
            setupTimeDisplay()
            restoreState()
            Log.d(TAG, "onCreate completed")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error", e)
            ErrorLogger.logException(this, e)
            Toast.makeText(this, getString(R.string.error_start) + ": ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupLeftSideButtons() {
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnTvGuide).setOnClickListener { showTvGuide() }
        findViewById<ImageButton>(R.id.btnHideLeft).setOnClickListener { hideLeftSide() }
        findViewById<ImageButton>(R.id.btnHideChannels).setOnClickListener { hideLeftSide() }
    }

    private fun toggleSettingsPanel() {
        settingsPanelVisible = !settingsPanelVisible
        settingsPanel.visibility = if (settingsPanelVisible) View.VISIBLE else View.GONE
    }

    private fun setupRightPanel() {
        findViewById<View>(R.id.btnHideRightPanel).setOnClickListener { hideRightPanel() }
        findViewById<View>(R.id.btnAspectFit).setOnClickListener { setAspectRatio(0) }
        findViewById<View>(R.id.btnAspect169).setOnClickListener { setAspectRatio(1) }
        findViewById<View>(R.id.btnAspect43).setOnClickListener { setAspectRatio(2) }
        findViewById<View>(R.id.btnAspectFill).setOnClickListener { setAspectRatio(3) }
        val volumeBar = findViewById<SeekBar>(R.id.volumeSeekBar)
        volumeBar.progress = 100
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) player?.volume = progress / 100f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        val audioSpinner = findViewById<Spinner>(R.id.audioTrackSpinner)
        val subtitleSpinner = findViewById<Spinner>(R.id.subtitleSpinner)
        val audioAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf(getString(R.string.track_auto)))
        audioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        audioSpinner.adapter = audioAdapter
        val subAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf(getString(R.string.track_off), getString(R.string.track_auto)))
        subAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        subtitleSpinner.adapter = subAdapter
        audioSpinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyAudioTrackSelection(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        })
        subtitleSpinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applySubtitleSelection(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        })
        listOf(R.id.btnSpeed05, R.id.btnSpeed1, R.id.btnSpeed125, R.id.btnSpeed15, R.id.btnSpeed2)
            .forEachIndexed { i, id ->
                findViewById<View>(id).setOnClickListener {
                    val speeds = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
                    player?.setPlaybackSpeed(speeds[i])
                    updateVideoInfo()
                }
            }
    }

    private fun updateVideoInfo() {
        val infoText = findViewById<TextView>(R.id.videoInfoText)
        val p = player ?: run { infoText.text = "—"; return }
        val size = p.videoSize
        val res = if (size.width > 0 && size.height > 0) "${size.width}×${size.height}" else "—"
        val speed = String.format(Locale.US, "%.2gx", p.playbackParameters.speed)
        infoText.text = getString(R.string.resolution) + ": $res\n" + getString(R.string.playback_speed) + ": $speed"
    }

    private fun applyAudioTrackSelection(index: Int) {
        val ts = trackSelector ?: return
        val builder = ts.parameters.buildUpon()
        if (index == 0) {
            builder.clearOverrides()
        } else {
            val tracks = player?.currentTracks ?: return
            var trackIndex = 0
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO && group.length > 0) {
                    if (trackIndex == index - 1) {
                        builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
                        break
                    }
                    trackIndex++
                }
            }
        }
        ts.parameters = builder.build()
    }

    private fun applySubtitleSelection(index: Int) {
        val ts = trackSelector ?: return
        val builder = ts.parameters.buildUpon()
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, index == 0)
        ts.parameters = builder.build()
    }

    private fun refreshTrackSpinners() {
        try {
            val audioSpinner = findViewById<Spinner>(R.id.audioTrackSpinner)
            val subtitleSpinner = findViewById<Spinner>(R.id.subtitleSpinner)
            val tracks = player?.currentTracks ?: return
            val audioNames = mutableListOf(getString(R.string.track_auto))
            val subNames = mutableListOf(getString(R.string.track_off), getString(R.string.track_auto))
            for (group in tracks.groups) {
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> for (i in 0 until group.length) {
                        val fmt = group.getTrackFormat(i)
                        audioNames.add(fmt.language?.let { "Audio $it" } ?: "Audio ${audioNames.size}")
                    }
                    C.TRACK_TYPE_TEXT -> for (i in 0 until group.length) {
                        val fmt = group.getTrackFormat(i)
                        subNames.add(fmt.language?.let { "Sub $it" } ?: "Sub ${subNames.size}")
                    }
                    else -> {}
                }
            }
            val aAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, audioNames)
            aAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            audioSpinner.adapter = aAdapter
            val sAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, subNames)
            sAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            subtitleSpinner.adapter = sAdapter
        } catch (_: Exception) {}
    }

    private fun setAspectRatio(mode: Int) {
        aspectRatioMode = mode
        applyAspectRatio()
    }

    private fun showRightPanel() {
        rightPanelVisible = true
        rightSettingsPanel.visibility = View.VISIBLE
        findViewById<SeekBar>(R.id.volumeSeekBar).progress = ((player?.volume ?: 1f) * 100).toInt()
        refreshTrackSpinners()
        updateVideoInfo()
    }

    private fun hideRightPanel() {
        rightPanelVisible = false
        rightSettingsPanel.visibility = View.GONE
    }

    private val tvGuideLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(TvGuideActivity.EXTRA_CHANNEL_URL)?.let { url ->
                allChannels.find { it.url == url }?.let { playChannel(it) }
            }
        }
    }

    private fun showTvGuide() {
        if (allChannels.isEmpty()) {
            Toast.makeText(this, R.string.select_channel, Toast.LENGTH_SHORT).show()
            return
        }
        TvGuideChannels.channels = allChannels
        TvGuideChannels.currentUrl = prefs.lastChannelUrl
        tvGuideLauncher.launch(Intent(this, TvGuideActivity::class.java))
    }

    private fun setupPlayerOverlay() {
        btnFullscreen.setOnClickListener { toggleFullscreen() }
        btnFullscreen.setImageResource(
            if (prefs.isFullscreen) R.drawable.ic_fullscreen_exit
            else R.drawable.ic_fullscreen
        )
        btnAspectRatio.setOnClickListener { cycleAspectRatio() }
        findViewById<ImageButton>(R.id.btnPlayPause).setOnClickListener {
            player?.let { p ->
                p.playWhenReady = !p.playWhenReady
                updatePlayPauseButton()
            }
        }
    }

    private fun cycleAspectRatio() {
        aspectRatioMode = (aspectRatioMode + 1) % 4
        applyAspectRatio()
        val modes = listOf(R.string.aspect_fit, R.string.aspect_16_9, R.string.aspect_4_3, R.string.aspect_fill)
        Toast.makeText(this, getString(modes[aspectRatioMode]), Toast.LENGTH_SHORT).show()
    }

    private fun updateKeepScreenOn() {
        val p = player ?: run {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        val shouldKeepOn = p.playbackState != Player.STATE_IDLE &&
            p.playbackState != Player.STATE_ENDED &&
            p.playWhenReady
        if (shouldKeepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyAspectRatio() {
        val mode = when (aspectRatioMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = mode
    }

    private fun toggleFullscreen() {
        prefs.isFullscreen = !prefs.isFullscreen
        applyFullscreen(prefs.isFullscreen)
        btnFullscreen.setImageResource(
            if (prefs.isFullscreen) R.drawable.ic_fullscreen_exit
            else R.drawable.ic_fullscreen
        )
    }

    private fun toggleLeftSide() {
        leftSideVisible = !leftSideVisible
        leftSideContainer.visibility = if (leftSideVisible) View.VISIBLE else View.GONE
        if (!leftSideVisible) {
            settingsPanelVisible = false
            settingsPanel.visibility = View.GONE
        }
    }

    private fun hideLeftSide() {
        leftSideVisible = false
        settingsPanelVisible = false
        leftSideContainer.visibility = View.GONE
        settingsPanel.visibility = View.GONE
    }

    private fun showLeftSide() {
        leftSideVisible = true
        leftSideContainer.visibility = View.VISIBLE
        settingsPanelVisible = false
        settingsPanel.visibility = View.GONE
    }

    private fun updateBottomBarChannelInfo() {
        val logoView = findViewById<android.widget.ImageView>(R.id.bottomBarChannelLogo)
        val nameView = findViewById<TextView>(R.id.bottomBarChannelName)
        val epgView = findViewById<TextView>(R.id.bottomBarEpgInfo)
        val infoView = findViewById<TextView>(R.id.bottomBarVideoInfo)
        val channel = prefs.lastChannelUrl?.let { url -> allChannels.find { it.url == url } }
        if (channel != null) {
            nameView.text = channel.name
            nameView.visibility = View.VISIBLE
            logoView.load(channel.logoUrl) {
                crossfade(true)
                error(android.R.drawable.ic_menu_gallery)
                placeholder(android.R.drawable.ic_menu_gallery)
            }
            logoView.visibility = View.VISIBLE
            val (now, next) = EpgRepository.getNowNext(epgData, channel.tvgId)
            epgView.text = when {
                now != null && next != null -> "• $now → $next"
                now != null -> "• $now"
                next != null -> "→ $next"
                else -> ""
            }
            epgView.visibility = if (epgView.text.isNotEmpty()) View.VISIBLE else View.GONE
        } else {
            nameView.visibility = View.GONE
            logoView.visibility = View.GONE
            epgView.visibility = View.GONE
        }
        val p = player
        val res = if (p != null && p.videoSize.width > 0 && p.videoSize.height > 0) {
            "${p.videoSize.width}×${p.videoSize.height}"
        } else "—"
        val speed = String.format(Locale.US, "%.2gx", p?.playbackParameters?.speed ?: 1f)
        infoView.text = "$res  $speed"
    }

    private fun updatePlayPauseButton() {
        val btn = findViewById<ImageButton>(R.id.btnPlayPause)
        val p = player
        val isPlaying = p != null && p.playWhenReady
        btn.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun scheduleAutoHide(hideAction: () -> Unit) {
        cancelAutoHide()
        autoHideRunnable = Runnable { hideAction() }
        autoHideHandler.postDelayed(autoHideRunnable!!, prefs.channelListAutoHideSeconds * 1000L)
    }

    private fun cancelAutoHide() {
        autoHideRunnable?.let { autoHideHandler.removeCallbacks(it) }
        autoHideRunnable = null
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (rightPanelVisible) {
                    hideRightPanel()
                    return true
                }
                if (!leftSideVisible) {
                    showLeftSide()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (leftSideVisible) {
                    hideLeftSide()
                    return true
                }
                if (!rightPanelVisible) {
                    showRightPanel()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (rightPanelVisible) {
                    hideRightPanel()
                    return true
                }
                if (leftSideVisible) {
                    hideLeftSide()
                    return true
                }
                if (prefs.isFullscreen) {
                    toggleFullscreen()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!leftSideVisible && !rightPanelVisible && filteredChannels.isNotEmpty()) {
                    switchToPrevChannel()
                    updateBottomBarChannelInfo()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!leftSideVisible && !rightPanelVisible && filteredChannels.isNotEmpty()) {
                    switchToNextChannel()
                    updateBottomBarChannelInfo()
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                if (!leftSideVisible) {
                    showLeftSide()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun switchToPrevChannel() {
        val idx = filteredChannels.indexOfFirst { it.url == prefs.lastChannelUrl }
        val newIdx = if (idx <= 0) filteredChannels.size - 1 else idx - 1
        filteredChannels.getOrNull(newIdx)?.let { playChannel(it) }
    }

    private fun switchToNextChannel() {
        val idx = filteredChannels.indexOfFirst { it.url == prefs.lastChannelUrl }
        val newIdx = if (idx < 0 || idx >= filteredChannels.size - 1) 0 else idx + 1
        filteredChannels.getOrNull(newIdx)?.let { playChannel(it) }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (rightPanelVisible) {
                    hideRightPanel()
                } else if (leftSideVisible) {
                    hideLeftSide()
                } else if (prefs.isFullscreen) {
                    toggleFullscreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showFullscreenControlsTemporarily() {
        showLeftSide()
    }

    private fun setupSearch() {
        searchChannels.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilters()
            }
        })
    }

    private fun applyFilters() {
        val query = searchChannels.text.toString().trim().lowercase()
        var filtered = allChannels
        if (showFavoritesOnly) filtered = filtered.filter { prefs.isFavorite(it.url) }
        val spinnerAdapter = categorySpinner.adapter
        val catIdx = categorySpinner.selectedItemPosition
        if (catIdx > 0 && spinnerAdapter != null && catIdx < spinnerAdapter.count) {
            val category = categorySpinner.getItemAtPosition(catIdx) as? String ?: ""
            filtered = filtered.filter { it.group == category }
        }
        if (query.isNotEmpty()) {
            filtered = filtered.filter { it.name.lowercase().contains(query) }
        }
        filteredChannels = filtered
        adapter.updateChannels(filtered)
        adapter.updateFavorites(prefs.favorites)
    }

    private fun setupTimeDisplay() {
        applyTimeDisplayPosition()
        val timeRunnable = object : Runnable {
            override fun run() {
                if (prefs.timeDisplayPosition != "off") {
                    timeDisplay.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    timeDisplay.visibility = View.VISIBLE
                }
                autoHideHandler.postDelayed(this, 1000L)
            }
        }
        autoHideHandler.post(timeRunnable)
    }

    private fun applyTimeDisplayPosition() {
        val pos = prefs.timeDisplayPosition
        timeDisplay.visibility = if (pos == "off") View.GONE else View.VISIBLE
        val lp = timeDisplay.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        lp.gravity = when (pos) {
            "left" -> Gravity.TOP or Gravity.START
            "right" -> Gravity.TOP or Gravity.END
            "bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else -> Gravity.TOP or Gravity.START
        }
        timeDisplay.layoutParams = lp
    }

    private fun applyFullscreen(fullscreen: Boolean) {
        if (fullscreen) {
            leftSideContainer.visibility = View.GONE
            rightSettingsPanel.visibility = View.GONE
            rightPanelVisible = false
            // OTT style: keep bottom bar visible when playing
            // Remove blue status bar line: draw behind system bars, make them transparent
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { ctrl ->
                    ctrl.hide(android.view.WindowInsets.Type.statusBars())
                    ctrl.hide(android.view.WindowInsets.Type.navigationBars())
                    ctrl.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        } else {
            leftSideContainer.visibility = if (leftSideVisible) View.VISIBLE else View.GONE
            // Restore bottom bar based on player state
            val p = player
            if (prefs.playerType != AppPreferences.PLAYER_EXTERNAL && p != null &&
                (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)) {
                playerBottomBar.visibility = View.VISIBLE
                updateBottomBarChannelInfo()
            }
            // Restore theme colors
            window.statusBarColor = 0xFF0D47A1.toInt()
            window.navigationBarColor = 0xFF121212.toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(android.view.WindowInsets.Type.statusBars())
                window.insetsController?.show(android.view.WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    private fun setupPlayer() {
        try {
            trackSelector = DefaultTrackSelector(this).apply {
                parameters = parameters.buildUpon().apply {
                    when (prefs.preferredQuality) {
                        "1080" -> setMaxVideoSize(1920, 1080)
                        "4k" -> setMaxVideoSize(3840, 2160)
                        else -> {}
                    }
                }.build()
            }
            val loadControl = DefaultLoadControl.Builder().apply {
                when (prefs.bufferMode) {
                    "low" -> setBufferDurationsMs(2000, 10000, 1000, 2500)
                    "high" -> setBufferDurationsMs(10000, 60000, 5000, 10000)
                    else -> {}
                }
            }.build()
            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .setLoadControl(loadControl)
                .build().also {
                playerView.player = it
                it.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> loadingIndicator.visibility = View.VISIBLE
                            Player.STATE_READY, Player.STATE_ENDED -> loadingIndicator.visibility = View.GONE
                            Player.STATE_IDLE -> loadingIndicator.visibility = View.GONE
                        }
                        runOnUiThread {
                            updateKeepScreenOn()
                            if (prefs.playerType != AppPreferences.PLAYER_EXTERNAL) {
                                playerBottomBar.visibility = when (playbackState) {
                                    Player.STATE_READY, Player.STATE_BUFFERING -> View.VISIBLE
                                    else -> View.GONE
                                }
                                if (playbackState == Player.STATE_READY) updateBottomBarChannelInfo()
                            }
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateKeepScreenOn()
                        runOnUiThread { updatePlayPauseButton() }
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        runOnUiThread { refreshTrackSpinners() }
                    }
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        runOnUiThread {
                            if (rightPanelVisible) updateVideoInfo()
                            if (playerBottomBar.visibility == View.VISIBLE) updateBottomBarChannelInfo()
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        loadingIndicator.visibility = View.GONE
                        val msg = error.cause?.message ?: error.message ?: ""
                        val friendly = if (msg.contains("403") || msg.contains("404")) {
                            getString(R.string.stream_unavailable)
                        } else {
                            getString(R.string.error_playback) + ": $msg"
                        }
                        Toast.makeText(this@MainActivity, friendly, Toast.LENGTH_LONG).show()
                    }
                })
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_player) + ": ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupRecyclerView() {
        adapter = ChannelAdapter(
            channels = emptyList(),
            favorites = prefs.favorites,
            epgData = epgData,
            isGridMode = { prefs.listDisplayMode == "grid" },
            onChannelClick = { ch ->
                playChannel(ch)
                // List stays open - close only via right/back
            },
            onFavoriteClick = { toggleFavorite(it) }
        )
        applyListLayoutManager()
        recyclerView.adapter = adapter
    }

    private fun applyListLayoutManager() {
        recyclerView.layoutManager = if (prefs.listDisplayMode == "grid") {
            GridLayoutManager(this, 3)
        } else {
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        }
        adapter.refreshDisplayMode()
    }

    private fun setupPlaylistSpinner() {
        val customChannelsPlaylist = if (prefs.customChannels.isNotEmpty()) {
            listOf(Playlist(
                getString(R.string.my_channels),
                "custom_channels",
                prefs.customChannels.map { Channel(it.first, it.second, null, null) }
            ))
        } else emptyList()
        allPlaylists = BuiltInPlaylists.getAllPlaylists() + prefs.customPlaylists.map { Playlist(it.first, it.second) } + customChannelsPlaylist
        val names = allPlaylists.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        playlistSpinner.adapter = adapter

        playlistSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val playlist = allPlaylists.getOrNull(position) ?: return
                if (playlist.url == prefs.lastPlaylistUrl && allChannels.isNotEmpty()) return
                loadPlaylist(playlist)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun restoreState() {
        val savedUrl = prefs.lastPlaylistUrl
        if (allPlaylists.isNotEmpty()) {
            if (savedUrl != null) {
                val idx = allPlaylists.indexOfFirst { it.url == savedUrl }
                if (idx >= 0) {
                    playlistSpinner.setSelection(idx, false)
                    loadPlaylist(allPlaylists[idx])
                    return
                }
            }
            playlistSpinner.post { loadPlaylist(allPlaylists[0]) }
        } else {
            emptyState.visibility = View.VISIBLE
        }
    }

    private fun setupCategorySpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf(getString(R.string.all)))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter
        categorySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.lastCategoryIndex = position
                filterChannelsByCategory(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFavoritesButton() {
        updateFavoritesButtonState()
        btnFavorites.setOnClickListener {
            showFavoritesOnly = !showFavoritesOnly
            updateFavoritesButtonState()
            filterChannelsByCategory(categorySpinner.selectedItemPosition)
        }
    }

    private fun updateFavoritesButtonState() {
        btnFavorites.setImageResource(
            if (showFavoritesOnly) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )
        btnFavorites.contentDescription = if (showFavoritesOnly) getString(R.string.show_all) else getString(R.string.favorites)
    }

    private fun loadPlaylist(playlist: Playlist) {
        loadJob?.cancel()
        allChannels = emptyList()
        adapter.updateChannels(emptyList())
        emptyState.visibility = View.VISIBLE
        showFavoritesOnly = false
        updateFavoritesButtonState()

        val url = playlist.url
        if (url == "custom_channels" && playlist.channels.isNotEmpty()) {
            prefs.lastPlaylistUrl = url
            allChannels = playlist.channels
            epgData = emptyMap()
            adapter.updateEpg(epgData)
            updateCategorySpinner(allChannels)
            filterChannelsByCategory(0)
            emptyState.visibility = View.GONE
            return
        }
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.no_playlist_url), Toast.LENGTH_SHORT).show()
            return
        }

        prefs.lastPlaylistUrl = url
        loadingIndicator.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    PlaylistRepository.fetchPlaylist(url)
                }
                loadingIndicator.visibility = View.GONE
                allChannels = result.channels
                epgData = if (result.epgUrl != null) {
                    EpgRepository.fetchEpg(result.epgUrl.split(",").firstOrNull()?.trim())
                } else emptyMap()
                adapter.updateEpg(epgData)
                if (result.channels.isEmpty()) {
                    Toast.makeText(this@MainActivity, getString(R.string.load_failed), Toast.LENGTH_LONG).show()
                } else {
                    updateCategorySpinner(result.channels)
                    val catIdx = prefs.lastCategoryIndex.coerceIn(0, categorySpinner.adapter!!.count - 1)
                    categorySpinner.setSelection(catIdx, false)
                    filterChannelsByCategory(catIdx)
                    emptyState.visibility = View.GONE
                    prefs.lastChannelUrl?.let { lastUrl ->
                        allChannels.find { it.url == lastUrl }?.let { playChannel(it) }
                    }
                }
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "Load playlist error", e)
                ErrorLogger.logException(this@MainActivity, e)
                loadingIndicator.visibility = View.GONE
                Toast.makeText(this@MainActivity, getString(R.string.error) + ": ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateCategorySpinner(channels: List<Channel>) {
        val groups = channels.mapNotNull { it.group }.distinct().sorted()
        val categories = listOf(getString(R.string.all)) + groups
        val catAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = catAdapter
    }

    private fun filterChannelsByCategory(@Suppress("UNUSED_PARAMETER") categoryIndex: Int) {
        applyFilters()
    }

    private fun toggleFavorite(channel: Channel) {
        if (prefs.isFavorite(channel.url)) {
            prefs.removeFavorite(channel.url)
        } else {
            prefs.addFavorite(channel.url)
        }
        adapter.updateFavorites(prefs.favorites)
    }

    private fun playChannel(channel: Channel) {
        prefs.lastChannelUrl = channel.url
        emptyState.visibility = View.GONE
        when (prefs.playerType) {
            AppPreferences.PLAYER_EXTERNAL -> {
                playerBottomBar.visibility = View.GONE
                playExternal(channel)
            }
            else -> {
                updateBottomBarChannelInfo()
                playerBottomBar.visibility = View.VISIBLE
                playInternal(channel)
            }
        }
    }

    private fun playInternal(channel: Channel) {
        try {
            player?.apply {
                setMediaItem(MediaItem.fromUri(channel.url))
                prepare()
                playWhenReady = true
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error) + ": ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun playExternal(channel: Channel) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(channel.url), "video/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val chooser = Intent.createChooser(intent, getString(R.string.open_with))
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(chooser)
            } else {
                Toast.makeText(this, getString(R.string.no_player_app), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error) + ": ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val customChannelsPlaylist = if (prefs.customChannels.isNotEmpty()) {
            listOf(Playlist(getString(R.string.my_channels), "custom_channels",
                prefs.customChannels.map { Channel(it.first, it.second, null, null) }))
        } else emptyList()
        allPlaylists = BuiltInPlaylists.getAllPlaylists() + prefs.customPlaylists.map { Playlist(it.first, it.second) } + customChannelsPlaylist
        val names = allPlaylists.map { it.name }
        val plAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        plAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        playlistSpinner.onItemSelectedListener = null
        playlistSpinner.adapter = plAdapter
        val savedUrl = prefs.lastPlaylistUrl
        val idx = if (savedUrl != null) allPlaylists.indexOfFirst { it.url == savedUrl } else -1
        if (idx >= 0) {
            playlistSpinner.setSelection(idx, false)
            if (allChannels.isNotEmpty()) {
                val adapter = categorySpinner.adapter
                val catIdx = if (adapter != null) prefs.lastCategoryIndex.coerceIn(0, adapter.count - 1) else 0
                filterChannelsByCategory(catIdx)
            }
        }
        playlistSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val playlist = allPlaylists.getOrNull(position) ?: return
                if (playlist.url == prefs.lastPlaylistUrl && allChannels.isNotEmpty()) return
                loadPlaylist(playlist)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        applyFullscreen(prefs.isFullscreen)
        updatePlayerQuality()
        applyListLayoutManager()
        btnFullscreen.setImageResource(
            if (prefs.isFullscreen) R.drawable.ic_fullscreen_exit
            else R.drawable.ic_fullscreen
        )
        leftSideContainer.visibility = if (leftSideVisible) View.VISIBLE else View.GONE
        settingsPanel.visibility = if (settingsPanelVisible) View.VISIBLE else View.GONE
        rightSettingsPanel.visibility = if (rightPanelVisible) View.VISIBLE else View.GONE
        if (prefs.playerType == AppPreferences.PLAYER_EXTERNAL) {
            playerBottomBar.visibility = View.GONE
        } else {
            val p = player
            playerBottomBar.visibility = if (p != null && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)) {
                updateBottomBarChannelInfo()
                updatePlayPauseButton()
                View.VISIBLE
            } else View.GONE
        }
        applyTimeDisplayPosition()
        // Reattach player after wake - fixes video freeze when screen was off
        player?.let { p ->
            playerView.player = p
            if (p.playWhenReady) {
                p.play()
            }
            updateKeepScreenOn()
        }
    }

    private fun updatePlayerQuality() {
        (player?.trackSelector as? DefaultTrackSelector)?.let { selector ->
            selector.parameters = selector.parameters.buildUpon().apply {
                when (prefs.preferredQuality) {
                    "1080" -> setMaxVideoSize(1920, 1080)
                    "4k" -> setMaxVideoSize(3840, 2160)
                    else -> {}
                }
            }.build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoHide()
        loadJob?.cancel()
        player?.release()
        player = null
    }
}
