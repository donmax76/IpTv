package com.tvviewer

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
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private lateinit var headerPanel: LinearLayout
    private lateinit var channelPanel: LinearLayout
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnAspectRatio: ImageButton
    private lateinit var playerBottomBar: View
    private lateinit var searchChannels: EditText

    private var player: ExoPlayer? = null
    private var channelsPanelVisible = true
    private var headerPanelVisible = true
    private var aspectRatioMode = 0 // 0=fit, 1=16:9, 2=4:3, 3=fill
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null
    private lateinit var adapter: ChannelAdapter
    private var loadJob: Job? = null
    private var allChannels: List<Channel> = emptyList()
    private var showFavoritesOnly = false
    private var allPlaylists: List<Playlist> = emptyList()

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
            headerPanel = findViewById(R.id.headerPanel)
            channelPanel = findViewById(R.id.channelPanel)
            btnFullscreen = findViewById(R.id.btnFullscreen)
            btnAspectRatio = findViewById(R.id.btnAspectRatio)
            playerBottomBar = findViewById(R.id.playerBottomBar)
            searchChannels = findViewById(R.id.searchChannels)

            findViewById<View>(R.id.rightEdgeZone).setOnClickListener { showHeaderPanelWithAutoHide() }
            findViewById<View>(R.id.tapOverlay).setOnClickListener {
                if (prefs.isFullscreen) {
                    showFullscreenControlsTemporarily()
                } else {
                    toggleChannelsPanel()
                }
            }
            findViewById<View>(R.id.leftEdgeZone).setOnClickListener { showChannelsPanelWithAutoHide() }
            setupPlayer()
            setupChannelPanelToggle()
            setupSearch()
            setupPlayerOverlay()
            setupMenuButton()
            setupRecyclerView()
            setupCategorySpinner()
            setupFavoritesButton()
            setupPlaylistSpinner()
            setupBackPress()
            restoreState()
            Log.d(TAG, "onCreate completed")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error", e)
            ErrorLogger.logException(this, e)
            Toast.makeText(this, getString(R.string.error_start) + ": ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupMenuButton() {
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menuInflater.inflate(R.menu.main_menu, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_settings -> {
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                            true
                        }
                        R.id.action_tv_guide -> {
                            showTvGuide()
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }
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
    }

    private fun cycleAspectRatio() {
        aspectRatioMode = (aspectRatioMode + 1) % 4
        applyAspectRatio()
        val modes = listOf(R.string.aspect_fit, R.string.aspect_16_9, R.string.aspect_4_3, R.string.aspect_fill)
        Toast.makeText(this, getString(modes[aspectRatioMode]), Toast.LENGTH_SHORT).show()
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

    private fun setupChannelPanelToggle() {
        findViewById<ImageButton>(R.id.btnHideChannels).setOnClickListener {
            cancelAutoHide()
            hideChannelsPanel()
        }
        findViewById<ImageButton>(R.id.btnHideHeader).setOnClickListener {
            cancelAutoHide()
            hideHeaderPanel()
        }
    }

    private fun toggleChannelsPanel() {
        channelsPanelVisible = !channelsPanelVisible
        channelPanel.visibility = if (channelsPanelVisible) View.VISIBLE else View.GONE
    }

    private fun hideChannelsPanel() {
        channelsPanelVisible = false
        channelPanel.visibility = View.GONE
    }

    private fun showChannelsPanel() {
        cancelAutoHide()
        channelsPanelVisible = true
        channelPanel.visibility = View.VISIBLE
    }

    private fun hideHeaderPanel() {
        headerPanelVisible = false
        headerPanel.visibility = View.GONE
    }

    private fun showHeaderPanel() {
        cancelAutoHide()
        headerPanelVisible = true
        headerPanel.visibility = View.VISIBLE
    }

    private fun showHeaderPanelWithAutoHide() {
        showHeaderPanel()
        scheduleAutoHide { hideHeaderPanel() }
    }

    private fun showChannelsPanelWithAutoHide() {
        showChannelsPanel()
        scheduleAutoHide { hideChannelsPanel() }
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
                if (!channelsPanelVisible) {
                    showChannelsPanelWithAutoHide()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!headerPanelVisible) {
                    showHeaderPanelWithAutoHide()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (prefs.isFullscreen) {
                    toggleFullscreen()
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                if (!headerPanelVisible) {
                    showHeaderPanelWithAutoHide()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (prefs.isFullscreen) {
                    toggleFullscreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showFullscreenControlsTemporarily() {
        cancelAutoHide()
        playerBottomBar.visibility = View.VISIBLE
        autoHideRunnable = Runnable { playerBottomBar.visibility = View.GONE }
        autoHideHandler.postDelayed(autoHideRunnable!!, 3000L)
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
        adapter.updateChannels(filtered)
        adapter.updateFavorites(prefs.favorites)
    }

    private fun applyFullscreen(fullscreen: Boolean) {
        if (fullscreen) {
            headerPanel.visibility = View.GONE
            channelPanel.visibility = View.GONE
            playerBottomBar.visibility = View.GONE
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
            headerPanel.visibility = if (headerPanelVisible) View.VISIBLE else View.GONE
            playerBottomBar.visibility = View.VISIBLE
            channelPanel.visibility = if (channelsPanelVisible) View.VISIBLE else View.GONE
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
            val trackSelector = DefaultTrackSelector(this).apply {
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
                .setTrackSelector(trackSelector)
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
            isGridMode = { prefs.listDisplayMode == "grid" },
            onChannelClick = { ch ->
                playChannel(ch)
                scheduleAutoHide { hideChannelsPanel() }
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
                val channels = withContext(Dispatchers.IO) {
                    PlaylistRepository.fetchPlaylist(url)
                }
                loadingIndicator.visibility = View.GONE
                allChannels = channels
                if (channels.isEmpty()) {
                    Toast.makeText(this@MainActivity, getString(R.string.load_failed), Toast.LENGTH_LONG).show()
                } else {
                    updateCategorySpinner(channels)
                    val catIdx = prefs.lastCategoryIndex.coerceIn(0, categorySpinner.adapter!!.count - 1)
                    categorySpinner.setSelection(catIdx, false)
                    filterChannelsByCategory(catIdx)
                    emptyState.visibility = View.GONE
                    prefs.lastChannelUrl?.let { url ->
                        allChannels.find { it.url == url }?.let { playChannel(it) }
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
            AppPreferences.PLAYER_EXTERNAL -> playExternal(channel)
            else -> playInternal(channel)
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
        if (channelsPanelVisible) {
            channelPanel.visibility = View.VISIBLE
        } else {
            channelPanel.visibility = View.GONE
        }
        if (headerPanelVisible) {
            headerPanel.visibility = View.VISIBLE
        } else {
            headerPanel.visibility = View.GONE
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
