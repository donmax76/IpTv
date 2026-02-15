package com.tvviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
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

        setupPlayerSpinner()
        setupLanguageSpinner()
        setupCustomPlaylists()
        setupAddPlaylist()
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
}
