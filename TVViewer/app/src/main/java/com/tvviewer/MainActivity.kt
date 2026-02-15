package com.tvviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private var player: ExoPlayer? = null
    private lateinit var adapter: ChannelAdapter
    private var loadJob: Job? = null
    private var allChannels: List<Channel> = emptyList()
    private var showFavoritesOnly = false

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

            try {
                val toolbar = findViewById<Toolbar>(R.id.toolbar)
                setSupportActionBar(toolbar)
            } catch (e: Exception) { Log.e(TAG, "Toolbar error", e) }

            setupPlayer()
            setupRecyclerView()
            setupCategorySpinner()
            setupFavoritesButton()
            setupPlaylistSpinner()
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
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupPlayer() {
        try {
            player = ExoPlayer.Builder(this).build().also {
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
        val allPlaylists = BuiltInPlaylists.getAllPlaylists() + prefs.customPlaylists.map { Playlist(it.first, it.second) }
        val names = allPlaylists.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        playlistSpinner.adapter = adapter

        playlistSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadPlaylist(allPlaylists[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (allPlaylists.isNotEmpty()) {
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
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.no_playlist_url), Toast.LENGTH_SHORT).show()
            return
        }

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
                    filterChannelsByCategory(0)
                    emptyState.visibility = View.GONE
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
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
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        categorySpinner.setSelection(0)
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
        // Refresh playlist list when returning from settings (custom playlists may have changed)
        val allPlaylists = BuiltInPlaylists.getAllPlaylists() + prefs.customPlaylists.map { Playlist(it.first, it.second) }
        val names = allPlaylists.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        playlistSpinner.adapter = adapter
    }

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
        player?.release()
        player = null
    }
}
