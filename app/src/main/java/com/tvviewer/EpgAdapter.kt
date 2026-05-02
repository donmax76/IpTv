package com.tvviewer

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EpgAdapter(
    private val items: List<TvGuideFragment.EpgChannelItem>,
    private val onChannelClick: (Channel) -> Unit
) : RecyclerView.Adapter<EpgAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.epgChannelLogo)
        val name: TextView = view.findViewById(R.id.epgChannelName)
        val liveBadge: TextView = view.findViewById(R.id.epgLiveBadge)
        val nowLayout: LinearLayout = view.findViewById(R.id.epgNowLayout)
        val nowTime: TextView = view.findViewById(R.id.epgNowTime)
        val nowTitle: TextView = view.findViewById(R.id.epgNowTitle)
        val nowProgress: ProgressBar = view.findViewById(R.id.epgNowProgress)
        val nextLayout: LinearLayout = view.findViewById(R.id.epgNextLayout)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val channel = item.channel
        val now = System.currentTimeMillis()

        holder.name.text = channel.name
        // Лого: tvg-logo → обучаемый кэш → iptv-org → placeholder.
        // LearnedLogos знает лого по имени канала из всех плейлистов
        // где они когда-либо встречались.
        val logoUrl = channel.logoUrl
            ?: LearnedLogos.lookup(channel.name)
            ?: ChannelMetaLookup.lookup(channel.name)?.logoUrl
        holder.logo.load(logoUrl) {
            crossfade(true)
            error(R.drawable.ic_channel_placeholder)
            placeholder(R.drawable.ic_channel_placeholder)
        }

        holder.itemView.setOnClickListener { onChannelClick(channel) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                holder.itemView.performClick()
                true
            } else false
        }

        // Компактный single-line формат (как в OTT-плеерах):
        //  • если идёт текущая программа — показываем её название, время
        //    окончания и progressbar внизу
        //  • если EPG пусто — серым "Нет EPG телепрограммы"
        val currentProg = item.programmes.firstOrNull { now in it.start..it.end }
        if (currentProg != null) {
            holder.nowTitle.text = currentProg.title
            holder.nowTime.text = "${timeFormat.format(Date(currentProg.start))}-${timeFormat.format(Date(currentProg.end))}"
            holder.nowTime.visibility = View.VISIBLE
            val total = (currentProg.end - currentProg.start).toFloat()
            val elapsed = (now - currentProg.start).toFloat()
            holder.nowProgress.progress = if (total > 0) ((elapsed / total) * 100).toInt() else 0
            holder.nowProgress.visibility = View.VISIBLE
        } else {
            holder.nowTitle.text = "Нет EPG телепрограммы"
            holder.nowTime.visibility = View.GONE
            holder.nowProgress.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size
}
