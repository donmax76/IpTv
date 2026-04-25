package com.tvviewer

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sideNav: NavigationView
    private lateinit var prefs: AppPreferences
    private var currentFragmentTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPreferences(this)
        applyOrientation()

        bottomNav = findViewById(R.id.bottomNavigation)
        drawerLayout = findViewById(R.id.drawerLayout)
        sideNav = findViewById(R.id.sideNav)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_playlists -> showFragment(PlaylistsFragment.TAG, ::PlaylistsFragment)
                R.id.nav_channels -> showFragment(ChannelsFragment.TAG, ::ChannelsFragment)
                R.id.nav_tv_guide -> showFragment(TvGuideFragment.TAG, ::TvGuideFragment)
                R.id.nav_favorites -> showFragment(FavoritesFragment.TAG, ::FavoritesFragment)
                R.id.nav_settings -> { openSettings(); false /* don't actually select */ }
                else -> false
            }
        }

        // Bottom navigation is kept hidden permanently (user asked for
        // the side drawer to be the only navigation). Don't toggle its
        // visibility on drawer events.
        bottomNav.visibility = View.GONE

        // Side drawer (left): full 6-item nav including Recent and Settings
        sideNav.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.side_nav_playlists -> {
                    showFragment(PlaylistsFragment.TAG, ::PlaylistsFragment)
                    bottomNav.selectedItemId = R.id.nav_playlists
                }
                R.id.side_nav_channels -> {
                    showFragment(ChannelsFragment.TAG, ::ChannelsFragment)
                    bottomNav.selectedItemId = R.id.nav_channels
                }
                R.id.side_nav_tv_guide -> {
                    showFragment(TvGuideFragment.TAG, ::TvGuideFragment)
                    bottomNav.selectedItemId = R.id.nav_tv_guide
                }
                R.id.side_nav_favorites -> {
                    showFragment(FavoritesFragment.TAG, ::FavoritesFragment)
                    bottomNav.selectedItemId = R.id.nav_favorites
                }
                R.id.side_nav_recent -> {
                    showFragment(RecentFragment.TAG, ::RecentFragment)
                }
                R.id.side_nav_settings -> {
                    openSettings()
                }
            }
            drawerLayout.closeDrawer(Gravity.START)
            true
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_playlists

            // Auto-check for updates on start
            checkForUpdatesOnStart()

            // Autoplay last channel if enabled
            if (prefs.autoplayLast) {
                val lastUrl = prefs.lastChannelUrl
                if (!lastUrl.isNullOrBlank()) {
                    val channel = ChannelDataHolder.allChannels.find { it.url == lastUrl }
                    if (channel != null) {
                        val index = ChannelDataHolder.allChannels.indexOf(channel)
                        ChannelDataHolder.currentChannelIndex = index
                        prefs.pushRecent(channel.url)
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
        // PlayerActivity sets this when the user presses LEFT twice from
        // the channel list overlay — meaning they want the side menu.
        if (ChannelDataHolder.openDrawerOnReturn) {
            ChannelDataHolder.openDrawerOnReturn = false
            window.decorView.post { openSideDrawer() }
        }
        // PlayerActivity's in-player drawer can ask MainActivity to switch
        // to a specific section instead of opening the drawer.
        val tab = ChannelDataHolder.returnToTabIndex
        if (tab >= 0) {
            ChannelDataHolder.returnToTabIndex = -1
            window.decorView.post {
                when (tab) {
                    0 -> showFragment(PlaylistsFragment.TAG, ::PlaylistsFragment)
                    1 -> showFragment(ChannelsFragment.TAG, ::ChannelsFragment)
                    2 -> showFragment(TvGuideFragment.TAG, ::TvGuideFragment)
                    3 -> showFragment(FavoritesFragment.TAG, ::FavoritesFragment)
                    4 -> showFragment(RecentFragment.TAG, ::RecentFragment)
                    5 -> openSettings()
                }
            }
        }
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

    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // D-pad / remote control navigation
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If the drawer is already open, route all D-pad keys to it so the
        // user can navigate items and Back closes it.
        if (drawerLayout.isDrawerOpen(Gravity.START)) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                drawerLayout.closeDrawer(Gravity.START)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            // Left on D-pad:
            //   • on bottom nav with focus already on the leftmost tab → open the side drawer
            //   • on bottom nav otherwise → previous tab
            //   • inside a fragment list, a single LEFT does nothing special;
            //     a SECOND LEFT (when there's nothing to the left in the
            //     fragment) opens the drawer too — the rest of the navigation
            //     happens via Android's default focus search.
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val focusedView = currentFocus
                if (focusedView == null || isBottomNavFocused(focusedView)) {
                    val firstId = tabIds.firstOrNull()
                    if (bottomNav.selectedItemId == firstId) {
                        openSideDrawer(); return true
                    }
                    selectPreviousTab()
                    return true
                }
                // Inside a fragment: if focus is on the leftmost element
                // (e.g. left-most chip / list — nothing further left), open
                // the drawer so the user can reach Settings/Recent quickly.
                val canMoveLeft = focusedView.focusSearch(View.FOCUS_LEFT) != null
                if (!canMoveLeft) {
                    openSideDrawer(); return true
                }
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val focusedView = currentFocus
                if (focusedView == null || isBottomNavFocused(focusedView)) {
                    selectNextTab()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            // D-pad Up - when on bottom nav, move focus to content area
            KeyEvent.KEYCODE_DPAD_UP -> {
                val focusedView = currentFocus
                if (focusedView == null || isBottomNavFocused(focusedView)) {
                    // Move focus to the fragment content
                    val container = findViewById<View>(R.id.fragmentContainer)
                    val firstFocusable = container?.findFocus() ?: findFirstFocusableInFragment()
                    if (firstFocusable != null) {
                        firstFocusable.requestFocus()
                        return true
                    }
                }
                return super.onKeyDown(keyCode, event)
            }
            // D-pad Down - when at bottom of content, move to bottom nav
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Let default focus navigation handle it first
                // If the current fragment can't move focus down, it will reach bottom nav
                return super.onKeyDown(keyCode, event)
            }
            // D-pad Center / Enter - select current tab or item
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                return super.onKeyDown(keyCode, event)
            }
            // Menu / Info / Guide / Settings — open the side drawer
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_TV_INPUT -> {
                openSideDrawer()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openSideDrawer() {
        drawerLayout.openDrawer(Gravity.START)
        sideNav.requestFocus()
        // Focus the first item explicitly for D-pad users
        sideNav.menu.getItem(0)?.let { sideNav.setCheckedItem(it) }
    }

    @Deprecated("Required override for older APIs")
    override fun onBackPressed() {
        if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START)
            return
        }
        // Stage 1: if focus is anywhere except the bottom navigation, just
        // jump to it. This gives the user a guaranteed one-key escape from
        // a long channel list without scrolling through thousands of items.
        val focus = currentFocus
        if (focus != null && !isBottomNavFocused(focus)) {
            bottomNav.requestFocus()
            return
        }
        // Stage 2: focus is already on bottom nav — confirm exit.
        AlertDialog.Builder(this, R.style.Theme_TVViewer_Dialog)
            .setMessage(R.string.exit_app_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> super.onBackPressed() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun isBottomNavFocused(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v == bottomNav) return true
            val parent = v.parent
            v = if (parent is View) parent else null
        }
        return false
    }

    private fun findFirstFocusableInFragment(): View? {
        val fm = supportFragmentManager
        val currentFragment = fm.fragments.firstOrNull { !it.isHidden && it.isVisible }
        return currentFragment?.view?.let { findFirstFocusable(it) }
    }

    private fun findFirstFocusable(view: View): View? {
        if (view.isFocusable && view.visibility == View.VISIBLE) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val result = findFirstFocusable(child)
                if (result != null) return result
            }
        }
        return null
    }

    private val tabIds = listOf(
        R.id.nav_playlists,
        R.id.nav_channels,
        R.id.nav_tv_guide,
        R.id.nav_favorites,
        R.id.nav_settings
    )

    private fun selectPreviousTab() {
        val currentIdx = tabIds.indexOf(bottomNav.selectedItemId)
        if (currentIdx > 0) {
            bottomNav.selectedItemId = tabIds[currentIdx - 1]
        }
    }

    private fun selectNextTab() {
        val currentIdx = tabIds.indexOf(bottomNav.selectedItemId)
        if (currentIdx < tabIds.size - 1) {
            bottomNav.selectedItemId = tabIds[currentIdx + 1]
        }
    }

    private fun checkForUpdatesOnStart() {
        lifecycleScope.launch {
            try {
                val result = UpdateChecker.check(prefs.updateCheckUrl)
                val updateInfo = result.getOrNull()
                if (updateInfo != null && updateInfo.versionCode > BuildConfig.VERSION_CODE) {
                    val message = buildString {
                        append("${getString(R.string.current_version)}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        append("\n${getString(R.string.new_version)}: ${updateInfo.versionName} (${updateInfo.versionCode})")
                        if (updateInfo.releaseNotes.isNotBlank()) {
                            append("\n\n${updateInfo.releaseNotes.take(500)}")
                        }
                    }
                    AlertDialog.Builder(this@MainActivity, R.style.Theme_TVViewer_Dialog)
                        .setTitle(getString(R.string.update_available, updateInfo.versionName))
                        .setMessage(message)
                        .setPositiveButton(R.string.update_download) { _, _ ->
                            UpdateInstaller.downloadAndInstall(this@MainActivity, updateInfo.downloadUrl)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                Log.d("MainActivity", "Auto update check failed", e)
            }
        }
    }
}
