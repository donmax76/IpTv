package com.tvviewer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class PlaylistsFragment : Fragment() {

    companion object {
        const val TAG = "PlaylistsFragment"
    }

    private lateinit var prefs: AppPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: PlaylistAdapter

    private val addPlaylistLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPlaylists()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_playlists, container, false)
    }

    private var autoLoaded = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        recyclerView = view.findViewById(R.id.playlistsRecyclerView)
        emptyText = view.findViewById(R.id.emptyText)
        progressBar = view.findViewById(R.id.progressBar)

        val fab = view.findViewById<FloatingActionButton>(R.id.fabAddPlaylist)
        fab.setOnClickListener {
            addPlaylistLauncher.launch(Intent(requireContext(), AddPlaylistActivity::class.java))
        }

        adapter = PlaylistAdapter(
            playlists = emptyList(),
            onPlaylistClick = { playlist ->
                (activity as? MainActivity)?.switchToChannels(playlist.first, playlist.second)
            },
            onDeleteClick = { index ->
                prefs.removeCustomPlaylist(index)
                refreshPlaylists()
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        refreshPlaylists()

        // Auto-load last playlist if channels are empty
        if (!autoLoaded && ChannelDataHolder.allChannels.isEmpty()) {
            autoLoaded = true
            val lastUrl = prefs.lastPlaylistUrl
            val lastName = prefs.lastPlaylistName
            if (!lastUrl.isNullOrBlank()) {
                (activity as? MainActivity)?.switchToChannels(lastName ?: "", lastUrl)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPlaylists()
    }

    private fun refreshPlaylists() {
        val playlists = prefs.customPlaylists
        val builtIn = BuiltInPlaylists.getAllPlaylists().map { it.name to (it.url ?: "") }
        val allPlaylists = playlists + builtIn

        adapter.updatePlaylists(allPlaylists, playlists.size)

        emptyText.visibility = if (allPlaylists.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (allPlaylists.isEmpty()) View.GONE else View.VISIBLE
    }
}
