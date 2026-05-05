package com.tvviewer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class RecentFragment : Fragment() {

    companion object {
        const val TAG = "RecentFragment"
    }

    private lateinit var prefs: AppPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyLayout: LinearLayout
    private lateinit var recentCount: TextView
    private lateinit var adapter: ChannelAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_recent, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        recyclerView = view.findViewById(R.id.recentRecyclerView)
        emptyLayout = view.findViewById(R.id.emptyLayout)
        recentCount = view.findViewById(R.id.recentCount)

        adapter = ChannelAdapter(
            channels = emptyList(),
            favorites = prefs.favorites,
            epgData = ChannelDataHolder.epgData,
            isGridMode = { prefs.listDisplayMode == "grid" },
            onChannelClick = { channel -> playChannel(channel) },
            onFavoriteClick = { channel -> toggleFavorite(channel) }
        )

        applyLayoutManager()
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.btnClearRecent)?.setOnClickListener {
            prefs.clearRecent()
            refreshRecent()
        }

        refreshRecent()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // Tab switched back to us via BottomNavigation (show/hide,
            // not replace) — onResume doesn't fire, so re-apply the
            // layout manager too in case the user toggled list↔grid
            // in Settings while we were hidden.
            applyLayoutManager()
            refreshRecent()
        }
    }

    override fun onResume() {
        super.onResume()
        // Setting can change while fragment is hidden — re-apply layout
        // manager and refresh in case the user switched list↔grid in
        // Settings since the fragment was created.
        applyLayoutManager()
        refreshRecent()
    }

    private fun applyLayoutManager() {
        val grid = prefs.listDisplayMode == "grid"
        val current = recyclerView.layoutManager
        recyclerView.layoutManager = if (grid) {
            if (current is GridLayoutManager) current
            else GridLayoutManager(requireContext(), 2)
        } else {
            if (current is LinearLayoutManager && current !is GridLayoutManager) current
            else LinearLayoutManager(requireContext())
        }
        adapter.notifyDataSetChanged()
    }

    private fun refreshRecent() {
        // Сначала пробуем prefs.recentChannels (новый snapshot список с
        // именами и source playlist). Если он пустой (юзер ещё не
        // просматривал каналов в новой версии) — fallback на старый
        // urls + lookup в текущем плейлисте.
        var recentChannels: List<Channel> = prefs.recentChannels
        if (recentChannels.isEmpty()) {
            val urls = prefs.recentUrls
            val byUrl = ChannelDataHolder.allChannels.associateBy { it.url }
            recentChannels = urls.mapNotNull { byUrl[it] }
        }

        adapter.updateChannels(recentChannels)
        adapter.updateFavorites(prefs.favorites)
        adapter.updateEpg(ChannelDataHolder.epgData)

        recentCount.text = getString(R.string.channels_count, recentChannels.size)

        emptyLayout.visibility = if (recentChannels.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (recentChannels.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun playChannel(channel: Channel) {
        val index = ChannelDataHolder.allChannels.indexOf(channel)
        ChannelDataHolder.currentChannelIndex = if (index >= 0) index else 0
        prefs.pushRecentChannel(channel)
        requireContext().launchPreferredPlayer(channel, ChannelDataHolder.currentChannelIndex)
    }

    private fun toggleFavorite(channel: Channel) {
        if (prefs.isFavorite(channel.url)) {
            prefs.removeFavorite(channel.url)
        } else {
            prefs.addFavorite(channel)  // полный snapshot, не только URL
        }
        refreshRecent()
    }
}
