package com.tvviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private var channels: List<Channel>,
    private var favorites: Set<String>,
    private val isGridMode: () -> Boolean,
    private val onChannelClick: (Channel) -> Unit,
    private val onFavoriteClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val channelName: TextView = view.findViewById(R.id.channelName)
        val channelLogo: ImageView = view.findViewById(R.id.channelLogo)
        val btnFavorite: ImageButton = view.findViewById(R.id.btnFavorite)
    }

    override fun getItemViewType(position: Int) = if (isGridMode()) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == 1) R.layout.item_channel_grid else R.layout.item_channel
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        holder.channelName.text = channel.name
        holder.channelLogo.load(channel.logoUrl) {
            crossfade(true)
            error(android.R.drawable.ic_menu_gallery)
            placeholder(android.R.drawable.ic_menu_gallery)
        }
        holder.btnFavorite.setImageResource(
            if (channel.url in favorites) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )
        holder.btnFavorite.setOnClickListener { onFavoriteClick(channel) }
        holder.itemView.setOnClickListener { onChannelClick(channel) }
    }

    override fun getItemCount() = channels.size

    fun updateChannels(newChannels: List<Channel>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    fun updateFavorites(newFavorites: Set<String>) {
        favorites = newFavorites
        notifyDataSetChanged()
    }

    fun refreshDisplayMode() {
        notifyDataSetChanged()
    }
}
