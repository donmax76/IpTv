package com.tvviewer

import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class AddPlaylistActivity : BaseActivity() {

    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_playlist)

        prefs = AppPreferences(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val nameEdit = findViewById<TextInputEditText>(R.id.editPlaylistName)
        val urlEdit = findViewById<TextInputEditText>(R.id.editPlaylistUrl)
        val btnAdd = findViewById<MaterialButton>(R.id.btnAdd)

        btnAdd.setOnClickListener {
            val name = nameEdit.text.toString().trim()
            val url = urlEdit.text.toString().trim()

            if (name.isEmpty() || url.isEmpty()) {
                Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.addCustomPlaylist(name, url)
            Toast.makeText(this, R.string.playlist_added, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }

        // Built-in playlists
        val builtinRecycler = findViewById<RecyclerView>(R.id.builtinPlaylistsRecyclerView)
        builtinRecycler.layoutManager = LinearLayoutManager(this)

        val builtinPlaylists = BuiltInPlaylists.getAllPlaylists()
        builtinRecycler.adapter = BuiltinPlaylistAdapter(builtinPlaylists) { playlist ->
            prefs.addCustomPlaylist(playlist.name, playlist.url ?: "")
            Toast.makeText(this, R.string.playlist_added, Toast.LENGTH_SHORT).show()
        }
    }
}
