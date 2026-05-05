package com.tvviewer

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import coil.load
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class PlayerActivity : BaseActivity() {

    companion object {
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_URL = "channel_url"
        const val EXTRA_CHANNEL_INDEX = "channel_index"

        /** Round 190: общий OkHttpClient для всех ExoPlayer-запросов.
         *  Connection pool с keep-alive живёт всё время процесса —
         *  переключение каналов на одном CDN не требует нового
         *  TCP+TLS handshake. Большое окно пула (32 idle соединения,
         *  90 с) рассчитано на быстрое CH+/CH- между ~30 каналами
         *  одного провайдера. trust-all: IPTV-CDN'ы часто отдают
         *  несовпадающие сертификаты. */
        private val sharedStreamHttpClient: okhttp3.OkHttpClient by lazy {
            val trust = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<java.security.cert.X509Certificate>,
                        authType: String) {}
                    override fun checkServerTrusted(
                        chain: Array<java.security.cert.X509Certificate>,
                        authType: String) {}
                    override fun getAcceptedIssuers():
                        Array<java.security.cert.X509Certificate> = emptyArray()
                }
            )
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
            sslCtx.init(null, trust, java.security.SecureRandom())
            okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(sslCtx.socketFactory,
                    trust[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectionPool(okhttp3.ConnectionPool(
                    /* maxIdleConnections = */ 32,
                    /* keepAliveDuration = */ 90,
                    java.util.concurrent.TimeUnit.SECONDS))
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        private val PLAYER_DRAWER_IDS = intArrayOf(
            R.id.playerDrawerPlaylists,
            R.id.playerDrawerFavorites,
            R.id.playerDrawerRecent,
            R.id.playerDrawerSettings,
        )
        private val RIGHT_MENU_IDS = intArrayOf(
            R.id.rightMenuFavorite,
            R.id.rightMenuChannelList,
            R.id.rightMenuLastChannel,
            R.id.rightMenuAudio,
            R.id.rightMenuSpeed,
            R.id.rightMenuAspect,
            R.id.rightMenuPip,
            R.id.rightMenuLock,
            R.id.rightMenuHttp,
        )

        // Common User-Agents для per-channel переопределения. Первое
        // значение "" означает "использовать глобальный".
        private val UA_PRESETS = listOf(
            "" to "ua_default",
            "VLC/3.0.20 LibVLC/3.0.20" to "ua_vlc",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" to "ua_chrome",
            "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0" to "ua_firefox",
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36" to "ua_android",
            "Lavf/58.76.100" to "ua_lavf",
        )
    }

    private lateinit var playerView: PlayerView
    private lateinit var controlsOverlay: RelativeLayout
    // Listeners on viewTreeObserver — сохраняем чтобы снять в onDestroy
    // иначе они удерживают Activity (memory leak — Round 129 audit).
    private var controlsFocusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private var overlayFocusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private lateinit var topBar: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var channelName: TextView
    private lateinit var epgNow: TextView
    private lateinit var channelNumber: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var clockDisplay: TextView
    private lateinit var persistentClock: TextView
    private lateinit var channelListOverlay: FrameLayout
    private lateinit var playerDrawerOverlay: FrameLayout
    private lateinit var playerRightMenuOverlay: FrameLayout
    private var streamDataFactory: androidx.media3.datasource.DataSource.Factory? = null
    private lateinit var overlayChannelsList: RecyclerView
    // Поля overlayCategoriesList — нужны не только в setup но и в
    // onDestroy для снятия ViewTreeObserver-listener (Round 129 audit).
    // Иначе lateinit-проверка в onDestroy не компилируется.
    private lateinit var overlayCategoriesList: RecyclerView
    // Контейнер вертикального столбца категорий — показываем/скрываем
    // в зависимости от наличия групп в плейлисте.
    private lateinit var overlayCategoriesPanel: LinearLayout
    private lateinit var numberInputDisplay: TextView
    private lateinit var sleepTimerIndicator: TextView
    private lateinit var prefs: AppPreferences

    // Gesture overlay indicators
    private lateinit var gestureIndicator: LinearLayout
    private lateinit var gestureIcon: ImageView
    private lateinit var gestureText: TextView
    private lateinit var gestureProgress: ProgressBar

    // Screen lock
    private lateinit var lockOverlay: FrameLayout
    private lateinit var btnLock: ImageButton
    private var isScreenLocked = false

    // Audio/Subtitle info
    private lateinit var btnAudioTrack: ImageButton
    private lateinit var btnSpeed: ImageButton
    private lateinit var audioTrackInfo: TextView

    private var player: ExoPlayer? = null
    /** MediaSession ловит медиа-кнопки (CH+/CH-/PRE-CH/play/pause/etc.)
     *  которые TV-бокс роутит через dispatchMediaKeyEvent а не через
     *  обычный dispatchKeyEvent. Без этого CH+/CH- на пультах X-боксов
     *  не доходили до приложения — система отдавала их активной
     *  media session, а у нас её не было. Создаём в onCreate и
     *  освобождаем в onDestroy. */
    private var mediaSession: android.media.session.MediaSession? = null
    private var currentUrl: String? = null
    private var currentIndex: Int = 0
    // Индекс предыдущего просмотренного канала, чтобы по кнопке Recall /
    // Last channel / красной кнопке вернуться туда, где только что был.
    // -1 — пока ни одного переключения не было.
    private var previousIndex: Int = -1
    private var controlsVisible = true
    private var channelListVisible = false
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private val channelListHideHandler = Handler(Looper.getMainLooper())
    private val channelListHideRunnable = Runnable { hideChannelList() }
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30000)
        }
    }
    private var aspectRatioMode = 0

    // Number input for remote
    private var numberInput = ""
    private val numberHandler = Handler(Looper.getMainLooper())
    private val numberRunnable = Runnable { applyNumberInput() }

    // Sleep timer
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepTimerEnd: Long = 0
    private val sleepTimerRunnable = object : Runnable {
        override fun run() {
            val remaining = sleepTimerEnd - System.currentTimeMillis()
            if (remaining <= 0) {
                player?.pause()
                sleepTimerIndicator.visibility = View.GONE
                Toast.makeText(this@PlayerActivity, R.string.sleep_timer_off, Toast.LENGTH_LONG).show()
                return
            }
            val mins = (remaining / 60000).toInt()
            sleepTimerIndicator.text = "${getString(R.string.sleep_timer)}: ${mins + 1} мин"
            sleepTimerIndicator.visibility = View.VISIBLE
            sleepHandler.postDelayed(this, 60000)
        }
    }

    // Channel info banner
    private lateinit var channelInfoBanner: LinearLayout
    private lateinit var bannerChannelNumber: TextView
    private lateinit var bannerChannelName: TextView
    private lateinit var bannerChannelLogo: ImageView
    private lateinit var bannerEpgNow: TextView
    private lateinit var bannerEpgNext: TextView
    private lateinit var bannerClock: TextView
    private lateinit var bannerEpgProgress: ProgressBar
    private val bannerHandler = Handler(Looper.getMainLooper())
    private val bannerHideRunnable = Runnable { channelInfoBanner.visibility = View.GONE }

    // Auto-reconnect on playback failure / unexpected stream end
    private var reconnectAttempts = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        val url = currentUrl
        if (url != null) {
            errorText.text = getString(R.string.reconnecting)
            playStream(url)
        }
    }
    private val MAX_RECONNECT = 8

    // Когда true — следующий onPause не ставит плеер на паузу.
    // Используется при открытии Настроек из выдвижного меню плеера: плеер
    // продолжает играть, пока пользователь меняет настройки.
    private var keepPlayingInBackground = false

    private var overlayAdapter: OverlayChannelAdapter? = null
    private var overlaySearchEdit: EditText? = null
    private var overlayChannelCount: TextView? = null
    private var overlayFilteredChannels: List<Channel> = emptyList()
    private var overlayFilteredIndices: List<Int> = emptyList()

    // Gesture control
    private lateinit var audioManager: AudioManager
    private var gestureDetector: GestureDetector? = null
    private var isSwipingVolume = false
    private var isSwipingBrightness = false
    private var swipeStartVolume = 0
    private var swipeStartBrightness = 0f
    private var swipeStartY = 0f

    // Playback speed
    private var currentSpeedIndex = 2 // index into speedValues
    private val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        prefs = AppPreferences(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initViews()
        hideSystemUI()
        setupGestures()

        val name = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        currentUrl = intent.getStringExtra(EXTRA_CHANNEL_URL) ?: ""
        currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0)

        channelName.text = name
        channelNumber.text = "${currentIndex + 1} / ${ChannelDataHolder.allChannels.size}"

        // СНАЧАЛА подписываемся на EPG-апдейты — ДО updateEpg.
        // Без этого был race: если cache из TVViewerApp загрузился
        // и notifyEpgUpdate уже выстрелил до register'а — событие
        // терялось. Теперь listener подписан раньше → ловим всё.
        EpgRepository.addEpgUpdateListener(playerEpgListener)

        updateEpg()
        initPlayer()
        playStream(currentUrl!!)
        showChannelBanner()
        scheduleHideControls()
        startClock()
        setupMediaSession()

        // Если EPG-кэш ещё не подтянут TVViewerApp — грузим в фоне.
        // НЕ синхронно: deserialize 6361 канала на X4 X4 блокирует
        // UI thread на 1-3 сек, в это время пульт игнорирует клавиши
        // (в т.ч. CH+/CH−). После загрузки playerEpgListener сам
        // перерисует UI.
        if (ChannelDataHolder.epgData.isEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val cached = EpgRepository.loadFromCache(this@PlayerActivity)
                    if (cached != null && cached.isNotEmpty()) {
                        ChannelDataHolder.epgData = cached
                        EpgRepository.notifyEpgUpdate(cached)
                        android.util.Log.d("PlayerActivity",
                            "EPG cache loaded async on player start: ${cached.size} channels")
                    }
                } catch (_: Throwable) {}
            }
        }

        // Save last channel + push to recent history
        prefs.lastChannelUrl = currentUrl
        currentUrl?.let { prefs.pushRecent(it) }

        // Setup sleep timer if configured
        val timerMins = prefs.sleepTimerMinutes
        if (timerMins > 0) {
            startSleepTimer(timerMins)
        }
    }

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        controlsOverlay = findViewById(R.id.controlsOverlay)
        topBar = findViewById(R.id.topBar)
        btnBack = findViewById(R.id.btnBack)
        channelName = findViewById(R.id.channelName)
        epgNow = findViewById(R.id.epgNow)
        channelNumber = findViewById(R.id.channelNumber)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorLayout = findViewById(R.id.errorLayout)
        errorText = findViewById(R.id.errorText)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        clockDisplay = findViewById(R.id.clockDisplay)
        persistentClock = findViewById(R.id.persistentClock)
        channelListOverlay = findViewById(R.id.channelListOverlay)

        // In-player drawer (shown over the channel list on 2nd LEFT)
        playerDrawerOverlay = findViewById(R.id.playerDrawerOverlay)
        findViewById<View>(R.id.playerDrawerDimBg).setOnClickListener { hidePlayerDrawer() }
        val gotoMain: (Int) -> Unit = { tabIdx ->
            ChannelDataHolder.openDrawerOnReturn = false
            ChannelDataHolder.returnToTabIndex = tabIdx
            finish()
        }
        findViewById<View>(R.id.playerDrawerPlaylists).setOnClickListener { gotoMain(0) }
        // playerDrawerChannels удалён
        // playerDrawerTvGuide удалён вместе с вкладкой ТВ Гид.
        // Индексы gotoMain сдвинуты на -1 после удаления.
        findViewById<View>(R.id.playerDrawerFavorites).setOnClickListener { gotoMain(2) }
        findViewById<View>(R.id.playerDrawerRecent).setOnClickListener { gotoMain(3) }
        // Настройки открываем поверх плеера, не завершая активити: трансляция
        // продолжается (звук+картинка), пользователь меняет параметры,
        // возвращается обратно — плеер идёт без перезапуска.
        findViewById<View>(R.id.playerDrawerSettings).setOnClickListener {
            keepPlayingInBackground = true
            hidePlayerDrawer()
            // Round 183: закрываем overlay со списком каналов и категорий
            // ПЕРЕД открытием Settings. Иначе после Back из Settings
            // PlayerActivity показывает overlay с предыдущей категорией,
            // и юзеру нужно нажать Back ещё раз чтобы попасть на видео
            // (жаловался: "при возврате нажатии назад вкладка категория
            // показывается а нужно сразу плеер").
            if (channelListVisible) hideChannelList()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Правое выпадающее меню плеера (DPAD_RIGHT). Все эти действия
        // раньше торчали кнопками в верхнем правом углу — теперь они
        // спрятаны и доступны только из этого меню.
        playerRightMenuOverlay = findViewById(R.id.playerRightMenuOverlay)
        findViewById<View>(R.id.playerRightMenuDimBg).setOnClickListener { hidePlayerRightMenu() }

        // Touch-кнопки в верхней панели для телефона: список каналов
        // и правое меню (на пульте те же действия — DPAD_LEFT / DPAD_RIGHT).
        findViewById<View>(R.id.btnTouchChannelList)?.setOnClickListener {
            toggleChannelList()
        }
        findViewById<View>(R.id.btnTouchRightMenu)?.setOnClickListener {
            showPlayerRightMenu()
        }
        // Избранное: добавить/убрать ТЕКУЩИЙ канал. Кнопка-toggle —
        // текст и иконка меняются в showPlayerRightMenu в зависимости
        // от того, в избранном ли канал сейчас.
        findViewById<View>(R.id.rightMenuFavorite).setOnClickListener {
            val ch = ChannelDataHolder.allChannels.getOrNull(currentIndex) ?: return@setOnClickListener
            val wasFav = prefs.isFavorite(ch.url)
            // Передаём ВЕСЬ Channel (не только url) — так в избранных
            // сохраняются имя/лого/группа из текущего плейлиста, и
            // канал виден в Favorites даже после смены плейлиста.
            if (wasFav) prefs.removeFavorite(ch.url) else prefs.addFavorite(ch)
            overlayAdapter?.updateFavorites(prefs.favorites)
            val msgRes = if (wasFav) R.string.favorite_removed else R.string.favorite_added
            Toast.makeText(this, getString(msgRes), Toast.LENGTH_SHORT).show()
            hidePlayerRightMenu()
        }
        findViewById<View>(R.id.rightMenuChannelList).setOnClickListener {
            hidePlayerRightMenu()
            toggleChannelList()
        }
        findViewById<View>(R.id.rightMenuLastChannel).setOnClickListener {
            hidePlayerRightMenu()
            switchToPreviousChannel()
        }
        findViewById<View>(R.id.rightMenuAudio).setOnClickListener {
            hidePlayerRightMenu()
            showAudioTrackDialog()
        }
        findViewById<View>(R.id.rightMenuSpeed).setOnClickListener {
            hidePlayerRightMenu()
            cycleSpeed()
        }
        findViewById<View>(R.id.rightMenuAspect).setOnClickListener {
            // НЕ закрываем меню — позволяем многократно нажать OK для
            // быстрого перебора Fit/16:9/4:3/Stretch без переоткрытия.
            cycleAspectRatio()
        }
        findViewById<View>(R.id.rightMenuPip).setOnClickListener {
            hidePlayerRightMenu()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) enterPipMode()
        }
        findViewById<View>(R.id.rightMenuLock).setOnClickListener {
            hidePlayerRightMenu()
            toggleScreenLock()
        }
        findViewById<View>(R.id.rightMenuHttp).setOnClickListener {
            hidePlayerRightMenu()
            showChannelHttpDialog()
        }
        overlayChannelsList = findViewById(R.id.overlayChannelsList)
        numberInputDisplay = findViewById(R.id.numberInputDisplay)
        sleepTimerIndicator = findViewById(R.id.sleepTimerIndicator)

        // Channel info banner
        channelInfoBanner = findViewById(R.id.channelInfoBanner)
        bannerChannelNumber = findViewById(R.id.bannerChannelNumber)
        bannerChannelName = findViewById(R.id.bannerChannelName)
        bannerChannelLogo = findViewById(R.id.bannerChannelLogo)
        bannerEpgNow = findViewById(R.id.bannerEpgNow)
        bannerEpgNext = findViewById(R.id.bannerEpgNext)
        bannerClock = findViewById(R.id.bannerClock)
        bannerEpgProgress = findViewById(R.id.bannerEpgProgress)

        // Gesture indicator
        gestureIndicator = findViewById(R.id.gestureIndicator)
        gestureIcon = findViewById(R.id.gestureIcon)
        gestureText = findViewById(R.id.gestureText)
        gestureProgress = findViewById(R.id.gestureProgress)

        // Screen lock
        lockOverlay = findViewById(R.id.lockOverlay)
        btnLock = findViewById(R.id.btnLock)

        // Audio track and speed buttons
        btnAudioTrack = findViewById(R.id.btnAudioTrack)
        btnSpeed = findViewById(R.id.btnSpeed)
        audioTrackInfo = findViewById(R.id.audioTrackInfo)

        // Show clock based on settings — both the in-bar clock (which is
        // hidden when controls auto-hide) and the persistent clock that
        // stays on top of the video.
        if (prefs.timeDisplayPosition != "off") {
            clockDisplay.visibility = View.VISIBLE
            persistentClock.visibility = View.VISIBLE
        }

        // Стрелка "Назад" в плеере: возвращаемся не в пустой экран Каналов,
        // а в текущий плейлист с подсвеченным текущим каналом. Сам индекс
        // сохраняется в ChannelDataHolder.currentChannelIndex при каждом
        // переключении канала, ChannelsFragment подхватит его.
        btnBack.setOnClickListener {
            ChannelDataHolder.openDrawerOnReturn = false
            ChannelDataHolder.returnToTabIndex = 1 // index of Channels
            finish()
        }

        // Reset the auto-hide timer whenever the user moves focus between
        // any top-bar / control buttons or interacts with the layout.
        // Without this, after 5 s of "navigating" the bar disappears even
        // though the user is actively trying to use it.
        val keepAliveListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) scheduleHideControls()
        }
        for (i in 0 until topBar.childCount) {
            topBar.getChildAt(i).onFocusChangeListener = keepAliveListener
        }
        // Same for the centre play / nav row, which has buttons too.
        // Сохраняем ссылку чтобы снять листенер в onDestroy — иначе
        // ViewTreeObserver удерживает Activity (Round 129 audit).
        controlsFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && controlsVisible &&
                (isFocusInTopBar() || isInsideControlsOverlay(newFocus))) {
                scheduleHideControls()
            }
        }
        controlsOverlay.viewTreeObserver.addOnGlobalFocusChangeListener(controlsFocusListener)

        btnPlayPause.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                    btnPlayPause.setImageResource(R.drawable.ic_play)
                } else {
                    p.play()
                    btnPlayPause.setImageResource(R.drawable.ic_pause)
                }
            }
            scheduleHideControls()
        }

        findViewById<ImageButton>(R.id.btnPip).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setOnClickListener { enterPipMode() }
            } else {
                visibility = View.GONE
            }
        }

        findViewById<ImageButton>(R.id.btnAspectRatio).setOnClickListener {
            cycleAspectRatio()
            scheduleHideControls()
        }

        findViewById<ImageButton>(R.id.btnChannelList).setOnClickListener {
            toggleChannelList()
        }

        // Lock button
        btnLock.setOnClickListener {
            toggleScreenLock()
        }

        // Audio track selection
        btnAudioTrack.setOnClickListener {
            showAudioTrackDialog()
            scheduleHideControls()
        }

        // Speed button
        btnSpeed.setOnClickListener {
            cycleSpeed()
            scheduleHideControls()
        }

        // Lock overlay - tap to unlock
        lockOverlay.setOnClickListener {
            showUnlockHint()
        }

        findViewById<ImageButton>(R.id.btnUnlock)?.setOnClickListener {
            toggleScreenLock()
        }

        findViewById<ImageButton>(R.id.btnPrevChannel).setOnClickListener { switchChannel(-1) }
        findViewById<ImageButton>(R.id.btnNextChannel).setOnClickListener { switchChannel(1) }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRetry).setOnClickListener {
            errorLayout.visibility = View.GONE
            currentUrl?.let { playStream(it) }
        }

        controlsOverlay.setOnClickListener { toggleControls() }

        // Channel list overlay
        overlayChannelsList.layoutManager = LinearLayoutManager(this)
        findViewById<View>(R.id.channelListDimBg).setOnClickListener { hideChannelList() }

        overlaySearchEdit = findViewById(R.id.overlaySearchEdit)
        overlayChannelCount = findViewById(R.id.overlayChannelCount)

        overlaySearchEdit?.setOnFocusChangeListener { _, hasFocus ->
            // Когда юзер уходит из поиска — возобновляем idle-таймер.
            // Когда заходит — таймер сам отключится в scheduleHideChannelList.
            if (!hasFocus && channelListVisible) scheduleHideChannelList()
            else if (hasFocus) channelListHideHandler.removeCallbacks(channelListHideRunnable)
        }

        overlaySearchEdit?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterOverlayChannels()
                bumpChannelListIdleTimer()
            }
        })

        // Reset auto-hide timer on any scroll/touch in the channel list
        overlayChannelsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0 || dy != 0) bumpChannelListIdleTimer()
            }
        })

        // Categories: ВЕРТИКАЛЬНЫЙ список слева от списка каналов.
        // На пульте: 1-е DPAD_LEFT — открывает список каналов,
        // 2-е DPAD_LEFT — переводит фокус в этот столбец категорий,
        // 3-е — открывает левое боковое меню плеера. Если у плейлиста
        // нет категорий — overlayCategoriesPanel скрыт, и 2-е DPAD_LEFT
        // сразу открывает боковое меню.
        overlayCategoriesPanel = findViewById(R.id.overlayCategoriesPanel)
        overlayCategoriesList = findViewById<RecyclerView>(R.id.overlayCategoriesList)
        overlayCategoriesList.layoutManager = LinearLayoutManager(this)
        // Категории по умолчанию СКРЫТЫ. Появляются только после
        // второго DPAD_LEFT (если у плейлиста они есть). Раньше панель
        // была сразу видна вместе со списком каналов, что юзер посчитал
        // лишним: первое LEFT должно показывать ТОЛЬКО список каналов.
        overlayCategoriesPanel.visibility = View.GONE
        // Собираем категории из текущего плейлиста: первый сегмент
        // group-tag (до ; , |), уникальные, отсортированные.
        val channels = ChannelDataHolder.allChannels
        val realCats = channels.mapNotNull { it.group?.split(';', ',', '|')?.firstOrNull()?.trim() }
            .filter { it.isNotEmpty() && it.length <= 30 }
            .distinct()
            .sorted()
        val cats = listOf(getString(R.string.all)) + realCats
        val catAdapter = CategoryAdapter(cats) { category ->
            overlaySelectedCategory = category
            filterOverlayChannels()
            // После выбора категории — скрываем панель категорий и
            // возвращаем список каналов с применённым фильтром.
            // Юзер видит "только подходящие каналы" сразу после выбора.
            overlayCategoriesPanel.visibility = View.GONE
            // Возвращаем категориям дефолтную ширину (могла быть 240dp).
            val lpCats = overlayCategoriesPanel.layoutParams
            lpCats.width = (140 * resources.displayMetrics.density).toInt()
            overlayCategoriesPanel.layoutParams = lpCats
            findViewById<View>(R.id.overlayChannelsPanel)?.visibility = View.VISIBLE
            // Переводим фокус на первый канал в отфильтрованном списке.
            overlayChannelsList.post {
                overlayChannelsList.requestFocus()
                overlayChannelsList.findViewHolderForAdapterPosition(0)
                    ?.itemView?.requestFocus()
            }
            bumpChannelListIdleTimer()
        }
        overlayCategoriesList.adapter = catAdapter

        // Любая прокрутка ленты категорий или смена фокуса внутри неё
        // продлевает таймер автоскрытия — иначе пользователь не успевает
        // выбрать категорию, пока перемещается по чипам.
        overlayCategoriesList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0 || dy != 0) bumpChannelListIdleTimer()
            }
        })
        // Тот же подход — сохраняем listener чтобы снять в onDestroy.
        overlayFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && channelListVisible) {
                var p: View? = newFocus
                while (p != null) {
                    if (p == channelListOverlay) { bumpChannelListIdleTimer(); break }
                    p = p.parent as? View
                }
            }
        }
        overlayCategoriesList.viewTreeObserver.addOnGlobalFocusChangeListener(overlayFocusListener)

        setupOverlayChannelList()
    }

    private var overlaySelectedCategory: String = ""

    /** Универсальный обработчик клика по строке overlay. Принимает
     *  позицию из адаптера (позицию в overlayFilteredChannels) и
     *  ищет реальный индекс в ChannelDataHolder.allChannels по URL.
     *  Так работает корректно и для unfiltered, и для filtered
     *  списка — даже если адаптер реюзается через updateChannels
     *  (в Round 131 я этот случай упустил, и filtered click открывал
     *  канал из общего плейлиста).  */
    private fun handleOverlayClick(posInList: Int) {
        val ch = overlayFilteredChannels.getOrNull(posInList) ?: return
        val realIdx = ChannelDataHolder.allChannels.indexOfFirst { it.url == ch.url }
        if (realIdx >= 0) {
            switchToChannel(realIdx)
            hideChannelList()
        }
    }

    private fun setupOverlayChannelList() {
        val channels = ChannelDataHolder.allChannels
        if (channels.isEmpty()) return

        overlaySelectedCategory = getString(R.string.all)
        overlayFilteredChannels = channels
        overlayFilteredIndices = channels.indices.toList()
        overlayChannelCount?.text = "${channels.size}"
        // Имя плейлиста над списком каналов: если есть — показываем,
        // иначе "Каналы" из шаблона. Имя категории — только когда
        // выбрана конкретная (не "Все"); ставится в filterOverlayChannels.
        findViewById<TextView>(R.id.overlayPlaylistName)?.text =
            prefs.lastPlaylistName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.channels)
        findViewById<TextView>(R.id.overlaySelectedCategoryLabel)?.visibility = View.GONE

        overlayAdapter = OverlayChannelAdapter(channels, ChannelDataHolder.epgData, currentIndex,
            favorites = prefs.favorites,
            onChannelClick = { pos -> handleOverlayClick(pos) },
            onFavoriteClick = { channel -> toggleFavorite(channel) },
            onShowDetailsClick = { channel -> showChannelDetailsDialog(channel) }
        )
        overlayChannelsList.adapter = overlayAdapter
    }

    private fun toggleFavorite(channel: Channel) {
        if (prefs.isFavorite(channel.url)) {
            prefs.removeFavorite(channel.url)
        } else {
            prefs.addFavorite(channel)  // полный Channel, не только URL
        }
        overlayAdapter?.updateFavorites(prefs.favorites)
    }

    private fun filterOverlayChannels() {
        val channels = ChannelDataHolder.allChannels
        if (channels.isEmpty()) return

        val query = overlaySearchEdit?.text?.toString()?.trim()?.lowercase() ?: ""
        val allLabel = getString(R.string.all)

        val filtered = channels.withIndex().filter { (_, ch) ->
            val matchesSearch = query.isEmpty() || ch.name.lowercase().contains(query)
            // Сравниваем по canonical group (первый сегмент до ';,|')
            // чтобы chip "Culture" матчил каналы tagged "Culture;Education".
            val canonicalGroup = ch.group?.split(';', ',', '|')?.firstOrNull()?.trim()
            val matchesCat = overlaySelectedCategory.isEmpty() || overlaySelectedCategory == allLabel ||
                canonicalGroup == overlaySelectedCategory
            matchesSearch && matchesCat
        }

        overlayFilteredChannels = filtered.map { it.value }
        overlayFilteredIndices = filtered.map { it.index }
        overlayChannelCount?.text = "${overlayFilteredChannels.size}"

        // Показываем выбранную категорию под именем плейлиста.
        // Когда "Все" — скрываем, чтобы не было лишнего текста.
        val catLabel = findViewById<TextView>(R.id.overlaySelectedCategoryLabel)
        if (overlaySelectedCategory.isNotEmpty() && overlaySelectedCategory != allLabel) {
            catLabel?.text = "▸ $overlaySelectedCategory"
            catLabel?.visibility = View.VISIBLE
        } else {
            catLabel?.visibility = View.GONE
        }

        // Find current channel position in filtered list
        val filteredCurrentIndex = overlayFilteredIndices.indexOf(currentIndex)

        // Audit #10: переиспользуем существующий adapter если он есть
        // (вместо создания нового на каждый keystroke в поиске).
        // Старые адаптеры собирали мусор в GC и вызывали jank.
        val existing = overlayAdapter
        if (existing != null && overlayChannelsList.adapter === existing) {
            existing.updateChannels(overlayFilteredChannels, filteredCurrentIndex)
            existing.updateFavorites(prefs.favorites)
        } else {
            overlayAdapter = OverlayChannelAdapter(overlayFilteredChannels, ChannelDataHolder.epgData, filteredCurrentIndex,
                favorites = prefs.favorites,
                onChannelClick = { pos -> handleOverlayClick(pos) },
                onFavoriteClick = { channel -> toggleFavorite(channel) },
                onShowDetailsClick = { channel -> showChannelDetailsDialog(channel) }
            )
            overlayChannelsList.adapter = overlayAdapter
        }
    }

    /** Показывает inline-панель с деталями программы (не отдельное
     *  окно). BACK или DPAD_LEFT — закрывают её, фокус возвращается
     *  на "избранное" в списке. */
    /** View в списке каналов которое было сфокусировано когда юзер
     *  открыл detail-панель. При закрытии возвращаем фокус именно
     *  туда, а не на верх списка. */
    private var detailsReturnFocus: View? = null

    private fun showChannelDetailsDialog(channel: Channel) {
        val panel = findViewById<View>(R.id.channelDetailsPanel) ?: return
        // Сдвигаем details-панель ВПРАВО на ширину видимых левых
        // панелей: если есть категории — 140 + 320 = 460dp, иначе
        // только 320dp. Иначе она перекрывает список каналов.
        val catsVisible = ::overlayCategoriesPanel.isInitialized &&
            overlayCategoriesPanel.visibility == View.VISIBLE
        val marginDp = if (catsVisible) 460 else 320
        val lp = panel.layoutParams as? android.widget.FrameLayout.LayoutParams
        lp?.let {
            it.marginStart = (marginDp * resources.displayMetrics.density).toInt()
            panel.layoutParams = it
        }
        // Запомним кто сейчас в фокусе — обычно это сердечко "избранное"
        // конкретной строки. Туда же вернёмся при закрытии.
        detailsReturnFocus = currentFocus
        val (now, next) = EpgRepository.getNowNextDetailed(
            ChannelDataHolder.epgData, channel.tvgId, channel.name
        )
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val dateFmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())

        findViewById<android.widget.TextView>(R.id.detailsChannelName).text = channel.name

        val nowTimeView = findViewById<android.widget.TextView>(R.id.detailsNowTime)
        val nowTitleView = findViewById<android.widget.TextView>(R.id.detailsNowTitle)
        val nowDescView = findViewById<android.widget.TextView>(R.id.detailsNowDesc)
        val nowLabel = findViewById<android.widget.TextView>(R.id.detailsNowLabel)
        if (now != null) {
            nowLabel.visibility = View.VISIBLE
            nowTimeView.visibility = View.VISIBLE
            nowTitleView.visibility = View.VISIBLE
            nowTimeView.text = "${timeFmt.format(java.util.Date(now.start))} – ${timeFmt.format(java.util.Date(now.end))}"
            nowTitleView.text = now.title
            if (now.description.isNotEmpty()) {
                nowDescView.visibility = View.VISIBLE
                nowDescView.text = now.description
            } else {
                nowDescView.visibility = View.GONE
            }
        } else {
            nowLabel.visibility = View.GONE
            nowTimeView.visibility = View.GONE
            nowDescView.visibility = View.GONE
            nowTitleView.visibility = View.VISIBLE
            nowTitleView.text = getString(R.string.no_current_program_data)
        }

        val nextTimeView = findViewById<android.widget.TextView>(R.id.detailsNextTime)
        val nextTitleView = findViewById<android.widget.TextView>(R.id.detailsNextTitle)
        val nextLabel = findViewById<android.widget.TextView>(R.id.detailsNextLabel)
        if (next != null) {
            nextLabel.visibility = View.VISIBLE
            nextTimeView.visibility = View.VISIBLE
            nextTitleView.visibility = View.VISIBLE
            nextTimeView.text = dateFmt.format(java.util.Date(next.start))
            nextTitleView.text = next.title
        } else {
            nextLabel.visibility = View.GONE
            nextTimeView.visibility = View.GONE
            nextTitleView.visibility = View.GONE
        }

        panel.visibility = View.VISIBLE
        panel.requestFocus()
        panel.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_BACK -> {
                        hideChannelDetailsPanel()
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    private fun hideChannelDetailsPanel() {
        val panel = findViewById<View>(R.id.channelDetailsPanel) ?: return
        if (panel.visibility != View.VISIBLE) return
        panel.visibility = View.GONE
        panel.setOnKeyListener(null)
        // Возвращаем фокус на ту View, что была активна перед открытием
        // (обычно сердечко конкретной строки в списке), чтобы юзер
        // оказался в той же строке откуда пришёл.
        val ret = detailsReturnFocus
        detailsReturnFocus = null
        if (ret != null && ret.isAttachedToWindow) {
            ret.requestFocus()
        } else {
            findViewById<View>(R.id.overlayChannelsList)?.requestFocus()
        }
    }

    // === Gesture support (volume/brightness) ===

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isScreenLocked) {
                    showUnlockHint()
                    return true
                }
                if (channelListVisible) {
                    hideChannelList()
                    return true
                }
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isScreenLocked) return true
                // Double tap left/right to switch channels
                val screenWidth = playerView.width
                if (e.x < screenWidth / 3f) {
                    switchChannel(-1)
                } else if (e.x > screenWidth * 2f / 3f) {
                    switchChannel(1)
                } else {
                    // Double tap center to play/pause
                    player?.let { p ->
                        if (p.isPlaying) {
                            p.pause()
                            btnPlayPause.setImageResource(R.drawable.ic_play)
                        } else {
                            p.play()
                            btnPlayPause.setImageResource(R.drawable.ic_pause)
                        }
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isScreenLocked) return
                // Long press to show channel list
                if (!channelListVisible) {
                    showChannelList()
                }
            }
        })

        playerView.setOnTouchListener { _, event ->
            if (isScreenLocked) {
                gestureDetector?.onTouchEvent(event)
                return@setOnTouchListener true
            }

            gestureDetector?.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartY = event.y
                    isSwipingVolume = false
                    isSwipingBrightness = false

                    val screenWidth = playerView.width
                    if (event.x > screenWidth / 2f) {
                        // Right side - volume
                        swipeStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    } else {
                        // Left side - brightness
                        swipeStartBrightness = window.attributes.screenBrightness
                        if (swipeStartBrightness < 0) {
                            swipeStartBrightness = try {
                                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                            } catch (e: Exception) { 0.5f }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = swipeStartY - event.y
                    val screenWidth = playerView.width
                    val screenHeight = playerView.height

                    if (abs(dy) > 30 && !channelListVisible) {
                        if (event.x > screenWidth / 2f) {
                            // Volume control (right side)
                            isSwipingVolume = true
                            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val volumeChange = (dy / screenHeight * maxVolume * 1.5f).toInt()
                            val newVolume = (swipeStartVolume + volumeChange).coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                            showGestureIndicator(
                                R.drawable.ic_volume,
                                "${getString(R.string.volume)}: ${(newVolume * 100 / maxVolume)}%",
                                newVolume * 100 / maxVolume
                            )
                        } else {
                            // Brightness control (left side)
                            isSwipingBrightness = true
                            val brightnessChange = dy / screenHeight
                            val newBrightness = (swipeStartBrightness + brightnessChange).coerceIn(0.01f, 1f)
                            val layoutParams = window.attributes
                            layoutParams.screenBrightness = newBrightness
                            window.attributes = layoutParams
                            showGestureIndicator(
                                R.drawable.ic_brightness,
                                "${getString(R.string.brightness)}: ${(newBrightness * 100).toInt()}%",
                                (newBrightness * 100).toInt()
                            )
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSwipingVolume || isSwipingBrightness) {
                        hideGestureIndicator()
                    }
                    isSwipingVolume = false
                    isSwipingBrightness = false
                }
            }
            true
        }
    }

    private fun showGestureIndicator(iconRes: Int, text: String, progress: Int) {
        gestureIcon.setImageResource(iconRes)
        gestureText.text = text
        gestureProgress.progress = progress
        gestureIndicator.visibility = View.VISIBLE
    }

    private fun hideGestureIndicator() {
        Handler(Looper.getMainLooper()).postDelayed({
            gestureIndicator.visibility = View.GONE
        }, 500)
    }

    // === Screen lock ===

    private fun toggleScreenLock() {
        isScreenLocked = !isScreenLocked
        if (isScreenLocked) {
            hideControls()
            lockOverlay.visibility = View.VISIBLE
            Toast.makeText(this, getString(R.string.screen_locked), Toast.LENGTH_SHORT).show()
        } else {
            lockOverlay.visibility = View.GONE
            showControls()
            Toast.makeText(this, getString(R.string.screen_unlocked), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUnlockHint() {
        val btnUnlock = lockOverlay.findViewById<ImageButton>(R.id.btnUnlock)
        btnUnlock?.visibility = View.VISIBLE
        Handler(Looper.getMainLooper()).postDelayed({
            btnUnlock?.visibility = View.GONE
        }, 3000)
    }

    // === Audio track selection ===

    private fun showAudioTrackDialog() {
        val p = player ?: return
        val tracks = p.currentTracks

        // Собираем ВСЕ аудио-дорожки: и поддерживаемые, и нет. Раньше
        // отфильтрованные по поддержке дорожки скрывались, и при
        // не-AAC-потоках (AC3/EAC3 без аппаратного декодера) список
        // оказывался пустым — пользователь видел "no audio tracks", хоть
        // дорожка в потоке есть. Помечаем неподдерживаемые как
        // "неподдерж." — пускай хотя бы видно, что есть в потоке.
        val audioTracks = mutableListOf<Pair<String, Int>>() // label, groupIndex
        var groupIndex = 0
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val supported = group.isTrackSupported(i)
                    val label = buildString {
                        append(format.label ?: format.language ?: "Track ${audioTracks.size + 1}")
                        format.codecs?.let { append(" [$it]") }
                            ?: format.sampleMimeType?.let { append(" [${it.substringAfter('/')}]") }
                        if (format.channelCount > 0) append(" (${format.channelCount}ch)")
                        if (format.sampleRate > 0) append(" ${format.sampleRate / 1000}kHz")
                        if (!supported) append(" — неподдерж.")
                    }
                    audioTracks.add(label to groupIndex)
                }
                groupIndex++
            } else {
                groupIndex++
            }
        }

        if (audioTracks.isEmpty()) {
            // Соберём диагностику: ExoPlayer не нашёл ни одной аудио-
            // группы. Показываем что есть в потоке, чтобы видеть
            // действительно ли аудио отсутствует или её просто не парсит
            // экстрактор.
            val diag = buildString {
                append(getString(R.string.no_audio_tracks))
                append("\n")
                if (tracks.groups.isEmpty()) {
                    append("В потоке нет дорожек вовсе.")
                } else {
                    append("В потоке найдено: ")
                    val parts = tracks.groups.mapIndexed { idx, g ->
                        val type = when (g.type) {
                            C.TRACK_TYPE_VIDEO -> "video"
                            C.TRACK_TYPE_AUDIO -> "audio"
                            C.TRACK_TYPE_TEXT -> "text"
                            else -> "type${g.type}"
                        }
                        val mime = if (g.length > 0) g.getTrackFormat(0).sampleMimeType ?: "?" else "?"
                        "$type[$mime]"
                    }
                    append(parts.joinToString(", "))
                }
            }
            Toast.makeText(this, diag, Toast.LENGTH_LONG).show()
            return
        }

        val names = audioTracks.map { it.first }.toTypedArray()
        // Определяем индекс текущей выбранной дорожки, чтобы подсветить
        // её в диалоге (раньше использовался setItems — без подсветки,
        // пользователь не понимал что выбрано).
        var currentSelectedIdx = -1
        var counter = 0
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        currentSelectedIdx = counter
                    }
                    counter++
                }
            }
        }
        // Заменили setSingleChoiceItems на FocusableDialog: тот же
        // селектор bg_dialog_list_item, что и во всех остальных
        // диалогах списка, надёжный фокус через D-pad.
        val dialog = FocusableDialog.show(
            this,
            getString(R.string.audio_track),
            names.toList().toTypedArray(),
            currentSelectedIdx
        ) { which ->
            selectAudioTrack(which)
        }
        // Anchor to the right side as a narrow side sheet so it doesn't
        // span the whole screen ("слишком растянуто в лево").
        dialog.window?.let { w ->
            val params = w.attributes
            params.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            params.width = (resources.displayMetrics.widthPixels * 0.32f).toInt().coerceIn(320, 520)
            params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            w.attributes = params
        }
    }

    private fun selectAudioTrack(trackIndex: Int) {
        val p = player ?: return
        var audioGroupIdx = 0
        var audioTrackIdx = 0
        var currentAudioTrack = 0

        for (group in p.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    if (currentAudioTrack == trackIndex) {
                        val override = TrackSelectionOverride(group.mediaTrackGroup, i)
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(override)
                            .build()
                        Toast.makeText(this, "${getString(R.string.audio_track)}: ${group.getTrackFormat(i).label ?: "Track ${trackIndex + 1}"}", Toast.LENGTH_SHORT).show()
                        return
                    }
                    currentAudioTrack++
                }
            }
        }
    }

    // === Playback speed ===

    private fun cycleSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speedValues.size
        val speed = speedValues[currentSpeedIndex]
        player?.playbackParameters = PlaybackParameters(speed)
        Toast.makeText(this, "${getString(R.string.playback_speed)}: ${speedLabels[currentSpeedIndex]}", Toast.LENGTH_SHORT).show()
    }

    /** Round 191: запоминаем bufferMode с которым был создан текущий
     *  ExoPlayer — onResume сравнивает, и если юзер поменял в Settings,
     *  пересоздаёт плеер чтобы новый LoadControl действительно вступил
     *  в силу. Раньше юзер жаловался "буфер не влияет ни на что". */
    private var playerBufferModeAtInit: String? = null

    private fun initPlayer() {
        playerBufferModeAtInit = prefs.bufferMode
        // На X4 X4 (256MB heap, слабый ARM) дефолтные буферы Media3
        // (50/50 сек) держат много декодированного видео, GC дёргает,
        // отсюда плеер залипает. Снижаем минимум до 8 сек, максимум
        // до 25, чтобы:
        //  - быстрее стартовать (меньше начальный буфер ⇒ меньше
        //    "паузу включаю..." при переключении канала),
        //  - меньше памяти держать при играх в фоне (Coil + EPG
        //    парсер не вытесняли видео-чанки из heap),
        //  - реже проваливать кадры из-за GC.
        // bufferForPlaybackMs (1500мс) — сколько НУЖНО буфера чтобы
        // СТАРТОВАТЬ воспроизведение; rebuffer (2500мс) — сколько
        // нужно чтобы продолжить ПОСЛЕ стagger'а. Ниже 1.5/2.5 сек
        // нельзя — на нестабильных IPTV-стримах уйдёт в постоянный
        // ребуфер.
        // Round 191: режимы реально отличаются. "Низкий" — мгновенный
        // старт ценой возможной редкой ребуферизации; "Обычный" —
        // быстрый, чуть стабильнее; "Высокий" — для нестабильной сети.
        val loadControl = when (prefs.bufferMode) {
            "low" -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 8000, 100, 800)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            "high" -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(20000, 40000, 2500, 4000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            else -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(6000, 18000, 200, 1500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }

        // Apply the user-configured User-Agent + Referer to every HTTP
        // request. Many regional streams (especially Azerbaijani / CIS)
        // reject the default ExoPlayer UA or require a same-origin
        // Referer; default Referer is derived from the stream URL's
        // origin so that case "just works" out of the box.
        // Round 190: используем OkHttpDataSource поверх ОДНОГО общего
        // OkHttpClient. Главный профит — TCP/TLS connection pool:
        // переключение каналов на том же CDN переиспользует
        // существующее keep-alive соединение → нет нового handshake →
        // переключение почти мгновенное. DefaultHttpDataSource на
        // каждый запрос открывал новый сокет.
        val httpDataSourceFactory = androidx.media3.datasource.okhttp
            .OkHttpDataSource.Factory(sharedStreamHttpClient)
            .setUserAgent(prefs.userAgent)
        val headers = HashMap<String, String>()
        prefs.httpReferer.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        if (headers.isNotEmpty()) httpDataSourceFactory.setDefaultRequestProperties(headers)
        val wrappedFactory = androidx.media3.datasource.DataSource.Factory {
            val ds = httpDataSourceFactory.createDataSource()
            val streamUrl = currentUrl
            // Per-channel User-Agent: если для этого канала задан свой
            // UA (через "HTTP заголовки" в правом меню) — используем
            // его поверх глобального. Помогает каналам с уникальными
            // требованиями (например izone-стримы хотят VLC, ucoz —
            // Chrome и т.д.).
            if (!streamUrl.isNullOrBlank()) {
                val perChannelUa = prefs.getChannelUserAgent(streamUrl)
                if (perChannelUa != prefs.userAgent) {
                    ds.setRequestProperty("User-Agent", perChannelUa)
                }
            }
            // Auto-Referer: если пользователь ничего не настроил,
            // используем scheme://host стрима (tv.izone.az и пр.).
            if (prefs.httpReferer.isBlank() && !streamUrl.isNullOrBlank()) {
                try {
                    val u = java.net.URI(streamUrl)
                    val origin = "${u.scheme}://${u.host}" +
                        (if (u.port > 0) ":${u.port}" else "") + "/"
                    ds.setRequestProperty("Referer", origin)
                    ds.setRequestProperty("Origin", origin.trimEnd('/'))
                } catch (_: Exception) {}
            }
            ds
        }
        // Stash so playStream's createMediaSourceFor() can build sources.
        this.streamDataFactory = wrappedFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(wrappedFactory)

        // Round 183: возвращаем NextRenderersFactory из nextlib —
        // софтверные FFmpeg-декодеры для MP2 / AC3 / EAC3 / DTS / FLAC /
        // Vorbis. Без них на DVB-каналах системный MediaCodec пишет
        // "звук не поддерживается".
        // Round 184: режим ON, не PREFER. PREFER заставлял ExoPlayer
        // выбирать FFmpeg ПЕРВЫМ — в т.ч. для видео — отсюда возврат
        // запинки ARB (HEVC 1080p на X4 X4 софтверно не вытягивает).
        // ON: hardware MediaCodec выбирается первым; FFmpeg
        // подключается ТОЛЬКО если MediaCodec.supportsFormat=false
        // (т.е. MP2/AC3/EAC3 аудио на железе нет → fallback на FFmpeg).
        // Видео всегда идёт через hardware-декодер.
        val renderersFactory = io.github.anilbeesetti.nextlib.media3ext.ffdecoder
            .NextRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )

        // Track-selection: предпочтительный язык + ограничение
        // максимального видео-битрейта чтобы железо X4 X4 не пыталось
        // декодить 4K-чанки которые не вытянет → залипания.
        // 1080p VP9/H.264 ~6 Mbps — потолок для большинства TV-боксов.
        // Track-selection: ограничение максимального разрешения видео
        // в зависимости от prefs.preferredQuality. Это основная защита
        // от запинающихся каналов на слабом железе X4 X4: если стрим
        // имеет 4K-вариант а декодер его не вытягивает, ABR держит
        // 4K и кадры пропускаются. Лимит МАКСИМАЛЬНОЙ высоты заставит
        // ExoPlayer выбрать 1080p (или 720p) и стрим будет ровный.
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this)
        val (maxW, maxH) = when (prefs.preferredQuality) {
            "1080p" -> 1920 to 1080
            "720p"  -> 1280 to 720
            "4k"    -> 3840 to 2160
            else    -> 1920 to 1080  // auto = по умолчанию ограничиваем 1080p
                                      // на X4 X4 чтобы не было stutter'ов на 4K
        }
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setMaxVideoSize(maxW, maxH)
            .build()

        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            // Round 183: явный AudioAttributes + handleAudioFocus=true.
            // Без них на некоторых TV-боксах Android AudioService не
            // выдаёт audio focus автоматически и поток молчит. Также
            // handleAudioBecomingNoisy: при отсоединении наушников/HDMI
            // плеер ставится на паузу вместо продолжения "в воздух".
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build().also { p ->
                playerView.player = p
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                loadingIndicator.visibility = View.VISIBLE
                                errorLayout.visibility = View.GONE
                            }
                            Player.STATE_READY -> {
                                loadingIndicator.visibility = View.GONE
                                errorLayout.visibility = View.GONE
                                btnPlayPause.setImageResource(R.drawable.ic_pause)
                                updateAudioTrackInfo()
                                // Successful playback resets the back-off
                                reconnectAttempts = 0
                                reconnectHandler.removeCallbacks(reconnectRunnable)
                            }
                            Player.STATE_ENDED -> {
                                loadingIndicator.visibility = View.GONE
                                // Live streams shouldn't normally end —
                                // treat unexpected end as a transient
                                // failure and reconnect.
                                scheduleReconnect()
                            }
                            Player.STATE_IDLE -> {
                                loadingIndicator.visibility = View.GONE
                            }
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        ensureAudioTrackSelected(tracks)
                        updateAudioTrackInfo()
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        autoApplyAspectIfNeeded(videoSize)
                        updateResolutionLabel(videoSize)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        loadingIndicator.visibility = View.GONE
                        // BehindLiveWindowException — HLS отстал от live-окна;
                        // не разрушаем источник, просто прыгаем к live-edge.
                        // Ошибку не логируем: она ожидаема и обрабатывается.
                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            try {
                                player?.seekToDefaultPosition()
                                player?.prepare()
                                // Safety net на случай если seek не вернул
                                // нас в live; STATE_READY отменит этот таймер.
                                reconnectHandler.postDelayed(reconnectRunnable, 5_000)
                                return
                            } catch (_: Exception) {}
                        }
                        ErrorLogger.logException(this@PlayerActivity, error)
                        // Декодер не нашёлся (типичный случай — MP2-аудио
                        // на дешёвых TV-боксах без MP2 MediaCodec'а).
                        // Reconnect не поможет — формат не изменится.
                        // Предлагаем открыть канал во внешнем плеере.
                        if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED) {
                            offerExternalPlayer(error)
                            return
                        }
                        scheduleReconnect()
                    }
                })
            }
    }

    /**
     * Декодер не нашёлся — обычно MP2-аудио на TV-боксах без
     * software-декодера. Предлагаем пользователю запустить канал
     * во внешнем плеере (VLC / MX), который декодирует MP2.
     */
    /**
     * Per-channel User-Agent. Пользователь выбирает пресет (VLC,
     * Chrome, Firefox, Android, FFmpeg) или "По умолчанию" (использует
     * глобальный prefs.userAgent). После выбора плеер перезапускает
     * стрим с новым UA, чтобы канал сразу подхватил его.
     */
    private fun showChannelHttpDialog() {
        val url = currentUrl ?: return
        val current = AppPreferences(this).getChannelState(url).optString("ua", "")
        val labels = UA_PRESETS.map { (_, key) ->
            resources.getIdentifier(key, "string", packageName)
                .let { if (it != 0) getString(it) else key }
        }.toTypedArray()
        val checkedIdx = UA_PRESETS.indexOfFirst { it.first == current }
            .let { if (it < 0) 0 else it }
        // FocusableDialog даёт ту же подсветку строк через bg_dialog_list_item
        // что и в остальных диалогах настройки.
        FocusableDialog.show(
            this,
            getString(R.string.user_agent),
            labels.toList().toTypedArray(),
            checkedIdx
        ) { which ->
            val (uaValue, _) = UA_PRESETS[which]
            prefs.setChannelUserAgent(url, uaValue.ifEmpty { null })
            Toast.makeText(
                this,
                getString(R.string.channel_ua_set, labels[which]),
                Toast.LENGTH_SHORT
            ).show()
            // Перезапускаем стрим, чтобы новый UA сразу применился.
            playStream(url)
        }
    }

    private fun offerExternalPlayer(error: PlaybackException) {
        val url = currentUrl ?: return
        errorLayout.visibility = View.VISIBLE
        val codec = (error.cause as? MediaCodecRenderer.DecoderInitializationException)
            ?.mimeType?.substringAfter('/') ?: "?"
        errorText.text = getString(R.string.codec_unsupported_short, codec)
        android.app.AlertDialog.Builder(this, R.style.Theme_TVViewer_Dialog)
            .setTitle(R.string.codec_unsupported_title)
            .setMessage(getString(R.string.codec_unsupported_message, codec))
            .setPositiveButton(R.string.open_external) { _, _ ->
                if (launchExternalVideo(url)) finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun scheduleReconnect() {
        reconnectHandler.removeCallbacks(reconnectRunnable)
        if (currentUrl == null) return
        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT) {
            // Give up: show the manual retry button
            errorLayout.visibility = View.VISIBLE
            errorText.text = getString(R.string.error_playback)
            return
        }
        // Exponential back-off, capped at 30s: 1s, 2s, 4s, 8s, 16s, 30s, 30s …
        val delayMs = (1000L shl (reconnectAttempts - 1).coerceAtMost(5)).coerceAtMost(30_000)
        errorLayout.visibility = View.VISIBLE
        errorText.text = getString(R.string.reconnecting) +
            " (${reconnectAttempts}/$MAX_RECONNECT)"
        reconnectHandler.postDelayed(reconnectRunnable, delayMs)
    }

    /**
     * Многие HLS-стримы (izone.az, региональные порталы CIS) объявляют
     * аудио в отдельном #EXT-X-MEDIA:TYPE=AUDIO без флага DEFAULT=YES,
     * и ExoPlayer не выбирает ни одну дорожку — звук пропадает, видео
     * идёт. Здесь вручную включаем первую поддерживаемую аудио-дорожку,
     * если автовыбор оставил всё выключенным.
     */
    private fun ensureAudioTrackSelected(tracks: Tracks) {
        val p = player ?: return
        val hasSelectedAudio = tracks.groups.any { g ->
            g.type == C.TRACK_TYPE_AUDIO && g.isSelected
        }
        if (hasSelectedAudio) return
        val firstSupported = tracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && hasAnySupportedTrack(it) }
            ?: return
        for (i in 0 until firstSupported.length) {
            if (firstSupported.isTrackSupported(i)) {
                val override = TrackSelectionOverride(firstSupported.mediaTrackGroup, i)
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(override)
                    .build()
                Log.d("PlayerActivity", "Force-selected audio track: ${firstSupported.getTrackFormat(i).label ?: firstSupported.getTrackFormat(i).language}")
                return
            }
        }
    }

    private fun hasAnySupportedTrack(group: Tracks.Group): Boolean {
        for (i in 0 until group.length) {
            if (group.isTrackSupported(i)) return true
        }
        return false
    }

    private fun updateAudioTrackInfo() {
        val p = player ?: return
        var audioCount = 0
        for (group in p.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                audioCount += group.length
            }
        }
        if (audioCount > 1) {
            btnAudioTrack.visibility = View.VISIBLE
            audioTrackInfo.text = "$audioCount"
            audioTrackInfo.visibility = View.VISIBLE
        } else {
            audioTrackInfo.visibility = View.GONE
        }
    }

    /**
     * Build a MediaItem with a sensible mime-type hint so ExoPlayer picks
     * the right source factory. Many IPTV portals serve HLS at URLs that
     * don't end in .m3u8 (e.g. http://host:8080/play/abc?token=xxx). With
     * no hint, ExoPlayer treats them as progressive and fails with
     * UnrecognizedInputFormatException. We default to HLS for any URL
     * that has no clearly-progressive extension.
     */
    private fun buildMediaItem(url: String): MediaItem {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val mime = when {
            path.contains(".m3u8") || path.contains(".m3u") ->
                androidx.media3.common.MimeTypes.APPLICATION_M3U8
            path.contains(".mpd") ->
                androidx.media3.common.MimeTypes.APPLICATION_MPD
            // Clearly progressive containers — keep them progressive.
            path.endsWith(".mp4") || path.endsWith(".m4v") ->
                androidx.media3.common.MimeTypes.VIDEO_MP4
            path.endsWith(".webm") ->
                androidx.media3.common.MimeTypes.VIDEO_WEBM
            path.endsWith(".flv") ->
                androidx.media3.common.MimeTypes.VIDEO_FLV
            path.endsWith(".mkv") ->
                androidx.media3.common.MimeTypes.VIDEO_MATROSKA
            // RTMP / RTSP — leave to default detection
            url.startsWith("rtmp", true) || url.startsWith("rtsp", true) -> null
            // Everything else (including bare URLs and URLs that end in
            // .ts) — assume HLS. Many IPTV portals (izone.az and similar)
            // serve HLS via URLs that look progressive but actually
            // return an m3u8 manifest. Treating them as HLS lets
            // HlsMediaSource handle the manifest; if the server really
            // returns raw MPEG-TS, HlsMediaSource still falls back
            // gracefully via single-segment HLS handling.
            else -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
        }
        val builder = MediaItem.Builder().setUri(url)
        if (mime != null) builder.setMimeType(mime)
        return builder.build()
    }

    private fun createMediaSourceFor(url: String): androidx.media3.exoplayer.source.MediaSource {
        val factory = streamDataFactory
            ?: androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(prefs.userAgent)
                .setAllowCrossProtocolRedirects(true)
        val item = buildMediaItem(url)
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm")
                || path.endsWith(".mkv") || path.endsWith(".flv") ->
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(item)
            path.contains(".mpd") ->
                // DASH — теперь зависимость media3-exoplayer-dash подключена,
                // строим MediaSource напрямую, без обхода через
                // DefaultMediaSourceFactory.
                androidx.media3.exoplayer.dash.DashMediaSource.Factory(factory)
                    .createMediaSource(item)
            url.startsWith("rtsp", true) ->
                androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                    .createMediaSource(item)
            else ->
                // HLS by default — covers .m3u8, .ts, no-extension, query-
                // string-only IPTV portal URLs (izone.az, ucoz, …).
                // setAllowChunklessPreparation(false): принудительно
                // подгружаем все renditions сразу (включая отдельные
                // аудио-дорожки), иначе на izone.az звуковая дорожка не
                // обнаруживается до первого segment'а.
                // Round 191: setAllowChunklessPreparation(true) —
                // главный фикс задержки переключения каналов на HLS.
                // Раньше false принудительно качал ПЕРВЫЙ медиа-чанк
                // перед prepare() (нужно было для izone.az чтобы не
                // потерять отдельную аудиодорожку), но это добавляло
                // 2-10 сек на каждое переключение. Теперь играем сразу
                // с манифеста; редкие каналы с отдельной audio
                // rendition могут потерять её, но переключение идёт
                // мгновенно — что и просил юзер.
                androidx.media3.exoplayer.hls.HlsMediaSource.Factory(factory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item)
        }
    }

    // Слушатель для seek после restore (VOD). Хранится отдельно
    // чтобы можно было отписать предыдущий при следующем playStream
    // — иначе для лайв-стримов (которые никогда не достигают READY
    // в смысле seek-target) слушатели бесконечно копились на плеере,
    // отсюда GC-паузы и зависания после долгого просмотра.
    private var pendingSeekListener: Player.Listener? = null

    /** Listener для приёма EPG-апдейтов из фонового fetch / load.
     *  Перерисовывает текущий баннер, epgNow в верхней панели И
     *  всю overlay-ленту каналов чтобы у каждого появилась программа. */
    private val playerEpgListener: (Map<String, List<EpgRepository.Programme>>) -> Unit = { newData ->
        runOnUiThread {
            updateEpg()
            try { showChannelBanner() } catch (_: Throwable) {}
            // Без этого overlay-список оставался без EPG если был
            // создан до того как fetchAll/loadFromCache закончил.
            try { overlayAdapter?.updateEpg(newData) } catch (_: Throwable) {}
        }
    }

    private fun playStream(url: String) {
        loadingIndicator.visibility = View.VISIBLE
        errorLayout.visibility = View.GONE
        // Restore per-channel saved state (speed, aspect, position).
        val savedState = prefs.getChannelState(url)
        val savedSpeed = savedState.optDouble("speed", 1.0).toFloat()
        val savedAspect = savedState.optInt("aspect", -1)
        val savedPos = savedState.optLong("pos", -1L)

        player?.apply {
            // Build the source explicitly so HLS / DASH / Progressive is
            // chosen by URL pattern. DefaultMediaSourceFactory's auto
            // detection in Media3 1.2 occasionally falls into the
            // progressive pipeline even when MediaItem.mimeType is set,
            // which is why streams from izone-style portals were getting
            // UnrecognizedInputFormatException.
            val source = createMediaSourceFor(url)
            setMediaSource(source)
            prepare()
            playWhenReady = true
            // Speed
            val speedIdx = speedValues.indexOfFirst { kotlin.math.abs(it - savedSpeed) < 0.01f }
            if (speedIdx >= 0) {
                currentSpeedIndex = speedIdx
                playbackParameters = PlaybackParameters(speedValues[speedIdx])
            }
            // Position — only seek for VOD-like content (duration known and remaining > 5%).
            // Сначала отписываем прошлый pendingSeekListener (если был),
            // чтобы не накапливать слушатели на лайв-стримах.
            pendingSeekListener?.let { removeListener(it) }
            pendingSeekListener = null
            if (savedPos > 30_000L) {
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            val dur = duration
                            if (dur != C.TIME_UNSET && dur > 0 && savedPos < dur * 0.95) {
                                seekTo(savedPos)
                            }
                            removeListener(this)
                            pendingSeekListener = null
                        }
                    }
                }
                pendingSeekListener = listener
                addListener(listener)
            }
        }
        if (savedAspect in 0..3) {
            aspectRatioMode = savedAspect
            applyAspectRatioMode()
        }
    }

    private fun applyAspectRatioMode() {
        when (aspectRatioMode) {
            0 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            1 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            2 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            3 -> playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }

    /** Если у канала ещё нет сохранённого aspect-режима — берём
     *  «умный» auto: если source AR заметно ÝŸже экрана (4:3 в 16:9) —
     *  RESIZE_MODE_ZOOM (заполняет высоту, кропает по бокам). Иначе
     *  RESIZE_MODE_FIT (сохраняет пропорции). Это убирает чёрные
     *  поля у каналов которые юзер раньше настраивал вручную.
     *  Пользовательский per-channel override не трогаем. */
    private fun autoApplyAspectIfNeeded(videoSize: androidx.media3.common.VideoSize) {
        val url = currentUrl ?: return
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        val saved = prefs.getChannelState(url).optInt("aspect", -1)
        if (saved >= 0) return  // user already set a mode for this channel
        val srcRatio = (videoSize.width.toFloat() *
            (if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f)) /
            videoSize.height.toFloat()
        val view = playerView
        val displayRatio = if (view.height > 0) view.width.toFloat() / view.height.toFloat() else 16f / 9f
        playerView.resizeMode =
            if (srcRatio < displayRatio * 0.95f) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
    }

    /** Показывает разрешение текущего потока в нижней инфо-панели
     *  (channelInfoBanner — там же где имя канала и часы).
     *  "4K" / "1080p" / "720p" / "WxH" в зависимости от высоты. */
    private fun updateResolutionLabel(videoSize: androidx.media3.common.VideoSize) {
        val tv = findViewById<TextView>(R.id.bannerResolution) ?: return
        if (videoSize.width <= 0 || videoSize.height <= 0) {
            tv.visibility = View.GONE
            return
        }
        val tag = when {
            videoSize.height >= 2000 -> "4K"
            videoSize.height >= 700 -> "${videoSize.height}p"
            else -> "${videoSize.width}x${videoSize.height}"
        }
        tv.text = tag
        tv.visibility = View.VISIBLE
        // Кэшируем фактическую высоту в prefs — адаптеры списка
        // покажут точный бейдж качества при следующей отрисовке.
        currentUrl?.let { prefs.setChannelHeight(it, videoSize.height) }
    }

    private fun saveCurrentChannelState() {
        val url = currentUrl ?: return
        if (url.isBlank()) return
        val p = player
        try {
            val obj = org.json.JSONObject()
            obj.put("speed", speedValues[currentSpeedIndex].toDouble())
            obj.put("aspect", aspectRatioMode)
            if (p != null) {
                val dur = p.duration
                val pos = p.currentPosition
                // Only persist position for VOD (known duration). Skip live streams.
                if (dur != C.TIME_UNSET && dur > 0 && pos > 30_000L && pos < dur * 0.95) {
                    obj.put("pos", pos)
                } else {
                    obj.put("pos", -1L)
                }
                obj.put("volume", p.volume.toDouble())
            }
            prefs.saveChannelState(url, obj)
        } catch (_: Exception) {}
    }

    private fun switchChannel(direction: Int) {
        val channels = ChannelDataHolder.allChannels
        if (channels.isEmpty()) return

        val target = (currentIndex + direction + channels.size) % channels.size
        switchToChannel(target)
    }

    /**
     * Recall / Last channel — переключение на предыдущий просмотренный
     * канал. Если истории нет (только что зашли), показываем тост.
     */
    private fun switchToPreviousChannel() {
        val prev = previousIndex
        val channels = ChannelDataHolder.allChannels
        if (prev < 0 || prev >= channels.size) {
            Toast.makeText(this, R.string.no_previous_channel, Toast.LENGTH_SHORT).show()
            return
        }
        switchToChannel(prev)
    }

    private fun switchToChannel(index: Int) {
        val channels = ChannelDataHolder.allChannels
        if (index !in channels.indices) return
        if (index == currentIndex) return // ничего не меняется

        // New channel — reset reconnect counter
        reconnectAttempts = 0
        reconnectHandler.removeCallbacks(reconnectRunnable)
        // Полная остановка плеера перед сменой источника. Без этого
        // иногда оставались таймеры и track-override'ы с прошлого
        // канала, и новый источник не мог пробиться через бесконечное
        // "Reconnecting…".
        player?.let { p ->
            p.stop()
            // Сбрасываем все track-override'ы (особенно по аудио,
            // которое мы форсим в ensureAudioTrackSelected). На новом
            // канале старый override указывает в никуда и плеер мог
            // зависать в STATE_BUFFERING, не выбрав ни одной дорожки.
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
        }
        // Прячем индикатор ошибок предыдущего канала — playStream его
        // тоже скроет, но для подстраховки.
        errorLayout.visibility = View.GONE

        // Persist the state of the channel we're leaving before switching.
        saveCurrentChannelState()

        // Запоминаем тот, с которого уходим — чтобы кнопка Recall
        // вернула нас на него, а не уехала ещё дальше в историю.
        previousIndex = currentIndex
        currentIndex = index
        val channel = channels[currentIndex]

        currentUrl = channel.url
        channelName.text = channel.name
        channelNumber.text = "${currentIndex + 1} / ${channels.size}"
        // Скрываем резолюцию пока новый поток не отдаст VideoSize.
        findViewById<View>(R.id.bannerResolution)?.visibility = View.GONE
        ChannelDataHolder.currentChannelIndex = currentIndex

        prefs.lastChannelUrl = currentUrl
        // pushRecentChannel вместо pushRecent — сохраняет snapshot
        // канала (имя, лого, source playlist) чтобы RecentFragment
        // видел канал даже из другого плейлиста.
        prefs.pushRecentChannel(channel)

        overlayAdapter?.updateCurrentIndex(currentIndex)

        // Reset speed to 1x by default — playStream will restore saved speed if any.
        currentSpeedIndex = 2
        player?.playbackParameters = PlaybackParameters(1f)

        updateEpg()
        playStream(channel.url)
        showChannelBanner()
        scheduleHideControls()
    }

    private fun updateEpg() {
        val channels = ChannelDataHolder.allChannels
        if (currentIndex in channels.indices) {
            val channel = channels[currentIndex]
            val (nowProg, nextProg) = EpgRepository.getNowNextDetailed(ChannelDataHolder.epgData, channel.tvgId, channel.name)
            if (nowProg != null) {
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val nowTime = timeFormat.format(Date(nowProg.start))
                val nowEndTime = timeFormat.format(Date(nowProg.end))
                epgNow.text = "$nowTime - $nowEndTime  ${nowProg.title}"
                epgNow.visibility = View.VISIBLE
            } else {
                epgNow.visibility = View.GONE
            }
        }
    }

    private fun updateClock() {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        clockDisplay.text = time
        persistentClock.text = time
    }

    private fun startClock() {
        updateClock()
        clockHandler.postDelayed(clockRunnable, 30000)
    }

    private fun cycleAspectRatio() {
        aspectRatioMode = (aspectRatioMode + 1) % 4
        applyAspectRatioMode()
        val names = arrayOf(
            getString(R.string.aspect_fit),
            getString(R.string.aspect_16_9),
            getString(R.string.aspect_4_3),
            getString(R.string.aspect_fill)
        )
        Toast.makeText(this, names[aspectRatioMode], Toast.LENGTH_SHORT).show()
    }

    // === Channel info banner ===

    private fun showChannelBanner() {
        val channels = ChannelDataHolder.allChannels
        if (currentIndex !in channels.indices) return

        val channel = channels[currentIndex]
        bannerChannelNumber.text = "${currentIndex + 1}"
        bannerChannelName.text = channel.name

        // Лого: tvg-logo → LearnedLogos → iptv-org. Раньше брали
        // только channel.logoUrl и если он null — лого не показывали.
        // С Round 100 (без favicon-fallback) у большинства каналов
        // logoUrl=null, и нижняя панель оставалась без лого хотя в
        // списках лого появлялись. Теперь та же цепочка что в
        // адаптерах.
        val resolvedLogo = channel.logoUrl
            ?: LearnedLogos.lookup(channel.name)
            ?: ChannelMetaLookup.lookup(channel.name)?.logoUrl
        if (resolvedLogo != null) {
            bannerChannelLogo.load(resolvedLogo) {
                crossfade(true)
                error(R.drawable.ic_channel_placeholder)
                placeholder(R.drawable.ic_channel_placeholder)
            }
        } else {
            bannerChannelLogo.setImageResource(R.drawable.ic_channel_placeholder)
        }

        val (nowProg, nextProg) = EpgRepository.getNowNextDetailed(ChannelDataHolder.epgData, channel.tvgId, channel.name)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        if (nowProg != null) {
            val nowTime = timeFormat.format(Date(nowProg.start))
            val nowEndTime = timeFormat.format(Date(nowProg.end))
            bannerEpgNow.text = "$nowTime - $nowEndTime  ${nowProg.title}"
            bannerEpgNow.visibility = View.VISIBLE
            // Show progress
            val progress = EpgRepository.getCurrentProgress(nowProg)
            bannerEpgProgress.progress = (progress * 100).toInt()
            bannerEpgProgress.visibility = View.VISIBLE
        } else {
            bannerEpgNow.visibility = View.GONE
            bannerEpgProgress.visibility = View.GONE
        }
        if (nextProg != null) {
            val nextTime = timeFormat.format(Date(nextProg.start))
            bannerEpgNext.text = "${getString(R.string.epg_next)}: $nextTime ${nextProg.title}"
            bannerEpgNext.visibility = View.VISIBLE
        } else {
            bannerEpgNext.visibility = View.GONE
        }

        val time = timeFormat.format(Date())
        bannerClock.text = time

        channelInfoBanner.visibility = View.VISIBLE
        bannerHandler.removeCallbacks(bannerHideRunnable)
        bannerHandler.postDelayed(bannerHideRunnable, 5000)

        // Tap banner to show full EPG info
        channelInfoBanner.setOnClickListener {
            showEpgDetailDialog()
        }
    }

    private fun showEpgDetailDialog() {
        val channels = ChannelDataHolder.allChannels
        if (currentIndex !in channels.indices) return
        val channel = channels[currentIndex]
        val epg = ChannelDataHolder.epgData
        val tvgId = channel.tvgId ?: return

        val normId = tvgId.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val programmes = epg[normId] ?: return
        if (programmes.isEmpty()) return

        val now = System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        // Show current + next 10 programmes
        val relevantProgs = programmes.filter { it.end >= now }.take(12)
        if (relevantProgs.isEmpty()) return

        val sb = StringBuilder()
        for (p in relevantProgs) {
            val startTime = timeFormat.format(Date(p.start))
            val endTime = timeFormat.format(Date(p.end))
            val isCurrent = now in p.start..p.end
            if (isCurrent) {
                sb.append("▶ $startTime - $endTime  ${p.title}")
                val progress = EpgRepository.getCurrentProgress(p)
                sb.append(" [${(progress * 100).toInt()}%]")
            } else {
                sb.append("   $startTime - $endTime  ${p.title}")
            }
            if (p.description.isNotEmpty()) {
                sb.append("\n     ${p.description.take(100)}")
            }
            sb.append("\n\n")
        }

        android.app.AlertDialog.Builder(this, R.style.Theme_TVViewer_Dialog)
            .setTitle("${channel.name} — ${getString(R.string.tv_guide)}")
            .setMessage(sb.toString().trim())
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // === Channel list overlay ===

    private fun toggleChannelList() {
        if (channelListVisible) hideChannelList() else showChannelList()
    }

    private fun showChannelList() {
        channelListOverlay.visibility = View.VISIBLE
        channelListVisible = true
        hideHandler.removeCallbacks(hideRunnable)
        // Обновляем имя плейлиста (вдруг поменялся за время сессии) и
        // подпись категории (могла быть "Кино" в прошлый раз).
        findViewById<TextView>(R.id.overlayPlaylistName)?.text =
            prefs.lastPlaylistName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.channels)
        val catLabel = findViewById<TextView>(R.id.overlaySelectedCategoryLabel)
        val allLabel = getString(R.string.all)
        if (overlaySelectedCategory.isNotEmpty() && overlaySelectedCategory != allLabel) {
            catLabel?.text = "▸ $overlaySelectedCategory"
            catLabel?.visibility = View.VISIBLE
        } else {
            catLabel?.visibility = View.GONE
        }

        // Scroll to current channel and focus it so the D-pad immediately
        // navigates inside the list (otherwise the user is stuck on the
        // PlayerView with no visible focus target).
        val scrollIndex = overlayFilteredIndices.indexOf(currentIndex)
        val target = if (scrollIndex >= 0) scrollIndex else currentIndex.coerceAtLeast(0)
        overlayChannelsList.scrollToPosition(target)
        overlayChannelsList.post {
            val vh = overlayChannelsList.findViewHolderForAdapterPosition(target)
            if (vh != null) {
                vh.itemView.requestFocus()
            } else {
                // The view holder isn't bound yet — request focus on the list
                // and let descendant focus pick the first child once laid out.
                overlayChannelsList.requestFocus()
            }
        }
        scheduleHideChannelList()
    }

    private fun hideChannelList() {
        channelListOverlay.visibility = View.GONE
        channelListVisible = false
        // Сбрасываем панель категорий и возвращаем список каналов в
        // visible-состояние. Так при следующем открытии (1-й DPAD_LEFT)
        // юзер увидит ТОЛЬКО список каналов, без категорий.
        if (::overlayCategoriesPanel.isInitialized) {
            overlayCategoriesPanel.visibility = View.GONE
            val lpCats = overlayCategoriesPanel.layoutParams
            lpCats.width = (140 * resources.displayMetrics.density).toInt()
            overlayCategoriesPanel.layoutParams = lpCats
        }
        findViewById<View>(R.id.overlayChannelsPanel)?.visibility = View.VISIBLE
        // Возвращаем details-панель к стандартной margin (320dp).
        findViewById<View>(R.id.channelDetailsPanel)?.let { p ->
            val lp = p.layoutParams as? android.widget.FrameLayout.LayoutParams
            lp?.let {
                it.marginStart = (320 * resources.displayMetrics.density).toInt()
                p.layoutParams = it
            }
        }
        channelListHideHandler.removeCallbacks(channelListHideRunnable)
        scheduleHideControls()
    }

    private fun scheduleHideChannelList() {
        channelListHideHandler.removeCallbacks(channelListHideRunnable)
        // Пока пользователь пишет в поиск — НЕ запускаем таймер
        // автоскрытия. Иначе панель закрывается посреди ввода и
        // введённое теряется. Авто-hide возобновится когда EditText
        // потеряет фокус (через onFocusChange listener).
        if (overlaySearchEdit?.hasFocus() == true) return
        // Пока показывается панель категорий — auto-hide ОТКЛЮЧЕН.
        // Юзер вручную закрывает её клавишей RIGHT или выбором
        // категории. Без этого через 5 сек панель закрывалась пока
        // юзер ещё листал категории, и фокус терялся.
        if (::overlayCategoriesPanel.isInitialized &&
            overlayCategoriesPanel.visibility == View.VISIBLE) return
        val seconds = prefs.channelListAutoHideSeconds
        if (seconds > 0) {
            channelListHideHandler.postDelayed(channelListHideRunnable, seconds * 1000L)
        }
    }

    /** Reset the channel-list inactivity timer when the user interacts with it. */
    private fun bumpChannelListIdleTimer() {
        if (channelListVisible) scheduleHideChannelList()
    }

    private fun isFocusInTopBar(): Boolean {
        var v: View? = currentFocus ?: return false
        while (v != null) {
            if (v == topBar) return true
            v = (v.parent as? View)
        }
        return false
    }

    /** true если фокус сейчас внутри указанной View (или это сама она). */
    private fun isFocusInside(target: View): Boolean {
        var v: View? = currentFocus ?: return false
        while (v != null) {
            if (v === target) return true
            v = (v.parent as? View)
        }
        return false
    }

    /** У текущего плейлиста есть реальные категории кроме "Все"? */
    private fun hasOverlayCategories(): Boolean {
        val adapter = overlayCategoriesList.adapter ?: return false
        return adapter.itemCount > 1  // 1 = только "Все"
    }

    private fun playerDrawerVisible(): Boolean =
        ::playerDrawerOverlay.isInitialized &&
            playerDrawerOverlay.visibility == View.VISIBLE

    private fun showPlayerDrawer() {
        playerDrawerOverlay.visibility = View.VISIBLE
        playerDrawerOverlay.bringToFront()
        // Сдвигаем панель списка каналов вправо ровно на ширину выдвижного
        // меню (260dp — см. activity_player.xml), чтобы оба элемента
        // отображались рядом, а не перекрывали друг друга.
        val drawerWidth = (260 * resources.displayMetrics.density).toInt()
        findViewById<View>(R.id.channelListPanel)
            ?.animate()?.translationX(drawerWidth.toFloat())?.setDuration(150)?.start()
        // Сначала очищаем фокус с того что было до открытия drawer'а,
        // иначе на TV-боксе DPAD_CENTER в drawer'е активирует ту
        // кнопку что была сфокусирована раньше (например "назад"
        // в ТВ Гиде).
        currentFocus?.clearFocus()
        // post() чтобы фокус-запрос произошёл ПОСЛЕ того как drawer
        // прошёл layout и его пункты стали focusable. Без этого
        // requestFocus иногда возвращает false и фокус не переходит.
        playerDrawerOverlay.post {
            playerDrawerOverlay.findViewById<View>(R.id.playerDrawerPlaylists)?.requestFocus()
        }
    }

    private fun hidePlayerDrawer() {
        if (::playerDrawerOverlay.isInitialized) {
            playerDrawerOverlay.visibility = View.GONE
            findViewById<View>(R.id.channelListPanel)
                ?.animate()?.translationX(0f)?.setDuration(150)?.start()
            // Возвращаем фокус: если открыты категории — на первую
            // категорию (юзер пришёл из них через 3-й LEFT, RIGHT
            // должен вернуть туда же). Иначе — в список каналов.
            val catsShown = ::overlayCategoriesPanel.isInitialized &&
                overlayCategoriesPanel.visibility == View.VISIBLE
            if (catsShown) {
                overlayCategoriesList.post {
                    overlayCategoriesList.requestFocus()
                    overlayCategoriesList.findViewHolderForAdapterPosition(0)
                        ?.itemView?.requestFocus()
                }
            } else {
                findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.overlayChannelsList)
                    ?.requestFocus()
            }
        }
    }

    private fun rightMenuVisible(): Boolean =
        ::playerRightMenuOverlay.isInitialized &&
            playerRightMenuOverlay.visibility == View.VISIBLE

    /**
     * Циклически переставляет фокус по списку id (UP/DOWN внутри
     * выпадающего меню). Возвращает true, если фокус был на одном из
     * пунктов и был передвинут.
     */
    private fun cycleFocus(items: IntArray, forward: Boolean): Boolean {
        val focusedId = currentFocus?.id ?: return false
        val idx = items.indexOf(focusedId)
        if (idx < 0) return false
        val n = items.size
        val nextIdx = if (forward) (idx + 1) % n else (idx - 1 + n) % n
        findViewById<View>(items[nextIdx])?.requestFocus()
        return true
    }

    private fun showPlayerRightMenu() {
        playerRightMenuOverlay.visibility = View.VISIBLE
        playerRightMenuOverlay.bringToFront()
        // Toggle-надпись на кнопке "Избранное": если канал уже в
        // избранном — "Убрать из избранного", иначе "В избранное".
        val ch = ChannelDataHolder.allChannels.getOrNull(currentIndex)
        val favBtn = playerRightMenuOverlay
            .findViewById<com.google.android.material.button.MaterialButton>(R.id.rightMenuFavorite)
        if (ch != null) {
            val isFav = prefs.isFavorite(ch.url)
            favBtn?.text = getString(if (isFav) R.string.unfavorite else R.string.favorite)
        }
        playerRightMenuOverlay.findViewById<View>(R.id.rightMenuFavorite)?.requestFocus()
    }

    private fun hidePlayerRightMenu() {
        if (::playerRightMenuOverlay.isInitialized) {
            playerRightMenuOverlay.visibility = View.GONE
        }
    }

    private fun isInsideControlsOverlay(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p == controlsOverlay) return true
            p = p.parent as? View
        }
        return false
    }

    private fun focusTopBar() {
        showControls()
        // Give the layout a tick to lay out before requesting focus
        topBar.post {
            // Pick first focusable child of the top bar
            for (i in 0 until topBar.childCount) {
                val c = topBar.getChildAt(i)
                if (c.isFocusable && c.visibility == View.VISIBLE) {
                    c.requestFocus()
                    break
                }
            }
            // Also reset the auto-hide timer so user has time to interact
            scheduleHideControls()
        }
    }

    // === Controls visibility ===

    private fun toggleControls() {
        if (isScreenLocked) return
        if (channelListVisible) {
            hideChannelList()
            return
        }
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        if (isScreenLocked) return
        controlsOverlay.visibility = View.VISIBLE
        controlsVisible = true
        scheduleHideControls()
    }

    private fun hideControls() {
        controlsOverlay.visibility = View.GONE
        controlsVisible = false
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        val seconds = prefs.channelListAutoHideSeconds
        hideHandler.postDelayed(hideRunnable, seconds * 1000L)
    }

    // === Sleep timer ===

    private fun startSleepTimer(minutes: Int) {
        sleepHandler.removeCallbacks(sleepTimerRunnable)
        if (minutes <= 0) {
            sleepTimerEnd = 0
            sleepTimerIndicator.visibility = View.GONE
            return
        }
        sleepTimerEnd = System.currentTimeMillis() + minutes * 60000L
        sleepTimerRunnable.run()
    }

    // === D-pad / Remote control ===

    /**
     * Логирует ВСЕ нажатия клавиш — даже те, что родная activity
     * фильтрует до onKeyDown. Помогает выяснить какой keycode шлёт
     * конкретный пульт. Toast только на ACTION_DOWN, не на REPEAT.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Любое нажатие при открытом списке каналов / категорий —
        // продлевает таймер автоскрытия. Без этого пользователь
        // переходит на категории, нажимает влево-вправо чтобы выбрать,
        // а список успевает закрыться через 5 сек. Теперь активность =
        // живёт пока юзер взаимодействует.
        if (event.action == KeyEvent.ACTION_DOWN && channelListVisible) {
            bumpChannelListIdleTimer()
        }
        // BACK / DPAD_RIGHT при открытом drawer'е — закрываем сами,
        // ДО того как DPAD_CENTER / OK успеет кликнуть какую-то
        // другую кнопку. Это страхует от багов когда drawer открыт
        // но фокус не дошёл до его пункта (на TV-боксе бывает).
        if (event.action == KeyEvent.ACTION_DOWN && playerDrawerVisible()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    hidePlayerDrawer(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Any keypress while the channel list is visible counts as activity
        if (channelListVisible) bumpChannelListIdleTimer()

        // Inline-панель деталей канала: BACK или LEFT закрывают её,
        // не выходя из плеера.
        if (findViewById<View>(R.id.channelDetailsPanel)?.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                hideChannelDetailsPanel(); return true
            }
        }

        // Правое выпадающее меню: BACK или DPAD_LEFT закрывают,
        // UP/DOWN зацикливаются внутри пунктов (как в плеер-меню).
        if (rightMenuVisible()) {
            if (keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                hidePlayerRightMenu(); return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (cycleFocus(RIGHT_MENU_IDS, keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        // Плеер-меню (выдвижное слева по DPAD_LEFT 2x): Back / Right
        // закрывают, UP/DOWN зацикливаются по пунктам меню (раньше с
        // последнего пункта DOWN уводил фокус в список каналов справа
        // и пользователь не мог вернуться обратно).
        if (playerDrawerVisible()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { hidePlayerDrawer(); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { hidePlayerDrawer(); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (cycleFocus(PLAYER_DRAWER_IDS, keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        if (isScreenLocked) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                toggleScreenLock()
                return true
            }
            return true
        }

        // When focus is already inside the top bar, defer all D-pad keys to
        // Android's default focus traversal so the user can freely move
        // between buttons (otherwise UP/DOWN below would re-grab the keys
        // and switch channels instead).
        val inTopBar = isFocusInTopBar()
        if (inTopBar) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> return super.onKeyDown(keyCode, event)
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // С верхнего бара (там теперь только кнопка Назад)
                    // Right тоже открывает правое меню плеера, как и из
                    // основной зоны.
                    showPlayerRightMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Leave the top bar — return focus to the player area
                    playerView.requestFocus()
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    playerView.requestFocus()
                    return true
                }
            }
        }

        when (keyCode) {
            // D-pad center / Enter — показываем нижний инфо-бар
            // (channelInfoBanner): имя канала, что идёт сейчас, часы,
            // разрешение. Повторное нажатие — скрыть. Список keycodes
            // расширен для разных ТВ-боксов: BUTTON_A, NUMPAD_ENTER,
            // SPACE — все они обычно мапятся на ОК на пульте.
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_SPACE -> {
                if (channelListVisible) return super.onKeyDown(keyCode, event)
                if (channelInfoBanner.visibility == View.VISIBLE) {
                    bannerHandler.removeCallbacks(bannerHideRunnable)
                    channelInfoBanner.visibility = View.GONE
                } else {
                    showChannelBanner()
                }
                return true
            }
            // Переключение канала вверх — широкий список keycode'ов,
            // потому что разные ТВ-боксы / пульты шлют разное:
            //  • DPAD_UP — D-pad стандартный
            //  • CHANNEL_UP — стандарт Android TV (CH+)
            //  • PAGE_UP — некоторые HDMI-донглы
            //  • MEDIA_NEXT, NAVIGATE_NEXT — пульты с кнопками вперёд
            //  • F12, BUTTON_R1 — некоторые ТВ-боксы (включая X4)
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_F12,
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (channelListVisible) return super.onKeyDown(keyCode, event)
                switchChannel(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_F11,
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (channelListVisible) return super.onKeyDown(keyCode, event)
                switchChannel(1)
                return true
            }
            // D-pad Left
            //   1st press        → открывает список каналов (фокус на первом канале)
            //   2nd press (на канале)  → если есть категории, переводит фокус
            //                            в вертикальный столбец категорий слева
            //   3rd press (на категории) → открывает левое боковое меню плеера
            //   Если категорий нет — после 2-го LEFT сразу открываем меню.
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (playerDrawerVisible()) {
                    return super.onKeyDown(keyCode, event)
                }
                if (channelListVisible) {
                    val hasCategories = hasOverlayCategories()
                    val catsShown = ::overlayCategoriesPanel.isInitialized &&
                        overlayCategoriesPanel.visibility == View.VISIBLE
                    val focusInCategories = catsShown && isFocusInside(overlayCategoriesList)
                    when {
                        focusInCategories -> {
                            // 3-е LEFT (фокус на категории) → открываем
                            // drawer, но категории и overlay НЕ скрываем —
                            // юзер хочет видеть их рядом. Из drawer'а
                            // первый RIGHT вернёт фокус в категории,
                            // следующий RIGHT покажет список каналов
                            // (категории скрываются), ещё RIGHT — закроет
                            // overlay полностью.
                            showPlayerDrawer()
                            return true
                        }
                        !catsShown && hasCategories -> {
                            // 2-е LEFT: СКРЫВАЕМ список каналов, показываем
                            // ТОЛЬКО панель категорий. Фокус — на первой
                            // категории. Юзер выбирает категорию → возврат
                            // делается автоматически в filterOverlayChannels.
                            findViewById<View>(R.id.overlayChannelsPanel)?.visibility = View.GONE
                            overlayCategoriesPanel.visibility = View.VISIBLE
                            // Расширяем панель категорий чтобы заполнила
                            // освободившееся место.
                            val lpCats = overlayCategoriesPanel.layoutParams
                            lpCats.width = (240 * resources.displayMetrics.density).toInt()
                            overlayCategoriesPanel.layoutParams = lpCats
                            overlayCategoriesPanel.post {
                                overlayCategoriesList.requestFocus()
                                overlayCategoriesList
                                    .findViewHolderForAdapterPosition(0)
                                    ?.itemView?.requestFocus()
                            }
                            // Скрываем details-панель если она была открыта —
                            // её содержимое не имеет смысла без списка.
                            findViewById<View>(R.id.channelDetailsPanel)?.visibility = View.GONE
                            bumpChannelListIdleTimer()
                            return true
                        }
                        else -> {
                            // 2-е LEFT без категорий → сразу меню плеера.
                            showPlayerDrawer()
                            return true
                        }
                    }
                }
                toggleChannelList()
                return true
            }
            // D-pad Right
            //   • если открыт список каналов — закрываем его
            //   • иначе — открываем правое выпадающее меню плеера
            //     (Аудио / Скорость / Список каналов / PiP / Блокировка /
            //     Соотношение). Раньше эти кнопки висели в верхнем
            //     правом углу — теперь убраны и доступны только отсюда.
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (channelListVisible) {
                    val catsShown = ::overlayCategoriesPanel.isInitialized &&
                        overlayCategoriesPanel.visibility == View.VISIBLE
                    val focusInCategories = catsShown && isFocusInside(overlayCategoriesList)
                    if (focusInCategories) {
                        // RIGHT в столбце категорий → возвращаем список
                        // каналов (с фильтром по выделенной категории),
                        // фокус на первом канале. Юзер сможет листать
                        // отфильтрованный список, а если хочет вернуться
                        // в категории — снова DPAD_LEFT.
                        overlayCategoriesPanel.visibility = View.GONE
                        val lpCats = overlayCategoriesPanel.layoutParams
                        lpCats.width = (140 * resources.displayMetrics.density).toInt()
                        overlayCategoriesPanel.layoutParams = lpCats
                        findViewById<View>(R.id.overlayChannelsPanel)?.visibility = View.VISIBLE
                        overlayChannelsList.post {
                            overlayChannelsList.requestFocus()
                            overlayChannelsList.findViewHolderForAdapterPosition(0)
                                ?.itemView?.requestFocus()
                        }
                        bumpChannelListIdleTimer()
                        return true
                    }
                    hideChannelList()
                    return true
                }
                showPlayerRightMenu()
                return true
            }
            // Play/Pause
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.let { p ->
                    if (p.isPlaying) {
                        p.pause()
                        btnPlayPause.setImageResource(R.drawable.ic_play)
                    } else {
                        p.play()
                        btnPlayPause.setImageResource(R.drawable.ic_pause)
                    }
                }
                showControls()
                return true
            }
            // Back
            KeyEvent.KEYCODE_BACK -> {
                if (channelListVisible) {
                    hideChannelList()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            // Menu key - show channel list
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_GUIDE -> {
                toggleChannelList()
                return true
            }
            // Volume keys - pass to system
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                return super.onKeyDown(keyCode, event)
            }
            // Info key — тоже переключатель нижнего инфо-бара
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_TV_DATA_SERVICE -> {
                if (channelInfoBanner.visibility == View.VISIBLE) {
                    bannerHandler.removeCallbacks(bannerHideRunnable)
                    channelInfoBanner.visibility = View.GONE
                } else {
                    showChannelBanner()
                }
                return true
            }
            // Recall / Last channel — возврат на предыдущий канал.
            // KEYCODE_LAST_CHANNEL — стандарт Android TV; красная
            // кнопка пульта тоже часто работает как Recall.
            KeyEvent.KEYCODE_LAST_CHANNEL,
            KeyEvent.KEYCODE_PROG_RED -> {
                switchToPreviousChannel()
                return true
            }
            // Зелёная кнопка пульта — циклическая смена соотношения
            // экрана (Fit / 16:9 / 4:3 / Stretch). Без захода в меню.
            KeyEvent.KEYCODE_PROG_GREEN -> {
                cycleAspectRatio()
                return true
            }
            // Favourite hotkey: F / yellow remote button / bookmark
            KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_PROG_YELLOW,
            KeyEvent.KEYCODE_BOOKMARK -> {
                val channels = ChannelDataHolder.allChannels
                if (currentIndex in channels.indices) {
                    toggleFavorite(channels[currentIndex])
                    val msg = if (prefs.isFavorite(channels[currentIndex].url))
                        "★ ${channels[currentIndex].name}" else channels[currentIndex].name
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        // Number keys for direct channel input (0-9)
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            val digit = keyCode - KeyEvent.KEYCODE_0
            handleNumberInput(digit)
            return true
        }
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
            val digit = keyCode - KeyEvent.KEYCODE_NUMPAD_0
            handleNumberInput(digit)
            return true
        }

        // Дебаг-логгер для неизвестных клавиш пульта: показываем
        // keycode и имя на экране, чтобы пользователь мог сообщить
        // что именно шлёт его пульт. Без этого мы гадаем.
        // Не трогаем системные клавиши громкости/питания/back.
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            keyCode != KeyEvent.KEYCODE_VOLUME_MUTE &&
            keyCode != KeyEvent.KEYCODE_BACK &&
            keyCode != KeyEvent.KEYCODE_HOME &&
            keyCode != KeyEvent.KEYCODE_POWER) {
            val name = KeyEvent.keyCodeToString(keyCode)
            Toast.makeText(this, "Key: $keyCode ($name)", Toast.LENGTH_LONG).show()
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleNumberInput(digit: Int) {
        numberInput += digit.toString()
        numberInputDisplay.text = numberInput
        numberInputDisplay.visibility = View.VISIBLE

        numberHandler.removeCallbacks(numberRunnable)
        numberHandler.postDelayed(numberRunnable, 1500)
    }

    private fun applyNumberInput() {
        val num = numberInput.toIntOrNull()
        numberInput = ""
        numberInputDisplay.visibility = View.GONE

        if (num != null && num > 0 && num <= ChannelDataHolder.allChannels.size) {
            switchToChannel(num - 1)
            showControls()
        }
    }

    // === Picture-in-Picture ===

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            controlsOverlay.visibility = View.GONE
            channelListOverlay.visibility = View.GONE
            channelInfoBanner.visibility = View.GONE
            lockOverlay.visibility = View.GONE
            controlsVisible = false
            channelListVisible = false
        } else {
            // Пользователь закрыл окно PiP крестиком (а не вернулся в плеер):
            // активити уже в STOPPED, плеер продолжал крутить аудио в фоне —
            // надо прибить, иначе звук остаётся висеть до следующего onCreate.
            if (lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED ||
                lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED) {
                player?.stop()
                player?.release()
                player = null
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Покинули PiP-окно (свернули в фон, выключили картинку): глушим
        // плеер, чтобы звук не "залипал" в системе.
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip && !keepPlayingInBackground) {
            player?.stop()
        }
    }

    // === System UI ===

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    override fun onPause() {
        super.onPause()
        // Keep playing in Picture-in-Picture mode
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        // Если пользователь открыл Настройки прямо из плеера — не ставим
        // на паузу: пускай идёт трансляция, пока он крутит ползунки.
        if (!inPip && !keepPlayingInBackground) {
            player?.pause()
        }
        saveCurrentChannelState()
    }

    override fun onResume() {
        super.onResume()
        // Возвращаемся из Настроек / другой активити — флаг сбрасываем,
        // дальнейшие onPause работают как обычно.
        keepPlayingInBackground = false
        player?.play()
        hideSystemUI()
        // Round 191: если юзер поменял "Буфер" в Settings, пересоздаём
        // плеер — иначе новый LoadControl не применится (он задаётся
        // только в ExoPlayer.Builder при initPlayer). Юзер жаловался
        // "буфер не влияет".
        val curBufferMode = prefs.bufferMode
        if (player != null && playerBufferModeAtInit != null &&
            playerBufferModeAtInit != curBufferMode) {
            val keepUrl = currentUrl
            try { player?.stop() } catch (_: Throwable) {}
            try { player?.release() } catch (_: Throwable) {}
            player = null
            playerBufferModeAtInit = null
            initPlayer()
            keepUrl?.let { playStream(it) }
        }
        // Перечитываем настройку показа часов: юзер мог открыть
        // Settings, включить часы и вернуться — без этого persistentClock
        // оставался скрытым до перезапуска плеера.
        applyClockVisibility()
        // Round 181: триггерим EPG auto-refresh ИЗ ПЛЕЕРА тоже. Раньше
        // это делал только MainActivity.onResume — но на TV-боксах юзер
        // обычно идёт через autoplay сразу в PlayerActivity и неделями
        // не возвращается на главный экран. Сама функция имеет 24h gate,
        // так что вызов дешёвый: fast-path выходит через 30 сек если
        // refresh уже был сегодня. Решает кейс "ТВ-гид не обновляется
        // на следующий день, пока я только смотрю каналы".
        try { TVViewerApp.triggerEpgAutoRefresh(this) } catch (_: Throwable) {}
        // Round 185: и проверку обновлений APK тоже из плеера —
        // тот же кейс что с EPG: юзер не возвращается на главный
        // экран → MainActivity.onResume не срабатывает →
        // обновление не приходит. Жалоба "не видно 272, только 271".
        try { UpdateCheckerHelper.maybeCheck(this) } catch (_: Throwable) {}
    }

    private fun applyClockVisibility() {
        val on = prefs.timeDisplayPosition != "off"
        if (::clockDisplay.isInitialized) {
            clockDisplay.visibility = if (on) View.VISIBLE else View.GONE
        }
        if (::persistentClock.isInitialized) {
            persistentClock.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    private fun setupMediaSession() {
        if (mediaSession != null) return
        try {
            val ms = android.media.session.MediaSession(this, "TVViewer")
            ms.setFlags(
                android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            ms.setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val event: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (event == null || event.action != KeyEvent.ACTION_DOWN) {
                        return super.onMediaButtonEvent(intent)
                    }
                    return when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT,
                        KeyEvent.KEYCODE_CHANNEL_UP -> { runOnUiThread { switchChannel(-1) }; true }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        KeyEvent.KEYCODE_CHANNEL_DOWN -> { runOnUiThread { switchChannel(1) }; true }
                        KeyEvent.KEYCODE_LAST_CHANNEL -> { runOnUiThread { switchToPreviousChannel() }; true }
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            runOnUiThread {
                                player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
                            }
                            true
                        }
                        else -> super.onMediaButtonEvent(intent)
                    }
                }
                // Стандартные media-callback'и: skipToNext/Prev часто
                // вызываются TV-системой при нажатии CH+ / CH-.
                override fun onSkipToNext() { runOnUiThread { switchChannel(-1) } }
                override fun onSkipToPrevious() { runOnUiThread { switchChannel(1) } }
                override fun onPlay() { runOnUiThread { player?.play() } }
                override fun onPause() { runOnUiThread { player?.pause() } }
            })
            // Без setPlaybackState=PLAYING система не считает session
            // активной → не маршрутизирует медиа-кнопки в неё.
            val state = android.media.session.PlaybackState.Builder()
                .setActions(
                    android.media.session.PlaybackState.ACTION_PLAY or
                    android.media.session.PlaybackState.ACTION_PAUSE or
                    android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                    android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                    android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    android.media.session.PlaybackState.ACTION_STOP
                )
                .setState(
                    android.media.session.PlaybackState.STATE_PLAYING,
                    android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1.0f
                )
                .build()
            ms.setPlaybackState(state)
            ms.isActive = true
            mediaSession = ms
        } catch (e: Throwable) {
            android.util.Log.e("PlayerActivity", "MediaSession setup failed", e)
        }
    }

    override fun onDestroy() {
        saveCurrentChannelState()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (_: Throwable) {}
        try { EpgRepository.removeEpgUpdateListener(playerEpgListener) } catch (_: Throwable) {}
        // Снимаем ViewTreeObserver-listeners чтобы Activity не утекала.
        try {
            controlsFocusListener?.let {
                if (::controlsOverlay.isInitialized)
                    controlsOverlay.viewTreeObserver.removeOnGlobalFocusChangeListener(it)
            }
            overlayFocusListener?.let {
                if (::overlayCategoriesList.isInitialized)
                    overlayCategoriesList.viewTreeObserver.removeOnGlobalFocusChangeListener(it)
            }
        } catch (_: Throwable) {}
        controlsFocusListener = null
        overlayFocusListener = null
        // Снимаем pendingSeekListener если он висит — иначе при
        // быстрых переключениях канала листенеры копятся.
        try {
            pendingSeekListener?.let { player?.removeListener(it) }
        } catch (_: Throwable) {}
        pendingSeekListener = null
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        numberHandler.removeCallbacks(numberRunnable)
        sleepHandler.removeCallbacks(sleepTimerRunnable)
        bannerHandler.removeCallbacks(bannerHideRunnable)
        reconnectHandler.removeCallbacks(reconnectRunnable)
        channelListHideHandler.removeCallbacks(channelListHideRunnable)
        player?.release()
        player = null
    }
}
