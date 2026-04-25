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
            isGridMode = { false },
            onChannelClick = { channel -> playChannel(channel) },
            onFavoriteClick = { channel -> toggleFavorite(channel) }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.btnClearRecent)?.setOnClickListener {
            prefs.clearRecent()
            refreshRecent()
        }

        refreshRecent()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshRecent()
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
    }

    private fun refreshRecent() {
        val urls = prefs.recentUrls
        val byUrl = ChannelDataHolder.allChannels.associateBy { it.url }
        val recentChannels = urls.mapNotNull { byUrl[it] }

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
        prefs.pushRecent(channel.url)

        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_URL, channel.url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, ChannelDataHolder.currentChannelIndex)
        }
        startActivity(intent)
    }

    private fun toggleFavorite(channel: Channel) {
        if (prefs.isFavorite(channel.url)) {
            prefs.removeFavorite(channel.url)
        } else {
            prefs.addFavorite(channel.url)
        }
        refreshRecent()
    }
}
