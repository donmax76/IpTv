package com.tvviewer

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
        holder.logo.load(channel.logoUrl) {
            crossfade(true)
            error(R.drawable.ic_channel_placeholder)
            placeholder(R.drawable.ic_channel_placeholder)
        }

        holder.itemView.setOnClickListener { onChannelClick(channel) }

        // Find current programme
        val currentProg = item.programmes.firstOrNull { now in it.start..it.end }
        val upcomingProgs = item.programmes.filter { it.start > now }.take(3)

        if (currentProg != null) {
            holder.nowLayout.visibility = View.VISIBLE
            holder.liveBadge.visibility = View.VISIBLE
            holder.nowTime.text = "${timeFormat.format(Date(currentProg.start))} - ${timeFormat.format(Date(currentProg.end))}"
            holder.nowTitle.text = currentProg.title

            // Progress
            val total = (currentProg.end - currentProg.start).toFloat()
            val elapsed = (now - currentProg.start).toFloat()
            holder.nowProgress.progress = if (total > 0) ((elapsed / total) * 100).toInt() else 0
        } else {
            holder.nowLayout.visibility = View.GONE
            holder.liveBadge.visibility = View.GONE
        }

        // Next programmes
        holder.nextLayout.removeAllViews()
        if (upcomingProgs.isNotEmpty() || (currentProg == null && item.programmes.isNotEmpty())) {
            holder.nextLayout.visibility = View.VISIBLE
            val progsToShow = if (currentProg == null) item.programmes.take(4) else upcomingProgs
            for (prog in progsToShow) {
                val progView = LayoutInflater.from(holder.itemView.context)
                    .inflate(R.layout.item_epg_programme, holder.nextLayout, false)
                progView.findViewById<TextView>(R.id.progTime).text =
                    "${timeFormat.format(Date(prog.start))} - ${timeFormat.format(Date(prog.end))}"
                progView.findViewById<TextView>(R.id.progTitle).text = prog.title
                holder.nextLayout.addView(progView)
            }
        } else {
            holder.nextLayout.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size
}
