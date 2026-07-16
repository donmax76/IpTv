package com.tvviewer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.request.CachePolicy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Стартовый экран приложения. Две большие кнопки:
 *   - Прямой эфир: запускает плеер с плейлистом по умолчанию
 *     (prefs.lastPlaylistUrl). Если плейлиста нет — toast подсказка.
 *   - Плейлисты: переключает на PlaylistsFragment.
 *  Под кнопками — текущий default-плейлист. Фон — высококачественные
 *  фотографии с picsum.photos, меняются каждые 12 сек с cross-fade.
 *  При первом запуске или офлайне используются захардкоженные градиенты
 *  как fallback. */
class HomeFragment : Fragment() {

    companion object {
        const val TAG = "HomeFragment"
        // Fallback-градиенты на случай если интернета нет / picsum.photos
        // недоступен. Coil попробует URL → если не загрузится, останется
        // предыдущая картинка.
        private val FALLBACK_BG_RES = intArrayOf(
            R.drawable.bg_home_gradient_1,
            R.drawable.bg_home_gradient_2,
            R.drawable.bg_home_gradient_3,
            R.drawable.bg_home_gradient_4,
            R.drawable.bg_home_gradient_5,
        )
        // picsum.photos выдаёт случайное HD-фото нужного разрешения.
        // ?random=N делает каждый запрос уникальным, чтобы Coil не
        // отдавал кэш одной и той же картинки. Размер 1920×1080 даёт
        // приличное качество и под 4K-экраном (scaleType centerCrop).
        // 1280×720 хватает для full-HD дисплея с centerCrop, при этом
        // в 2 раза легче по трафику (важно на слабом TV-боксе и при
        // шумном Wi-Fi). picsum выдаёт уникальную картинку на каждый
        // ?random=N — без него Coil отдавал бы кэш.
        private const val PHOTO_URL_BASE = "https://picsum.photos/1280/720?random="
        // Был 12 сек — оказалось слишком часто, юзер жаловался на лаг.
        // 30 сек — щадящий ритм, не давит на сеть и GC, при этом фон
        // всё равно выглядит «живым».
        private const val SLIDE_INTERVAL_MS = 30_000L
        private const val FADE_DURATION_MS = 1_400L
    }

    private lateinit var prefs: AppPreferences
    private val bgHandler = Handler(Looper.getMainLooper())
    private var bgPhotoSeed = (System.currentTimeMillis() / 1000).toInt()
    private var fallbackIndex = 0
    private var showingA = true
    /** Защита от двойного нажатия: пока fetchPlaylist в процессе,
     *  игнорируем повторные клики. Иначе юзер тыкает 3-4 раза думая
     *  что не отреагировало → запускается несколько fetch'ей в
     *  параллель и это ещё больше тормозит. */
    private var liveStarting = false
    private val bgRunnable = object : Runnable {
        override fun run() {
            cycleBackground()
            bgHandler.postDelayed(this, SLIDE_INTERVAL_MS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        view.findViewById<View>(R.id.btnHomeLive).setOnClickListener { onLiveClicked() }
        view.findViewById<View>(R.id.btnHomePlaylists).setOnClickListener {
            (activity as? MainActivity)?.openPlaylistsTab()
        }
        view.findViewById<View>(R.id.btnHomeLive).post {
            view.findViewById<View>(R.id.btnHomeLive).requestFocus()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Round 183: BottomNavigation использует show/hide, поэтому
        // onResume срабатывает только при первом создании. После
        // смены плейлиста через другой таб юзер возвращается на
        // Home и видит СТАРОЕ имя плейлиста — пока не пересоздаст
        // фрагмент. Перечитываем pref здесь.
        if (!hidden) refreshDefaultLabel()
    }

    override fun onResume() {
        super.onResume()
        refreshDefaultLabel()
        // Сразу подгружаем первое фото из интернета (вместо градиента
        // по умолчанию). Дальше — cycle по таймеру каждые 12 сек.
        view?.findViewById<ImageView>(R.id.homeBgA)?.load("$PHOTO_URL_BASE$bgPhotoSeed") {
            placeholder(FALLBACK_BG_RES[0])
            error(FALLBACK_BG_RES[0])
            memoryCachePolicy(CachePolicy.ENABLED)
            diskCachePolicy(CachePolicy.ENABLED)
        }
        bgHandler.removeCallbacks(bgRunnable)
        bgHandler.postDelayed(bgRunnable, SLIDE_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        bgHandler.removeCallbacks(bgRunnable)
    }

    private fun cycleBackground() {
        val v = view ?: return
        val a = v.findViewById<ImageView>(R.id.homeBgA) ?: return
        val b = v.findViewById<ImageView>(R.id.homeBgB) ?: return
        bgPhotoSeed += 1
        fallbackIndex = (fallbackIndex + 1) % FALLBACK_BG_RES.size
        val photoUrl = "$PHOTO_URL_BASE$bgPhotoSeed"
        val target = if (showingA) b else a
        val other = if (showingA) a else b
        // Coil грузит из сети (с дисковым кэшем), показывает fallback
        // градиент пока картинка не пришла. После загрузки запускаем
        // cross-fade. Если сеть упала — на target останется fallback,
        // и пользователь увидит цветной градиент вместо чёрного экрана.
        target.load(photoUrl) {
            placeholder(FALLBACK_BG_RES[fallbackIndex])
            error(FALLBACK_BG_RES[fallbackIndex])
            memoryCachePolicy(CachePolicy.ENABLED)
            diskCachePolicy(CachePolicy.ENABLED)
            listener(
                onSuccess = { _, _ -> startFade(target, other) },
                onError = { _, _ -> startFade(target, other) },
            )
        }
        showingA = !showingA
    }

    private fun startFade(target: ImageView, other: ImageView) {
        target.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
        other.animate().alpha(0f).setDuration(FADE_DURATION_MS).start()
    }

    private fun refreshDefaultLabel() {
        val tv = view?.findViewById<TextView>(R.id.homeDefaultPlaylist) ?: return
        val name = prefs.lastPlaylistName?.takeIf { it.isNotBlank() }
        tv.text = if (name != null) {
            getString(R.string.home_default_playlist_format, name)
        } else {
            getString(R.string.home_no_default_playlist)
        }
    }

    private fun onLiveClicked() {
        if (liveStarting) return  // защита от двойного клика
        // Если последний контекст был "Избранные" — открываем избранные
        // вместо плейлиста. CH+/CH- продолжит листать по избранным,
        // юзер не теряет контекст после перезапуска приложения.
        if (prefs.lastWasFavorites) {
            val favs = prefs.favoriteChannels
            if (favs.isNotEmpty()) {
                openFavoritesPlayer(favs)
                return
            }
            // Если избранных не осталось — fallback на плейлист.
            prefs.lastWasFavorites = false
        }
        val url = prefs.lastPlaylistUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.home_choose_playlist_first, Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.openPlaylistsTab()
            return
        }
        liveStarting = true
        val v = view
        // Мгновенный feedback: показываем индикатор поверх кнопки —
        // юзер видит что приложение откликнулось на клик. Без этого
        // он тыкал ещё пару раз, думая что не работает.
        val progress = v?.findViewById<View>(R.id.homeLiveProgress)
        progress?.visibility = View.VISIBLE
        v?.findViewById<View>(R.id.btnHomeLive)?.isEnabled = false
        v?.findViewById<View>(R.id.btnHomePlaylists)?.isEnabled = false

        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            try {
                // Если плейлист с этим URL уже загружен — не качаем
                // повторно. Экономит 3-10 сек на TV-боксе с медленным
                // Wi-Fi / у плохо работающих хостеров.
                val cached = ChannelDataHolder.loadedPlaylistUrl == url &&
                    ChannelDataHolder.allChannels.isNotEmpty()
                // Пост-обработка — на Default-диспетчере: сортировка
                // 4000+ каналов (в режиме "quality" — с JSON-парсами) +
                // enrichFavorites шли на Main (lifecycleScope без
                // аргумента = Main) — секундные фризы по нажатию
                // «Эфир». Тот же фикс в MainActivity.playPlaylist.
                val all = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    if (cached) {
                        // Даже если плейлист уже загружен, прогоняем
                        // enrichFavorites на случай если у юзера есть
                        // избранные без sourcePlaylist которые матчатся
                        // с этим плейлистом.
                        prefs.enrichFavorites(ChannelDataHolder.allChannels)
                        ChannelDataHolder.allChannels
                    } else {
                        val res = PlaylistRepository.fetchPlaylist(url, ctx)
                        val custom = prefs.customChannels.map { (n, u) -> Channel(name = n, url = u) }
                        val merged = res.channels + custom
                        if (merged.isEmpty()) {
                            emptyList()
                        } else {
                            // Round 194: учитываем Settings → "Сортировка каналов".
                            val sorted = ChannelSorter.apply(prefs, merged)
                            ChannelDataHolder.allChannels = sorted
                            ChannelDataHolder.loadedPlaylistUrl = url
                            prefs.enrichFavorites(sorted)
                            sorted
                        }
                    }
                }
                if (all.isEmpty()) {
                    Toast.makeText(ctx, R.string.load_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Открываем плейлист → сбрасываем флаг "избранные".
                prefs.lastWasFavorites = false
                val lastChan = prefs.lastChannelUrl
                val idx = all.indexOfFirst { it.url == lastChan }.let { if (it < 0) 0 else it }
                ChannelDataHolder.currentChannelIndex = idx
                val target = all[idx]
                prefs.pushRecentChannel(target)
                requireContext().launchPreferredPlayer(target, idx)
            } catch (e: Exception) {
                ErrorLogger.logException(ctx, e)
                Toast.makeText(ctx, R.string.load_failed, Toast.LENGTH_SHORT).show()
            } finally {
                liveStarting = false
                progress?.visibility = View.GONE
                v?.findViewById<View>(R.id.btnHomeLive)?.isEnabled = true
                v?.findViewById<View>(R.id.btnHomePlaylists)?.isEnabled = true
            }
        }
    }

    /** Открывает плеер с избранными как активным списком. Используется
     *  когда юзер раньше смотрел из Favorites — после перезапуска
     *  приложения "Прямой эфир" вернёт его туда же. */
    private fun openFavoritesPlayer(favs: List<Channel>) {
        ChannelDataHolder.allChannels = favs
        ChannelDataHolder.loadedPlaylistUrl = null
        val lastChan = prefs.lastChannelUrl
        val idx = favs.indexOfFirst { it.url == lastChan }.let { if (it < 0) 0 else it }
        ChannelDataHolder.currentChannelIndex = idx
        val target = favs[idx]
        prefs.pushRecentChannel(target)
        requireContext().launchPreferredPlayer(target, idx)
    }
}
