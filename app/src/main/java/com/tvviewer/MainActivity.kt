package com.tvviewer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : BaseActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var prefs: AppPreferences
    private var currentFragmentTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPreferences(this)
        applyOrientation()

        bottomNav = findViewById(R.id.bottomNavigation)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_playlists -> showFragment(PlaylistsFragment.TAG, ::PlaylistsFragment)
                R.id.nav_channels -> showFragment(ChannelsFragment.TAG, ::ChannelsFragment)
                R.id.nav_tv_guide -> showFragment(TvGuideFragment.TAG, ::TvGuideFragment)
                R.id.nav_favorites -> showFragment(FavoritesFragment.TAG, ::FavoritesFragment)
                R.id.nav_settings -> showFragment(SettingsFragment.TAG, ::SettingsFragment)
                else -> false
            }
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_playlists

            // Autoplay last channel if enabled
            if (prefs.autoplayLast) {
                val lastUrl = prefs.lastChannelUrl
                if (!lastUrl.isNullOrBlank()) {
                    val channel = ChannelDataHolder.allChannels.find { it.url == lastUrl }
                    if (channel != null) {
                        val index = ChannelDataHolder.allChannels.indexOf(channel)
                        ChannelDataHolder.currentChannelIndex = index
                        val intent = Intent(this, PlayerActivity::class.java).apply {
                            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                            putExtra(PlayerActivity.EXTRA_CHANNEL_URL, channel.url)
                            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, index)
                        }
                        startActivity(intent)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyOrientation()
    }

    private fun applyOrientation() {
        requestedOrientation = when (prefs.screenOrientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun showFragment(tag: String, factory: () -> Fragment): Boolean {
        if (tag == currentFragmentTag) return true
        currentFragmentTag = tag

        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(tag)
        val transaction = fm.beginTransaction()

        // Hide all current fragments
        fm.fragments.forEach { transaction.hide(it) }

        if (existing != null) {
            transaction.show(existing)
        } else {
            transaction.add(R.id.fragmentContainer, factory(), tag)
        }

        transaction.commitAllowingStateLoss()
        return true
    }

    fun switchToChannels(playlistName: String, playlistUrl: String) {
        ChannelDataHolder.pendingPlaylistName = playlistName
        ChannelDataHolder.pendingPlaylistUrl = playlistUrl
        bottomNav.selectedItemId = R.id.nav_channels
    }

    // D-pad support for navigation
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Let the system handle focus navigation
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
