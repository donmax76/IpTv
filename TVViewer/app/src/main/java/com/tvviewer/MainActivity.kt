package com.tvviewer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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

    private var player: ExoPlayer? = null
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

            try {
                val toolbar = findViewById<Toolbar>(R.id.toolbar)
                setSupportActionBar(toolbar)
            } catch (e: Exception) { Log.e(TAG, "Toolbar error", e) }

            setupPlayer()
            setupRecyclerView()
            setupCategorySpinner()
            setupFavoritesButton()
            setupPlaylistSpinner()
            restoreState()
            Log.d(TAG, "onCreate completed")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error", e)
            ErrorLogger.logException(this, e)
            Toast.makeText(this, getString(R.string.error_start) + ": ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            R.id.action_fullscreen -> {
                toggleFullscreen()
                return true
            }
            R.id.action_tv_guide -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.tv_guide)
                    .setMessage(R.string.tv_guide_unavailable)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toggleFullscreen() {
        prefs.isFullscreen = !prefs.isFullscreen
        applyFullscreen(prefs.isFullscreen)
        invalidateOptionsMenu()
    }

    private fun applyFullscreen(fullscreen: Boolean) {
        if (fullscreen) {
            headerPanel.visibility = View.GONE
            channelPanel.visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        } else {
            headerPanel.visibility = View.VISIBLE
            channelPanel.visibility = View.VISIBLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(android.view.WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
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
                        ErrorLogger.logException(this@MainActivity, error)
                        loadingIndicator.visibility = View.GONE
                        Toast.makeText(this@MainActivity, getString(R.string.error_playback) + ": ${error.message}", Toast.LENGTH_LONG).show()
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
            onChannelClick = { playChannel(it) },
            onFavoriteClick = { toggleFavorite(it) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.adapter = adapter
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

    private fun filterChannelsByCategory(categoryIndex: Int) {
        var filtered = allChannels
        if (showFavoritesOnly) {
            filtered = filtered.filter { prefs.isFavorite(it.url) }
        }
        val spinnerAdapter = categorySpinner.adapter
        if (categoryIndex > 0 && spinnerAdapter != null && categoryIndex < spinnerAdapter.count) {
            val category = categorySpinner.getItemAtPosition(categoryIndex) as? String ?: ""
            filtered = filtered.filter { it.group == category }
        }
        adapter.updateChannels(filtered)
        adapter.updateFavorites(prefs.favorites)
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
        // Refresh playlists (custom may have changed), restore selection
        val customChannelsPlaylist = if (prefs.customChannels.isNotEmpty()) {
            listOf(Playlist(getString(R.string.my_channels), "custom_channels",
                prefs.customChannels.map { Channel(it.first, it.second, null, null) }))
        } else emptyList()
        allPlaylists = BuiltInPlaylists.getAllPlaylists() + prefs.customPlaylists.map { Playlist(it.first, it.second) } + customChannelsPlaylist
        val names = allPlaylists.map { it.name }
        val plAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        plAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        playlistSpinner.adapter = plAdapter
        // Restore playlist selection
        prefs.lastPlaylistUrl?.let { url ->
            val idx = allPlaylists.indexOfFirst { it.url == url }
            if (idx >= 0 && allChannels.isNotEmpty()) {
                playlistSpinner.setSelection(idx, false)
                val adapter = categorySpinner.adapter
                val catIdx = if (adapter != null) prefs.lastCategoryIndex.coerceIn(0, adapter.count - 1) else 0
                filterChannelsByCategory(catIdx)
            }
        }
        applyFullscreen(prefs.isFullscreen)
        updatePlayerQuality()
        invalidateOptionsMenu()
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

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        menu.findItem(R.id.action_fullscreen)?.setTitle(
            if (prefs.isFullscreen) getString(R.string.exit_fullscreen) else getString(R.string.fullscreen)
        )
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
        player?.release()
        player = null
    }
}
