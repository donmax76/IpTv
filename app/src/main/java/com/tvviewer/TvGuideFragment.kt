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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TvGuideFragment : Fragment() {

    companion object {
        const val TAG = "TvGuideFragment"
    }

    private lateinit var prefs: AppPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyLayout: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var epgStatus: TextView
    private lateinit var searchEditText: EditText
    private lateinit var tvCurrentDate: TextView

    private var allChannelsWithEpg: List<EpgChannelItem> = emptyList()
    private var filteredItems: List<EpgChannelItem> = emptyList()
    private var selectedDateOffset = 0 // 0=today, -1=yesterday, 1=tomorrow

    data class EpgChannelItem(
        val channel: Channel,
        val programmes: List<EpgRepository.Programme>
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_tv_guide, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        recyclerView = view.findViewById(R.id.epgRecyclerView)
        progressBar = view.findViewById(R.id.epgProgressBar)
        emptyLayout = view.findViewById(R.id.epgEmptyLayout)
        emptyText = view.findViewById(R.id.epgEmptyText)
        epgStatus = view.findViewById(R.id.epgStatus)
        searchEditText = view.findViewById(R.id.epgSearchEditText)
        tvCurrentDate = view.findViewById(R.id.tvCurrentDate)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        view.findViewById<ImageButton>(R.id.btnRefreshEpg).setOnClickListener {
            refreshEpg()
        }

        view.findViewById<ImageButton>(R.id.btnPrevDay).setOnClickListener {
            selectedDateOffset--
            updateDateDisplay()
            filterAndDisplay()
        }

        view.findViewById<ImageButton>(R.id.btnNextDay).setOnClickListener {
            selectedDateOffset++
            updateDateDisplay()
            filterAndDisplay()
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterAndDisplay() }
        })

        updateDateDisplay()
        loadEpgData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) loadEpgData()
    }

    private fun updateDateDisplay() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, selectedDateOffset)
        val dateStr = when (selectedDateOffset) {
            0 -> getString(R.string.today)
            1 -> getString(R.string.tomorrow)
            -1 -> getString(R.string.yesterday)
            else -> SimpleDateFormat("dd MMMM", Locale.getDefault()).format(cal.time)
        }
        tvCurrentDate.text = dateStr
    }

    private fun loadEpgData() {
        val channels = ChannelDataHolder.allChannels
        val epgData = ChannelDataHolder.epgData

        if (channels.isEmpty()) {
            emptyLayout.visibility = View.VISIBLE
            emptyText.text = getString(R.string.epg_load_playlist_first)
            recyclerView.visibility = View.GONE
            epgStatus.text = ""
            return
        }

        // Auto-refresh EPG if no data and we have a URL
        if (epgData.isEmpty()) {
            // Try loading from cache first
            val cached = EpgRepository.loadFromCache(requireContext())
            if (cached != null && cached.isNotEmpty()) {
                ChannelDataHolder.epgData = cached
            }

            // allEpgUrls() includes built-in defaults when nothing else
            // is configured, so TV Guide always has at least one source.
            if (prefs.allEpgUrls().isNotEmpty() && ChannelDataHolder.epgData.isEmpty()) {
                refreshEpg()
                return
            }
        }

        fun norm(s: String?): String =
            s?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: ""
        allChannelsWithEpg = channels.map { ch ->
            // Try by tvg-id first, then by normalized channel name
            // (xmltv parser also indexes channels by display-name now).
            val byId = epgData[norm(ch.tvgId)]
            val byName = if (byId.isNullOrEmpty()) epgData[norm(ch.name)] else byId
            EpgChannelItem(ch, byName ?: emptyList())
        }

        val channelsWithData = allChannelsWithEpg.count { it.programmes.isNotEmpty() }
        epgStatus.text = getString(R.string.epg_channels_count, channelsWithData)

        if (prefs.epgLastUpdate > 0) {
            val dateStr = SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date(prefs.epgLastUpdate))
            epgStatus.text = "${epgStatus.text} • ${getString(R.string.epg_last_update, dateStr)}"
        }

        if (channelsWithData == 0) {
            emptyLayout.visibility = View.VISIBLE
            emptyText.text = getString(R.string.epg_no_data)
            recyclerView.visibility = View.GONE
            return
        }

        filterAndDisplay()
    }

    private fun filterAndDisplay() {
        val query = searchEditText.text.toString().trim().lowercase()

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, selectedDateOffset)
        val dayStart = cal.clone() as Calendar
        dayStart.set(Calendar.HOUR_OF_DAY, 0)
        dayStart.set(Calendar.MINUTE, 0)
        dayStart.set(Calendar.SECOND, 0)
        val dayEnd = cal.clone() as Calendar
        dayEnd.set(Calendar.HOUR_OF_DAY, 23)
        dayEnd.set(Calendar.MINUTE, 59)
        dayEnd.set(Calendar.SECOND, 59)

        filteredItems = allChannelsWithEpg
            .filter { item ->
                query.isEmpty() || item.channel.name.lowercase().contains(query)
            }
            .map { item ->
                val dayProgs = item.programmes.filter { p ->
                    p.start <= dayEnd.timeInMillis && p.end >= dayStart.timeInMillis
                }
                item.copy(programmes = dayProgs)
            }

        if (filteredItems.isEmpty()) {
            emptyLayout.visibility = View.VISIBLE
            emptyText.text = getString(R.string.epg_no_data)
            recyclerView.visibility = View.GONE
        } else {
            emptyLayout.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            recyclerView.adapter = EpgAdapter(filteredItems) { channel ->
                playChannel(channel)
            }
        }
    }

    private fun refreshEpg() {
        val urls = prefs.allEpgUrls()
        if (urls.isEmpty()) {
            Toast.makeText(requireContext(), R.string.epg_no_data, Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        // Use application context for the network call so the work survives
        // a fragment detach. UI updates after that bail out if the fragment
        // is no longer attached (was crashing as IllegalStateException).
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            try {
                val data = EpgRepository.fetchAll(urls, appCtx)
                ChannelDataHolder.epgData = data
                prefs.epgLastUpdate = System.currentTimeMillis()
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                loadEpgData()
                Toast.makeText(appCtx, R.string.epg_updated, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "EPG refresh error", e)
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                Toast.makeText(appCtx, R.string.epg_update_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playChannel(channel: Channel) {
        val index = ChannelDataHolder.allChannels.indexOf(channel)
        ChannelDataHolder.currentChannelIndex = if (index >= 0) index else 0

        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_URL, channel.url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, ChannelDataHolder.currentChannelIndex)
        }
        startActivity(intent)
    }
}
