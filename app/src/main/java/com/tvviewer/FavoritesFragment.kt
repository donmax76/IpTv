package com.tvviewer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FavoritesFragment : Fragment() {

    companion object {
        const val TAG = "FavoritesFragment"
    }

    private lateinit var prefs: AppPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyLayout: LinearLayout
    private lateinit var favCount: TextView
    private lateinit var adapter: ChannelAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_favorites, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        recyclerView = view.findViewById(R.id.favoritesRecyclerView)
        emptyLayout = view.findViewById(R.id.emptyLayout)
        favCount = view.findViewById(R.id.favCount)

        adapter = ChannelAdapter(
            channels = emptyList(),
            favorites = prefs.favorites,
            epgData = ChannelDataHolder.epgData,
            isGridMode = { false },
            onChannelClick = { channel -> playChannel(channel) },
            onFavoriteClick = { channel -> toggleFavorite(channel) }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        refreshFavorites()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshFavorites()
    }

    override fun onResume() {
        super.onResume()
        refreshFavorites()
    }

    private fun refreshFavorites() {
        // Раньше брали favorites из prefs.favorites (Set<URL>) и
        // ФИЛЬТРОВАЛИ ChannelDataHolder.allChannels — но это содержит
        // только текущий плейлист. После смены плейлиста избранные
        // из других плейлистов терялись из вида.
        // Теперь берём ПОЛНЫЕ данные канала из prefs.favoriteChannels —
        // они сохранены при добавлении в избранное и видны независимо
        // от текущего загруженного плейлиста.
        val favoriteChannels = prefs.favoriteChannels
        val favorites = favoriteChannels.map { it.url }.toSet()

        adapter.updateChannels(favoriteChannels)
        adapter.updateFavorites(favorites)
        adapter.updateEpg(ChannelDataHolder.epgData)

        favCount.text = getString(R.string.channels_count, favoriteChannels.size)

        emptyLayout.visibility = if (favoriteChannels.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (favoriteChannels.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun playChannel(channel: Channel) {
        // ВСЕГДА подменяем allChannels на список избранных при
        // запуске из Favorites: пользователь хочет переключаться
        // CH+/CH- ИМЕННО по избранным, и видеть нумерацию "X из N
        // избранных", а не "153 из 6361 канала плейлиста". Раньше
        // подмена работала только если канал был не из текущего
        // плейлиста — пользователь жаловался.
        val favs = prefs.favoriteChannels
        ChannelDataHolder.allChannels = favs
        // Сбрасываем loadedPlaylistUrl, иначе HomeFragment.onLiveClicked
        // подумает что плейлист загружен и не сделает свежий fetch.
        ChannelDataHolder.loadedPlaylistUrl = null
        val idx = favs.indexOfFirst { it.url == channel.url }.coerceAtLeast(0)
        ChannelDataHolder.currentChannelIndex = idx
        prefs.pushRecent(channel.url)

        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_URL, channel.url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, idx)
        }
        startActivity(intent)
    }

    private fun toggleFavorite(channel: Channel) {
        if (prefs.isFavorite(channel.url)) {
            prefs.removeFavorite(channel.url)
        } else {
            prefs.addFavorite(channel)  // полный snapshot
        }
        refreshFavorites()
    }
}
