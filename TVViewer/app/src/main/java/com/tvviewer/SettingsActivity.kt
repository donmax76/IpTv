package com.tvviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class SettingsActivity : BaseActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var playerSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var customList: ListView
    private lateinit var addPlaylistName: EditText
    private lateinit var addPlaylistUrl: EditText
    private lateinit var crashFirebaseId: EditText
    private lateinit var crashWebhookUrl: EditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var bufferSpinner: Spinner
    private lateinit var listDisplaySpinner: Spinner
    private lateinit var listAutohideSpinner: Spinner
    private lateinit var addChannelName: EditText
    private lateinit var addChannelUrl: EditText
    private lateinit var customChannelsList: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = AppPreferences(this)

        findViewById<Toolbar>(R.id.toolbar)?.apply {
            setNavigationOnClickListener { finish() }
        }

        playerSpinner = findViewById(R.id.playerSpinner)
        languageSpinner = findViewById(R.id.languageSpinner)
        customList = findViewById(R.id.customPlaylistsList)
        addPlaylistName = findViewById(R.id.addPlaylistName)
        addPlaylistUrl = findViewById(R.id.addPlaylistUrl)
        crashFirebaseId = findViewById(R.id.crashFirebaseId)
        crashWebhookUrl = findViewById(R.id.crashWebhookUrl)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        bufferSpinner = findViewById(R.id.bufferSpinner)
        listDisplaySpinner = findViewById(R.id.listDisplaySpinner)
        listAutohideSpinner = findViewById(R.id.listAutohideSpinner)
        addChannelName = findViewById(R.id.addChannelName)
        addChannelUrl = findViewById(R.id.addChannelUrl)
        customChannelsList = findViewById(R.id.customChannelsList)

        setupPlayerSpinner()
        setupQualitySpinner()
        setupBufferSpinner()
        setupListDisplaySpinner()
        setupListAutohideSpinner()
        setupCustomChannels()
        setupLanguageSpinner()
        setupCustomPlaylists()
        setupAddPlaylist()
        setupAddMultiplePlaylists()
        setupErrorLog()
        setupCrashReporting()
    }

    private fun setupErrorLog() {
        findViewById<android.widget.Button>(R.id.btnCopyErrors).setOnClickListener {
            val text = ErrorLogger.getErrorContent(this)
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.no_errors_saved, Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TVViewer Errors", text))
                Toast.makeText(this, R.string.copied_send_to_dev, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<android.widget.Button>(R.id.btnShareErrors).setOnClickListener {
            val text = ErrorLogger.getErrorContent(this)
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.no_errors_saved, Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_SUBJECT, "TVViewer ошибки")
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_errors)))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            prefs.crashReportFirebaseId = crashFirebaseId.text.toString().trim().ifEmpty { null }
            prefs.crashReportUrl = crashWebhookUrl.text.toString().trim().ifEmpty { null }
        } catch (_: Exception) {}
    }

    private fun setupCrashReporting() {
        try {
            crashFirebaseId.setText(prefs.crashReportFirebaseId ?: "")
            crashWebhookUrl.setText(prefs.crashReportUrl ?: "")
            findViewById<Button>(R.id.btnTestCrash).setOnClickListener {
                throw RuntimeException("Тестовая ошибка - проверка отправки")
            }
        } catch (e: Exception) {
            android.util.Log.e("TVViewer", "setupCrashReporting error", e)
        }
    }

    private fun setupPlayerSpinner() {
        val players = listOf(
            getString(R.string.player_internal),
            getString(R.string.player_external)
        )
        playerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, players)
        playerSpinner.setSelection(if (prefs.playerType == AppPreferences.PLAYER_EXTERNAL) 1 else 0)
        playerSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.playerType = if (pos == 1) AppPreferences.PLAYER_EXTERNAL else AppPreferences.PLAYER_INTERNAL
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
    }

    private fun setupQualitySpinner() {
        val qualities = listOf(
            getString(R.string.quality_auto),
            getString(R.string.quality_1080),
            getString(R.string.quality_4k)
        )
        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualities)
        val idx = when (prefs.preferredQuality) {
            "1080" -> 1
            "4k" -> 2
            else -> 0
        }
        qualitySpinner.setSelection(idx)
        qualitySpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.preferredQuality = when (pos) {
                    1 -> "1080"
                    2 -> "4k"
                    else -> "auto"
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
    }

    private fun setupListDisplaySpinner() {
        val modes = listOf(getString(R.string.list_display_list), getString(R.string.list_display_grid))
        listDisplaySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        listDisplaySpinner.setSelection(if (prefs.listDisplayMode == "grid") 1 else 0)
        listDisplaySpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.listDisplayMode = if (pos == 1) "grid" else "list"
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
    }

    private fun setupListAutohideSpinner() {
        val seconds = (2..30).map { "$it" }
        listAutohideSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, seconds)
        val current = prefs.channelListAutoHideSeconds
        listAutohideSpinner.setSelection((current - 2).coerceIn(0, seconds.size - 1))
        listAutohideSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.channelListAutoHideSeconds = pos + 2
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
    }

    private fun setupBufferSpinner() {
        val modes = listOf(
            getString(R.string.buffer_low),
            getString(R.string.buffer_normal),
            getString(R.string.buffer_high)
        )
        bufferSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        val idx = when (prefs.bufferMode) {
            "low" -> 0
            "high" -> 2
            else -> 1
        }
        bufferSpinner.setSelection(idx)
        bufferSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.bufferMode = when (pos) {
                    0 -> "low"
                    2 -> "high"
                    else -> "normal"
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
    }

    private fun setupCustomChannels() {
        refreshCustomChannelsList()
        findViewById<Button>(R.id.btnAddChannel).setOnClickListener {
            val name = addChannelName.text.toString().trim()
            val url = addChannelUrl.text.toString().trim()
            if (name.isEmpty() || url.isEmpty()) {
                Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!url.startsWith("http")) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.addCustomChannel(name, url)
            addChannelName.text.clear()
            addChannelUrl.text.clear()
            refreshCustomChannelsList()
            Toast.makeText(this, R.string.playlist_added, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnAddMedeniyyet).setOnClickListener {
            addChannelName.setText("Mədəniyyət TV")
            addChannelUrl.setText("https://streaming.mediaculture.az/mədəniyyət/stream.m3u8")
            Toast.makeText(this, R.string.medeniyyet_url_hint, Toast.LENGTH_LONG).show()
        }
        customChannelsList.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(this)
                .setTitle(R.string.remove_playlist)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.removeCustomChannel(position)
                    refreshCustomChannelsList()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    private fun refreshCustomChannelsList() {
        val channels = prefs.customChannels.map { "${it.first}\n${it.second}" }
        customChannelsList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, channels)
    }

    private fun setupLanguageSpinner() {
        val languages = LocaleHelper.supportedLanguages.map { it.second }
        languageSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        val currentIndex = LocaleHelper.supportedLanguages.indexOfFirst { it.first == prefs.language }
        languageSpinner.setSelection(if (currentIndex >= 0) currentIndex else 0)
        languageSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val code = LocaleHelper.supportedLanguages[pos].first
                if (prefs.language != code) {
                    prefs.language = code
                    recreate()
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
    }

    private fun setupCustomPlaylists() {
        refreshCustomList()
        customList.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(this)
                .setTitle(R.string.remove_playlist)
                .setMessage(R.string.remove_playlist_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.removeCustomPlaylist(position)
                    refreshCustomList()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    private fun refreshCustomList() {
        val playlists = prefs.customPlaylists.map { "${it.first}\n${it.second}" }
        customList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, playlists)
    }

    private fun setupAddPlaylist() {
        findViewById<Button>(R.id.btnAddPlaylist).setOnClickListener {
            val name = addPlaylistName.text.toString().trim()
            val url = addPlaylistUrl.text.toString().trim()
            if (name.isEmpty() || url.isEmpty()) {
                Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!url.startsWith("http")) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.addCustomPlaylist(name, url)
            addPlaylistName.text.clear()
            addPlaylistUrl.text.clear()
            refreshCustomList()
            Toast.makeText(this, R.string.playlist_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAddMultiplePlaylists() {
        findViewById<Button>(R.id.btnAddMultiplePlaylists).setOnClickListener {
            val input = EditText(this).apply {
                setHint(R.string.add_multiple_hint)
                minLines = 6
                setPadding(48, 32, 48, 32)
                setBackgroundResource(R.drawable.spinner_background)
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF888888.toInt())
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(input)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.add_multiple_playlists)
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val text = input.text.toString()
                    val items = parseMultiplePlaylists(text)
                    if (items.isEmpty()) {
                        Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
                    } else {
                        prefs.addCustomPlaylists(items)
                        refreshCustomList()
                        Toast.makeText(this, getString(R.string.playlist_added) + " x${items.size}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun parseMultiplePlaylists(text: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        text.lines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            val (name, url) = when {
                trimmed.contains("|") -> {
                    val parts = trimmed.split("|", limit = 2)
                    parts[0].trim() to parts[1].trim()
                }
                trimmed.contains(" - ") -> {
                    val parts = trimmed.split(" - ", limit = 2)
                    parts[0].trim() to parts[1].trim()
                }
                trimmed.startsWith("http") -> {
                    val nameFromUrl = try {
                        java.net.URL(trimmed).host?.replace("www.", "")?.substringBefore(".") ?: "Playlist ${index + 1}"
                    } catch (_: Exception) { "Playlist ${index + 1}" }
                    nameFromUrl to trimmed
                }
                else -> return@forEachIndexed
            }
            if (url.startsWith("http")) {
                result.add(name.ifEmpty { "Playlist ${index + 1}" } to url)
            }
        }
        return result
    }
}
