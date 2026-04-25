package com.tvviewer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

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

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        importPlaylistFromUri(uri)
    }

    private fun importPlaylistFromUri(uri: android.net.Uri) {
        try {
            val ctx = requireContext()
            val name = queryFileName(uri) ?: "Imported.m3u"
            val content = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw java.io.IOException("empty stream")
            // Persist into app filesDir for reuse, then store file:// URL.
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val playlistDir = java.io.File(ctx.filesDir, "imported_playlists").apply { mkdirs() }
            val savedFile = java.io.File(playlistDir, "${System.currentTimeMillis()}_$safeName")
            savedFile.writeBytes(content)
            val url = "file://${savedFile.absolutePath}"
            val displayName = name.removeSuffix(".m3u8").removeSuffix(".m3u")
            prefs.addCustomPlaylist(displayName, url)
            Toast.makeText(ctx, R.string.playlist_added, Toast.LENGTH_SHORT).show()
            refreshPlaylists()
        } catch (e: Exception) {
            ErrorLogger.logException(requireContext(), e)
            Toast.makeText(requireContext(), R.string.load_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryFileName(uri: android.net.Uri): String? {
        var name: String? = null
        try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
            }
        } catch (_: Exception) {}
        return name ?: uri.lastPathSegment
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

        val btnAdd = view.findViewById<View>(R.id.btnAddPlaylist)
        btnAdd?.setOnClickListener {
            val opts = arrayOf(
                getString(R.string.add_url),
                getString(R.string.import_file)
            )
            android.app.AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.add_playlist)
                .setItems(opts) { _, which ->
                    when (which) {
                        0 -> addPlaylistLauncher.launch(Intent(requireContext(), AddPlaylistActivity::class.java))
                        1 -> importFileLauncher.launch(arrayOf("audio/*", "application/octet-stream", "text/*", "*/*"))
                    }
                }
                .show()
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
