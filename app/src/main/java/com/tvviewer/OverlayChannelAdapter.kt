package com.tvviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayChannelAdapter(
    private val channels: List<Channel>,
    private val epgData: Map<String, List<EpgRepository.Programme>>,
    private var currentIndex: Int,
    private var favorites: Set<String> = emptySet(),
    private val onChannelClick: (Int) -> Unit,
    private val onFavoriteClick: ((Channel) -> Unit)? = null
) : RecyclerView.Adapter<OverlayChannelAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.overlayChannelNumber)
        val logo: ImageView = view.findViewById(R.id.overlayChannelLogo)
        val name: TextView = view.findViewById(R.id.overlayChannelName)
        val epg: TextView = view.findViewById(R.id.overlayChannelEpg)
        val favoriteBtn: ImageButton = view.findViewById(R.id.overlayFavoriteBtn)
        val epgProgress: ProgressBar = view.findViewById(R.id.overlayEpgProgress)
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

        // EPG now/next with time
        val (nowProg, nextProg) = EpgRepository.getNowNextDetailed(epgData, channel.tvgId)
        if (nowProg != null) {
            val nowTime = timeFormat.format(Date(nowProg.start))
            val nowEndTime = timeFormat.format(Date(nowProg.end))
            holder.epg.text = "$nowTime-$nowEndTime ${nowProg.title}"
            holder.epg.visibility = View.VISIBLE

            // Progress bar
            val progress = EpgRepository.getCurrentProgress(nowProg)
            holder.epgProgress.progress = (progress * 100).toInt()
            holder.epgProgress.visibility = View.VISIBLE
        } else {
            holder.epg.visibility = View.GONE
            holder.epgProgress.visibility = View.GONE
        }

        // Highlight current channel
        if (position == currentIndex) {
            holder.itemView.setBackgroundColor(0x407C6CF7.toInt())
            holder.name.setTextColor(0xFFFFFFFF.toInt())
        } else {
            holder.itemView.setBackgroundColor(0)
            holder.name.setTextColor(0xFFFFFFFF.toInt())
        }

        // Favorites
        val isFav = channel.url in favorites
        holder.favoriteBtn.setImageResource(
            if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        holder.favoriteBtn.setColorFilter(
            if (isFav) 0xFFFF5252.toInt() else 0x80FFFFFF.toInt()
        )
        holder.favoriteBtn.setOnClickListener {
            onFavoriteClick?.invoke(channel)
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

    fun updateFavorites(newFavorites: Set<String>) {
        favorites = newFavorites
        notifyDataSetChanged()
    }
}
