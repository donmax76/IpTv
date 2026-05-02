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
import kotlinx.coroutines.launch

/** Стартовый экран приложения. Две большие кнопки:
 *   - Прямой эфир: запускает плеер с плейлистом по умолчанию
 *     (prefs.lastPlaylistUrl). Если плейлиста нет — toast подсказка.
 *   - Плейлисты: переключает на PlaylistsFragment.
 *  Под кнопками — текущий default-плейлист. Фон — серия цветных
 *  градиентов которая меняется каждые 8 сек с cross-fade. */
class HomeFragment : Fragment() {

    companion object {
        const val TAG = "HomeFragment"
        private val BG_RES = intArrayOf(
            R.drawable.bg_home_gradient_1,
            R.drawable.bg_home_gradient_2,
            R.drawable.bg_home_gradient_3,
            R.drawable.bg_home_gradient_4,
            R.drawable.bg_home_gradient_5,
        )
        private const val SLIDE_INTERVAL_MS = 8_000L
        private const val FADE_DURATION_MS = 1_200L
    }

    private lateinit var prefs: AppPreferences
    private val bgHandler = Handler(Looper.getMainLooper())
    private var bgIndex = 0
    private var showingA = true
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

    override fun onResume() {
        super.onResume()
        refreshDefaultLabel()
        // Стартуем cycle с задержкой чтобы первый смены не был сразу
        // после открытия — пусть первый bg показывается полный интервал.
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
        bgIndex = (bgIndex + 1) % BG_RES.size
        if (showingA) {
            b.setImageResource(BG_RES[bgIndex])
            b.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
            a.animate().alpha(0f).setDuration(FADE_DURATION_MS).start()
        } else {
            a.setImageResource(BG_RES[bgIndex])
            a.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
            b.animate().alpha(0f).setDuration(FADE_DURATION_MS).start()
        }
        showingA = !showingA
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
        val url = prefs.lastPlaylistUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.home_choose_playlist_first, Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.openPlaylistsTab()
            return
        }
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            try {
                val res = PlaylistRepository.fetchPlaylist(url, ctx)
                val custom = prefs.customChannels.map { (n, u) -> Channel(name = n, url = u) }
                val all = res.channels + custom
                if (all.isEmpty()) {
                    Toast.makeText(ctx, R.string.load_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                ChannelDataHolder.allChannels = all
                val lastChan = prefs.lastChannelUrl
                val idx = all.indexOfFirst { it.url == lastChan }.let { if (it < 0) 0 else it }
                ChannelDataHolder.currentChannelIndex = idx
                val target = all[idx]
                prefs.pushRecent(target.url)
                val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, target.name)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_URL, target.url)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, idx)
                }
                if (prefs.playerType == AppPreferences.PLAYER_EXTERNAL) {
                    requireContext().launchExternalVideo(target.url)
                } else {
                    startActivity(intent)
                }
            } catch (e: Exception) {
                ErrorLogger.logException(ctx, e)
                Toast.makeText(ctx, R.string.load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
