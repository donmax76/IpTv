package com.tvviewer

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch

class ChannelsFragment : Fragment() {

    companion object {
        const val TAG = "ChannelsFragment"
    }

    private lateinit var prefs: AppPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var categoriesRecyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyLayout: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var playlistTitle: TextView
    private lateinit var channelCount: TextView
    private lateinit var searchEditText: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: ChannelAdapter
    private lateinit var categoryAdapter: CategoryAdapter

    private var allChannels: List<Channel> = emptyList()
    private var filteredChannels: List<Channel> = emptyList()
    private var categories: List<String> = emptyList()
    private var selectedCategory: String = ""
    private var currentPlaylistUrl: String? = null
    private var currentPlaylistName: String? = null
    private var epgData: Map<String, List<EpgRepository.Programme>> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_channels, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        recyclerView = view.findViewById(R.id.channelsRecyclerView)
        categoriesRecyclerView = view.findViewById(R.id.categoriesRecyclerView)
        progressBar = view.findViewById(R.id.progressBar)
        emptyLayout = view.findViewById(R.id.emptyLayout)
        emptyText = view.findViewById(R.id.emptyText)
        playlistTitle = view.findViewById(R.id.playlistTitle)
        channelCount = view.findViewById(R.id.channelCount)
        searchEditText = view.findViewById(R.id.searchEditText)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)

        swipeRefresh.setColorSchemeResources(R.color.primary)
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.surface)

        adapter = ChannelAdapter(
            channels = emptyList(),
            favorites = prefs.favorites,
            epgData = emptyMap(),
            isGridMode = { prefs.listDisplayMode == "grid" },
            onChannelClick = { channel -> playChannel(channel) },
            onFavoriteClick = { channel -> toggleFavorite(channel) }
        )

        setupRecyclerView()

        categoryAdapter = CategoryAdapter(
            categories = emptyList(),
            onCategoryClick = { category ->
                selectedCategory = category
                prefs.lastSelectedGroup = category
                filterChannels()
            }
        )
        categoriesRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        categoriesRecyclerView.adapter = categoryAdapter

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterChannels() }
        })

        swipeRefresh.setOnRefreshListener {
            currentPlaylistUrl?.let { loadPlaylist(currentPlaylistName ?: "", it) }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) checkPendingPlaylist()
    }

    override fun onResume() {
        super.onResume()
        checkPendingPlaylist()
        adapter.updateFavorites(prefs.favorites)
    }

    private fun checkPendingPlaylist() {
        val name = ChannelDataHolder.pendingPlaylistName
        val url = ChannelDataHolder.pendingPlaylistUrl
        if (url != null && url != currentPlaylistUrl) {
            ChannelDataHolder.pendingPlaylistName = null
            ChannelDataHolder.pendingPlaylistUrl = null
            loadPlaylist(name ?: "", url)
        }
    }

    private fun setupRecyclerView() {
        if (prefs.listDisplayMode == "grid") {
            recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        } else {
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
        }
        recyclerView.adapter = adapter
    }

    private fun loadPlaylist(name: String, url: String) {
        currentPlaylistName = name
        currentPlaylistUrl = url
        playlistTitle.text = name.ifEmpty { getString(R.string.channels) }

        progressBar.visibility = View.VISIBLE
        emptyLayout.visibility = View.GONE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = PlaylistRepository.fetchPlaylist(url)
                // Add custom channels
                val customChannels = prefs.customChannels.map { (n, u) -> Channel(name = n, url = u) }
                allChannels = result.channels + customChannels
                ChannelDataHolder.allChannels = allChannels

                // Extract categories
                categories = listOf(getString(R.string.all)) +
                    allChannels.mapNotNull { it.group }.distinct().sorted()
                // Restore last selected group
                val lastGroup = prefs.lastSelectedGroup
                selectedCategory = if (lastGroup != null && categories.contains(lastGroup)) lastGroup
                    else getString(R.string.all)
                categoryAdapter.updateCategories(categories, selectedCategory)

                filterChannels()

                channelCount.text = getString(R.string.channels_count, allChannels.size)
                channelCount.visibility = View.VISIBLE

                progressBar.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                swipeRefresh.isRefreshing = false

                // Load EPG in background
                result.epgUrl?.let { epgUrl ->
                    prefs.lastEpgUrl = epgUrl
                    lifecycleScope.launch {
                        try {
                            epgData = EpgRepository.fetchEpg(epgUrl)
                            ChannelDataHolder.epgData = epgData
                            prefs.epgLastUpdate = System.currentTimeMillis()
                            adapter.updateEpg(epgData)
                        } catch (e: Exception) {
                            Log.e("ChannelsFragment", "EPG error", e)
                        }
                    }
                }

                prefs.lastPlaylistUrl = url
                prefs.lastPlaylistName = name
            } catch (e: Exception) {
                Log.e("ChannelsFragment", "Load error", e)
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                emptyLayout.visibility = View.VISIBLE
                emptyText.text = getString(R.string.load_failed)
                ErrorLogger.logException(requireContext(), e)
            }
        }
    }

    private fun filterChannels() {
        val query = searchEditText.text.toString().trim().lowercase()
        filteredChannels = allChannels.filter { channel ->
            val matchesCategory = selectedCategory == getString(R.string.all) ||
                channel.group == selectedCategory
            val matchesSearch = query.isEmpty() ||
                channel.name.lowercase().contains(query)
            matchesCategory && matchesSearch
        }
        adapter.updateChannels(filteredChannels)

        if (filteredChannels.isEmpty() && allChannels.isNotEmpty()) {
            emptyLayout.visibility = View.VISIBLE
            emptyText.text = getString(R.string.select_channel)
            recyclerView.visibility = View.GONE
        } else {
            emptyLayout.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun playChannel(channel: Channel) {
        val index = allChannels.indexOf(channel)
        ChannelDataHolder.currentChannelIndex = if (index >= 0) index else 0

        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_URL, channel.url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, ChannelDataHolder.currentChannelIndex)
        }

        if (prefs.playerType == AppPreferences.PLAYER_EXTERNAL) {
            val externalIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(channel.url), "video/*")
            }
            try {
                startActivity(externalIntent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), R.string.no_player_app, android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            startActivity(intent)
        }
    }

    private fun toggleFavorite(channel: Channel) {
        if (prefs.isFavorite(channel.url)) {
            prefs.removeFavorite(channel.url)
        } else {
            prefs.addFavorite(channel.url)
        }
        adapter.updateFavorites(prefs.favorites)
    }
}
