package com.tvviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class OverlayChannelAdapter(
    private val channels: List<Channel>,
    private val epgData: Map<String, List<EpgRepository.Programme>>,
    private var currentIndex: Int,
    private val onChannelClick: (Int) -> Unit
) : RecyclerView.Adapter<OverlayChannelAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.overlayChannelNumber)
        val logo: ImageView = view.findViewById(R.id.overlayChannelLogo)
        val name: TextView = view.findViewById(R.id.overlayChannelName)
        val epg: TextView = view.findViewById(R.id.overlayChannelEpg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_overlay_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        holder.number.text = "${position + 1}"
        holder.name.text = channel.name

        holder.logo.load(channel.logoUrl) {
            crossfade(true)
            error(R.drawable.ic_channel_placeholder)
            placeholder(R.drawable.ic_channel_placeholder)
        }

        val (now, _) = EpgRepository.getNowNext(epgData, channel.tvgId)
        if (now != null) {
            holder.epg.text = now
            holder.epg.visibility = View.VISIBLE
        } else {
            holder.epg.visibility = View.GONE
        }

        // Highlight current channel
        if (position == currentIndex) {
            holder.itemView.setBackgroundColor(0x406C5CE7.toInt())
        } else {
            holder.itemView.setBackgroundColor(0)
        }

        holder.itemView.setOnClickListener { onChannelClick(position) }
    }

    override fun getItemCount() = channels.size

    fun updateCurrentIndex(index: Int) {
        val old = currentIndex
        currentIndex = index
        notifyItemChanged(old)
        notifyItemChanged(index)
    }
}
