package com.tvviewer

import android.os.Bundle
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : BaseActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private var currentFragmentTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottomNavigation)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_playlists -> showFragment(PlaylistsFragment.TAG, ::PlaylistsFragment)
                R.id.nav_channels -> showFragment(ChannelsFragment.TAG, ::ChannelsFragment)
                R.id.nav_favorites -> showFragment(FavoritesFragment.TAG, ::FavoritesFragment)
                R.id.nav_settings -> showFragment(SettingsFragment.TAG, ::SettingsFragment)
                else -> false
            }
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_playlists
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
}
