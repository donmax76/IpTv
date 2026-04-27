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
    private lateinit var qualityChipsContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyLayout: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var playlistTitle: TextView
    private lateinit var channelCount: TextView
    private lateinit var searchEditText: EditText
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: ChannelAdapter
    private lateinit var categoryAdapter: CategoryAdapter

    private val qualityFilters = listOf("all", "4K", "FHD", "HD", "SD")

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
        qualityChipsContainer = view.findViewById(R.id.qualityChipsContainer)
        setupQualityChips()
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

        // Refresh once the iptv-org database arrives so logos / EPG hooks
        // appear without the user needing to restart the app.
        ChannelMetaLookup.onLoaded {
            if (isAdded) adapter.notifyDataSetChanged()
        }

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
        // List/grid mode could have been changed in Settings while we
        // were paused — refresh the layout manager AND tell the adapter
        // to re-bind so item type (list vs grid card) matches the new mode.
        val needsGrid = prefs.listDisplayMode == "grid"
        val isGrid = recyclerView.layoutManager is GridLayoutManager
        if (needsGrid != isGrid) {
            setupRecyclerView()
            adapter.notifyDataSetChanged()
        }
        // Возвращаемся из плеера → прокручиваем список к текущему каналу и
        // даём ему фокус, чтобы пользователь сразу видел, на каком канале
        // он остановился.
        scrollToCurrentChannel()
    }

    private fun scrollToCurrentChannel() {
        val idx = ChannelDataHolder.currentChannelIndex
        if (idx < 0 || idx >= ChannelDataHolder.allChannels.size) return
        val targetUrl = ChannelDataHolder.allChannels[idx].url
        val pos = filteredChannels.indexOfFirst { it.url == targetUrl }
        if (pos < 0) return
        recyclerView.post {
            recyclerView.scrollToPosition(pos)
            recyclerView.postDelayed({
                val vh = recyclerView.findViewHolderForAdapterPosition(pos)
                vh?.itemView?.requestFocus()
            }, 80)
        }
    }

    private fun checkPendingPlaylist() {
        val name = ChannelDataHolder.pendingPlaylistName
        val url = ChannelDataHolder.pendingPlaylistUrl
        if (url != null && url != currentPlaylistUrl) {
            ChannelDataHolder.pendingPlaylistName = null
            ChannelDataHolder.pendingPlaylistUrl = null
            loadPlaylist(name ?: "", url)
            return
        }
        // Если фрагмент создан заново (например, пользователь нажал
        // стрелку Назад в плеере, а ChannelsFragment ещё не был открыт),
        // pendingPlaylistUrl пуст, а сами Каналы — пусты. Подтягиваем
        // последний плейлист из настроек, чтобы экран не оставался
        // пустым.
        if (currentPlaylistUrl == null && allChannels.isEmpty()) {
            val lastUrl = prefs.lastPlaylistUrl
            if (!lastUrl.isNullOrBlank()) {
                loadPlaylist(prefs.lastPlaylistName ?: "", lastUrl)
            }
        }
    }

    private fun setupRecyclerView() {
        if (prefs.listDisplayMode == "grid") {
            val columns = resources.getInteger(R.integer.grid_columns)
            recyclerView.layoutManager = GridLayoutManager(requireContext(), columns)
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
                val result = PlaylistRepository.fetchPlaylist(url, requireContext().applicationContext)
                // Add custom channels
                val customChannels = prefs.customChannels.map { (n, u) -> Channel(name = n, url = u) }
                allChannels = result.channels + customChannels
                ChannelDataHolder.allChannels = allChannels

                // Extract categories. Multi-tag composites in iptv-org
                // playlists ("Culture;Education;Lifestyle") are pure noise on
                // a remote, so we take only the first segment of each group
                // and dedupe. For narrow genre playlists (Movies/Sports/News
                // /Music) we hide the bar entirely — there are no useful
                // sub-categories there.
                val genreNames = setOf(
                    "movies", "кино", "фильмы",
                    "sport", "sports", "спорт",
                    "news", "новости",
                    "music", "музыка",
                    "documentary", "документальные",
                    "kids", "детям",
                )
                val plName = (name ?: "").lowercase()
                val isGenrePlaylist = genreNames.any { it in plName }
                val rawCats = allChannels.asSequence()
                    .mapNotNull { it.group }
                    .map { g ->
                        // Take only the leading clean segment.
                        g.split(';', ',', '|').first().trim()
                    }
                    .filter { it.isNotEmpty() && it.length <= 30 }
                    .toSet()
                    .sorted()
                categories = if (isGenrePlaylist) {
                    listOf(getString(R.string.all))
                } else {
                    listOf(getString(R.string.all)) + rawCats
                }
                categoriesRecyclerView.visibility =
                    if (isGenrePlaylist) View.GONE else View.VISIBLE
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

                // Load EPG - first from cache, then from network
                lifecycleScope.launch {
                    try {
                        val ctx = context ?: return@launch
                        // Try loading from cache first for instant display
                        val cached = EpgRepository.loadFromCache(ctx)
                        if (cached != null && cached.isNotEmpty() && epgData.isEmpty()) {
                            epgData = cached
                            ChannelDataHolder.epgData = epgData
                            adapter.updateEpg(epgData)
                            Log.d("ChannelsFragment", "EPG loaded from cache: ${cached.size} channels")
                        }

                        // Then fetch fresh data from network — combine playlist's own
                        // x-tvg-url with user-configured URLs and fetch them in parallel.
                        val playlistEpg = result.epgUrl
                        if (!playlistEpg.isNullOrBlank() && prefs.lastEpgUrl.isNullOrBlank()) {
                            prefs.lastEpgUrl = playlistEpg
                        }
                        val urls = buildList {
                            playlistEpg?.takeIf { it.isNotBlank() }?.let { add(it) }
                            addAll(prefs.allEpgUrls())
                        }.distinct()
                        if (urls.isNotEmpty()) {
                            val freshData = EpgRepository.fetchAll(urls, ctx)
                            if (freshData.isNotEmpty() && isAdded) {
                                epgData = freshData
                                ChannelDataHolder.epgData = epgData
                                prefs.epgLastUpdate = System.currentTimeMillis()
                                adapter.updateEpg(epgData)
                                Log.d("ChannelsFragment", "EPG updated from network: ${freshData.size} channels (${urls.size} sources)")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChannelsFragment", "EPG error", e)
                    }
                }

                prefs.lastPlaylistUrl = url
                prefs.lastPlaylistName = name
            } catch (e: Exception) {
                Log.e("ChannelsFragment", "Load error", e)
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                emptyLayout.visibility = View.VISIBLE
                emptyText.text = getString(R.string.load_failed)
                context?.let { ErrorLogger.logException(it, e) }
            }
        }
    }

    private fun setupQualityChips() {
        qualityChipsContainer.removeAllViews()
        val ctx = requireContext()
        val current = prefs.qualityFilter
        qualityFilters.forEach { q ->
            val chip = TextView(ctx).apply {
                text = if (q == "all") getString(R.string.all) else q
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                setPadding(28, 12, 28, 12)
                background = androidx.core.content.ContextCompat.getDrawable(
                    ctx, R.drawable.bg_category_chip
                )
                isFocusable = true
                isClickable = true
                isSelected = (q == current)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 16
                layoutParams = lp
                setOnClickListener {
                    prefs.qualityFilter = q
                    // Refresh selected state on all children
                    for (i in 0 until qualityChipsContainer.childCount) {
                        val child = qualityChipsContainer.getChildAt(i)
                        child.isSelected = (i < qualityFilters.size && qualityFilters[i] == q)
                    }
                    filterChannels()
                }
            }
            qualityChipsContainer.addView(chip)
        }
    }

    private fun applySort(channels: List<Channel>): List<Channel> {
        return when (prefs.channelSort) {
            "name" -> channels.sortedBy { it.name.lowercase() }
            "group" -> channels.sortedWith(compareBy({ it.group ?: "zzz" }, { it.name.lowercase() }))
            "number" -> channels // M3U order — unchanged.
            "quality" -> channels.sortedByDescending { QualityUtil.rank(it.name) }
            else -> channels
        }
    }

    private fun filterChannels() {
        val query = searchEditText.text.toString().trim().lowercase()
        val qualityFilter = prefs.qualityFilter
        val sorted = applySort(allChannels)
        filteredChannels = sorted.filter { channel ->
            // Compare against the canonical leading segment of the group so
            // a chip "Culture" matches channels tagged "Culture;Education;Lifestyle"
            val canonicalGroup = channel.group?.split(';', ',', '|')?.firstOrNull()?.trim()
            val matchesCategory = selectedCategory == getString(R.string.all) ||
                canonicalGroup == selectedCategory
            val matchesSearch = query.isEmpty() ||
                channel.name.lowercase().contains(query)
            val matchesQuality = qualityFilter == "all" ||
                QualityUtil.detectQuality(channel.name) == qualityFilter
            matchesCategory && matchesSearch && matchesQuality
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
        prefs.pushRecent(channel.url)

        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_URL, channel.url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, ChannelDataHolder.currentChannelIndex)
        }

        if (prefs.playerType == AppPreferences.PLAYER_EXTERNAL) {
            requireContext().launchExternalVideo(channel.url)
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
