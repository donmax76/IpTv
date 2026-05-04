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
                R.id.nav_home -> showFragment(HomeFragment.TAG, ::HomeFragment)
                R.id.nav_playlists -> showFragment(PlaylistsFragment.TAG, ::PlaylistsFragment)
                R.id.nav_favorites -> showFragment(FavoritesFragment.TAG, ::FavoritesFragment)
                R.id.nav_settings -> { openSettings(); false /* don't actually select */ }
                else -> false
            }
        }

        // Bottom navigation is kept hidden permanently (user asked for
        // the side drawer to be the only navigation). Don't toggle its
        // visibility on drawer events.
        bottomNav.visibility = View.GONE

        sideNav.setNavigationItemSelectedListener { item ->
            handleSideNavSelection(item.itemId)
            true
        }

        // Touch-кнопка меню для телефонов (не работает с пультом — на
        // боксе используется DPAD_LEFT). Открывает боковой drawer.
        findViewById<View>(R.id.btnTouchMenu)?.setOnClickListener {
            openSideDrawer()
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_home

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
        // Триггерим auto-refresh EPG. Сам fetcher имеет 24h gate, так
        // что ничего не делает если refresh был сегодня. Кейс который
        // фиксим: юзер открыл приложение, не закрывал его сутки,
        // на следующий день программа осталась старой — раньше
        // refresh был только в TVViewerApp.onCreate (раз за процесс).
        try { TVViewerApp.triggerEpgAutoRefresh(this) } catch (_: Throwable) {}
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
                    // tab=1 раньше указывал на Channels — теперь возвращаем
                    // на Home (стартовый экран Live/Playlists).
                    1 -> showFragment(HomeFragment.TAG, ::HomeFragment)
                    2 -> showFragment(FavoritesFragment.TAG, ::FavoritesFragment)
                    3 -> showFragment(RecentFragment.TAG, ::RecentFragment)
                    4 -> openSettings()
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

    /** Старый switchToChannels: теперь сразу запускает плеер с
     *  выбранным плейлистом вместо переключения на ChannelsFragment.
     *  Каналы больше не доступны как отдельная вкладка. */
    fun switchToChannels(playlistName: String, playlistUrl: String) {
        prefs.lastPlaylistUrl = playlistUrl
        prefs.lastPlaylistName = playlistName
        ChannelDataHolder.pendingPlaylistName = playlistName
        ChannelDataHolder.pendingPlaylistUrl = playlistUrl
        playPlaylist(playlistName, playlistUrl)
    }

    fun openPlaylistsTab() {
        bottomNav.selectedItemId = R.id.nav_playlists
    }

    fun openHomeTab() {
        bottomNav.selectedItemId = R.id.nav_home
    }

    /** Загружает плейлист в фоне и запускает плеер на первом
     *  (или последнем сохранённом) канале. */
    private fun playPlaylist(name: String, url: String) {
        val ctx = applicationContext
        lifecycleScope.launch {
            try {
                // Если этот плейлист уже загружен — не качаем повторно.
                val cached = ChannelDataHolder.loadedPlaylistUrl == url &&
                    ChannelDataHolder.allChannels.isNotEmpty()
                val all = if (cached) ChannelDataHolder.allChannels else {
                    val res = PlaylistRepository.fetchPlaylist(url, ctx)
                    val custom = prefs.customChannels.map { (n, u) -> Channel(name = n, url = u) }
                    val merged = res.channels + custom
                    if (merged.isEmpty()) {
                        android.widget.Toast.makeText(ctx, R.string.load_failed, android.widget.Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    ChannelDataHolder.allChannels = merged
                    ChannelDataHolder.loadedPlaylistUrl = url
                    prefs.enrichFavorites(merged)
                    merged
                }
                // Контекст — плейлист, не избранное.
                prefs.lastWasFavorites = false
                val lastChan = prefs.lastChannelUrl
                val idx = all.indexOfFirst { it.url == lastChan }.let { if (it < 0) 0 else it }
                ChannelDataHolder.currentChannelIndex = idx
                val target = all[idx]
                prefs.pushRecent(target.url)
                val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, target.name)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_URL, target.url)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, idx)
                }
                if (prefs.playerType == AppPreferences.PLAYER_EXTERNAL) {
                    ctx.launchExternalVideo(target.url)
                } else {
                    startActivity(intent)
                }
            } catch (e: Exception) {
                ErrorLogger.logException(ctx, e)
                android.widget.Toast.makeText(ctx, R.string.load_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // dispatchKeyEvent перехватывает кнопки ДО того как они дойдут до
    // сфокусированного View. Без этого OK/Enter при открытом drawer'е
    // активирует сначала ту кнопку которая была в фокусе ДО открытия
    // drawer'а (например "назад" в ТВ Гиде), а не пункт меню.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            ::drawerLayout.isInitialized &&
            drawerLayout.isDrawerOpen(Gravity.START)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    drawerLayout.closeDrawer(Gravity.START); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveSideNavSelection(+1); return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveSideNavSelection(-1); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    handleSideNavSelection(sideNavItemIds[sideNavSelectedIdx])
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // D-pad / remote control navigation
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // dispatchKeyEvent уже разобрал кнопки drawer'а до нас, тут
        // только обработка вне drawer'а.
        if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(Gravity.START)) {
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

    private val sideNavItemIds = intArrayOf(
        R.id.side_nav_home,
        R.id.side_nav_playlists,
        R.id.side_nav_favorites,
        R.id.side_nav_recent,
        R.id.side_nav_settings,
    )
    private var sideNavSelectedIdx = 0

    private fun openSideDrawer() {
        // Очищаем фокус с того что было до — на TV-боксе иначе
        // DPAD_CENTER в drawer'е активирует кнопку что была раньше
        // (например "назад" в ТВ Гиде). dispatchKeyEvent теперь
        // перехватывает кнопки drawer'а до фокуса, но это страховка.
        currentFocus?.clearFocus()
        drawerLayout.openDrawer(Gravity.START)
        // Сбрасываем выбор на первый пункт. UP/DOWN перебирают пункты
        // через sideNav.setCheckedItem (визуальная подсветка), OK
        // активирует выбранный пункт. Раньше полагались на родную
        // NavigationView фокус-логику, но она не разворачивалась с
        // первого открытия Activity.
        sideNavSelectedIdx = 0
        sideNav.setCheckedItem(sideNavItemIds[0])
    }

    private fun handleSideNavSelection(itemId: Int) {
        when (itemId) {
            R.id.side_nav_home -> {
                showFragment(HomeFragment.TAG, ::HomeFragment)
                bottomNav.selectedItemId = R.id.nav_home
            }
            R.id.side_nav_playlists -> {
                showFragment(PlaylistsFragment.TAG, ::PlaylistsFragment)
                bottomNav.selectedItemId = R.id.nav_playlists
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
    }

    private fun moveSideNavSelection(delta: Int) {
        val n = sideNavItemIds.size
        sideNavSelectedIdx = (sideNavSelectedIdx + delta + n) % n
        sideNav.setCheckedItem(sideNavItemIds[sideNavSelectedIdx])
    }

    private fun findRecyclerView(root: View): androidx.recyclerview.widget.RecyclerView? {
        if (root is androidx.recyclerview.widget.RecyclerView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val r = findRecyclerView(root.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    @Deprecated("Required override for older APIs")
    override fun onBackPressed() {
        if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START)
            return
        }
        // На пульте: первый BACK перебрасывает фокус на bottom-nav,
        // второй — спрашивает выход. На телефоне (тач) bottom-nav скрыт,
        // фокуса там нет никогда, так что диалог никогда не появлялся.
        // Теперь: если фокус НЕ на bottom-nav И на пульте (есть focus,
        // bottom-nav видим) — перебрасываем как раньше; иначе сразу
        // подтверждаем выход.
        val focus = currentFocus
        val bottomNavVisible = bottomNav.visibility == View.VISIBLE
        if (focus != null && bottomNavVisible && !isBottomNavFocused(focus)) {
            bottomNav.requestFocus()
            return
        }
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
        R.id.nav_home,
        R.id.nav_playlists,
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
