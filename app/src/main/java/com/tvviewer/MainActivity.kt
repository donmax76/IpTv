package com.tvviewer

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sideNav: NavigationView
    private lateinit var prefs: AppPreferences
    private var currentFragmentTag: String? = null

    /** Слушатель состояния EPG-refresh — показывает / скрывает
     *  верхний баннер "Обновляю программу". Снимается в onPause. */
    private val epgRefreshStateListener: (Boolean) -> Unit = { running ->
        runOnUiThread {
            findViewById<View>(R.id.epgRefreshBanner)?.visibility =
                if (running) View.VISIBLE else View.GONE
        }
    }

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

            // Round 222b: проверка апдейта снова в SplashActivity до
            // открытия MainActivity. Сюда мы попадаем УЖЕ после её
            // ответа. onResume ниже триггерит maybeCheck c 1-часовым
            // троттлом — это только страховка если splash упал по
            // таймауту.

            // Autoplay last channel if enabled
            if (prefs.autoplayLast) {
                val lastUrl = prefs.lastChannelUrl
                if (!lastUrl.isNullOrBlank()) {
                    val channel = ChannelDataHolder.allChannels.find { it.url == lastUrl }
                    // Android Round 375: если последний канал ЗАБЛОКИРОВАН
                    // родительским контролем — НЕ автозапускаем его.
                    // Юзер: не должно открывать заблокированный канал и
                    // спрашивать PIN на старте; вместо этого остаёмся в
                    // общем списке каналов (эта же MainActivity).
                    if (channel != null &&
                            !ParentalControl.isLocked(prefs, channel)) {
                        val index = ChannelDataHolder.allChannels.indexOf(channel)
                        ChannelDataHolder.currentChannelIndex = index
                        prefs.pushRecentChannel(channel)
                        this.launchPreferredPlayer(channel, index)
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
        // Round 185: общий хелпер с 1-часовым троттлом (был 6 ч до
        // 185 — юзер жаловался "не видно 272, только 271", потому что
        // CI выпустил билд раньше чем истёк гейт).
        UpdateCheckerHelper.maybeCheck(this)
        // Подписываемся на state-listener чтобы показать баннер
        // "идёт обновление" сразу при старте если refresh в процессе.
        EpgRepository.addRefreshStateListener(epgRefreshStateListener)
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

    override fun onPause() {
        super.onPause()
        EpgRepository.removeRefreshStateListener(epgRefreshStateListener)
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
                // Тяжёлая пост-обработка — на Default-диспетчере.
                // lifecycleScope.launch{} без аргумента = Main: раньше
                // сортировка 4000+ каналов (в режиме "quality" — ещё и
                // full-JSON parse per-канал до кэша), merge с custom,
                // enrichFavorites (O(favorites × channels) сканы) и
                // indexOfFirst шли прямо на UI-нитке — секундные фризы
                // по нажатию на плейлист/«Эфир» (ANR-класс).
                val all = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    if (cached) {
                        prefs.enrichFavorites(ChannelDataHolder.allChannels)
                        ChannelDataHolder.allChannels
                    } else {
                        val res = PlaylistRepository.fetchPlaylist(url, ctx)
                        val custom = prefs.customChannels.map { (n, u) -> Channel(name = n, url = u) }
                        val merged = res.channels + custom
                        if (merged.isEmpty()) {
                            emptyList()
                        } else {
                            // Round 194: применяем настройку Settings →
                            // "Сортировка каналов".
                            val sorted = ChannelSorter.apply(prefs, merged)
                            ChannelDataHolder.allChannels = sorted
                            ChannelDataHolder.loadedPlaylistUrl = url
                            prefs.enrichFavorites(sorted)
                            sorted
                        }
                    }
                }
                if (all.isEmpty()) {
                    android.widget.Toast.makeText(ctx, R.string.load_failed, android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Контекст — плейлист, не избранное.
                prefs.lastWasFavorites = false
                val lastChan = prefs.lastChannelUrl
                val idx = all.indexOfFirst { it.url == lastChan }.let { if (it < 0) 0 else it }
                ChannelDataHolder.currentChannelIndex = idx
                val target = all[idx]
                prefs.pushRecentChannel(target)
                this@MainActivity.launchPreferredPlayer(target, idx)
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

    // Round 223: time of the last BACK press at the «нечего возвращать»
    // state. Если второй BACK прилетел в течение 2 сек — показываем
    // диалог выхода. Иначе — Toast «нажмите ещё раз» и обновление
    // времени. Заменяет старое поведение «BACK → перевод фокуса в
    // bottom-nav → второй BACK → выход».
    private var lastBackPressMs: Long = 0
    private val DOUBLE_BACK_WINDOW_MS = 2000L

    @Deprecated("Required override for older APIs")
    override fun onBackPressed() {
        if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START)
            return
        }
        // Если открыта вкладка отличная от Home — первый BACK
        // возвращает на Home (это «предыдущее меню»).
        if (bottomNav.selectedItemId != R.id.nav_home) {
            bottomNav.selectedItemId = R.id.nav_home
            return
        }
        // Round 230: на Home double-BACK закрывает программу СРАЗУ,
        // без AlertDialog «Выйти?». Юзер: «достаточно алерта». Алерт
        // (Toast) уже показан на первом BACK, второй — финальный.
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs < DOUBLE_BACK_WINDOW_MS) {
            lastBackPressMs = 0L
            super.onBackPressed()
            return
        }
        lastBackPressMs = now
        Toast.makeText(this, R.string.press_back_again_to_exit,
            Toast.LENGTH_SHORT).show()
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

}
