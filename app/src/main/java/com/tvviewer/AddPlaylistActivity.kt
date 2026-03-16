package com.tvviewer

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class AddPlaylistActivity : BaseActivity() {

    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_playlist)

        prefs = AppPreferences(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        setupTabs()
        setupM3uTab()
        setupXtreamTab()
    }

    private fun setupTabs() {
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val m3uContent = findViewById<View>(R.id.m3uContent)
        val xtreamContent = findViewById<View>(R.id.xtreamContent)

        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_m3u))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_xtream))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        m3uContent.visibility = View.VISIBLE
                        xtreamContent.visibility = View.GONE
                    }
                    1 -> {
                        m3uContent.visibility = View.GONE
                        xtreamContent.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupM3uTab() {
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

    private fun setupXtreamTab() {
        val serverEdit = findViewById<TextInputEditText>(R.id.editXtreamServer)
        val usernameEdit = findViewById<TextInputEditText>(R.id.editXtreamUsername)
        val passwordEdit = findViewById<TextInputEditText>(R.id.editXtreamPassword)
        val btnLogin = findViewById<MaterialButton>(R.id.btnXtreamLogin)
        val progress = findViewById<ProgressBar>(R.id.xtreamProgress)
        val status = findViewById<TextView>(R.id.xtreamStatus)

        btnLogin.setOnClickListener {
            val server = serverEdit.text.toString().trim()
            val username = usernameEdit.text.toString().trim()
            val password = passwordEdit.text.toString().trim()

            if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            progress.visibility = View.VISIBLE
            status.visibility = View.GONE

            lifecycleScope.launch {
                val result = XtreamApi.authenticate(server, username, password)
                result.fold(
                    onSuccess = { info ->
                        val m3uUrl = XtreamApi.buildM3uUrl(server, username, password)
                        val playlistName = "Xtream: $username"
                        prefs.addCustomPlaylist(playlistName, m3uUrl)

                        progress.visibility = View.GONE
                        status.visibility = View.VISIBLE
                        status.text = getString(R.string.xtream_connected)
                        status.setTextColor(getColor(R.color.accent_green))

                        Toast.makeText(this@AddPlaylistActivity, R.string.playlist_added, Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    },
                    onFailure = { error ->
                        progress.visibility = View.GONE
                        status.visibility = View.VISIBLE
                        status.text = getString(R.string.xtream_error, error.message ?: "Unknown error")
                        status.setTextColor(getColor(R.color.accent_red))
                        btnLogin.isEnabled = true
                    }
                )
            }
        }
    }
}
