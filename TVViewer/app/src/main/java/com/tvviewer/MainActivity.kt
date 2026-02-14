package com.tvviewer

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyState: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var playlistSpinner: android.widget.Spinner

    private var player: ExoPlayer? = null
    private lateinit var adapter: ChannelAdapter
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyState = findViewById(R.id.emptyState)
        recyclerView = findViewById(R.id.recyclerView)
        playlistSpinner = findViewById(R.id.playlistSpinner)

        setupPlayer()
        setupRecyclerView()
        setupPlaylistSpinner()
    }

    private fun setupPlayer() {
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
            })
        }
    }

    private fun setupRecyclerView() {
        adapter = ChannelAdapter(emptyList()) { channel ->
            playChannel(channel)
        }
        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.adapter = adapter
    }

    private fun setupPlaylistSpinner() {
        val playlistNames = BuiltInPlaylists.playlists.map { it.name }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, playlistNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        playlistSpinner.adapter = spinnerAdapter

        playlistSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadPlaylist(BuiltInPlaylists.playlists[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (BuiltInPlaylists.playlists.isNotEmpty()) {
            loadPlaylist(BuiltInPlaylists.playlists[0])
        }
    }

    private fun loadPlaylist(playlist: Playlist) {
        loadJob?.cancel()
        adapter.updateChannels(emptyList())
        emptyState.visibility = View.VISIBLE

        val url = playlist.url
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, "Нет URL плейлиста", Toast.LENGTH_SHORT).show()
            return
        }

        loadingIndicator.visibility = View.VISIBLE
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val channels = withContext(Dispatchers.IO) {
                    PlaylistRepository.fetchPlaylist(url)
                }
                loadingIndicator.visibility = View.GONE
                if (channels.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Не удалось загрузить каналы", Toast.LENGTH_SHORT).show()
                } else {
                    adapter.updateChannels(channels)
                    emptyState.visibility = View.GONE
                }
            } catch (e: Exception) {
                loadingIndicator.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playChannel(channel: Channel) {
        emptyState.visibility = View.GONE
        player?.apply {
            setMediaItem(MediaItem.fromUri(channel.url))
            prepare()
            playWhenReady = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
        player?.release()
        player = null
    }
}
