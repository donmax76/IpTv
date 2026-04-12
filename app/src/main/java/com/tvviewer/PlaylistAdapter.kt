package com.tvviewer

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlaylistAdapter(
    private var playlists: List<Pair<String, String>>,
    private var customCount: Int = 0,
    private val onPlaylistClick: (Pair<String, String>) -> Unit,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlistName)
        val url: TextView = view.findViewById(R.id.playlistUrl)
        val channelCount: TextView = view.findViewById(R.id.playlistChannelCount)
        val icon: ImageView = view.findViewById(R.id.playlistIcon)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = playlists[position]
        holder.name.text = playlist.first
        holder.url.text = playlist.second

        // Show delete button only for custom playlists
        val isCustom = position < customCount
        holder.btnDelete.visibility = if (isCustom) View.VISIBLE else View.GONE
        holder.btnDelete.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos in 0 until customCount) {
                onDeleteClick(pos)
            }
        }

        // Make delete button focusable for D-pad
        holder.btnDelete.isFocusable = isCustom
        holder.btnDelete.isFocusableInTouchMode = false

        // Different icon tint for built-in vs custom
        holder.icon.setColorFilter(
            if (isCustom) holder.itemView.context.getColor(R.color.secondary)
            else holder.itemView.context.getColor(R.color.primary)
        )

        holder.itemView.setOnClickListener { onPlaylistClick(playlist) }

        // Handle D-pad center/enter on focused card
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                holder.itemView.performClick()
                true
            } else if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isCustom) {
                // D-pad right to focus delete button
                holder.btnDelete.requestFocus()
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount() = playlists.size

    fun updatePlaylists(newPlaylists: List<Pair<String, String>>, newCustomCount: Int) {
        playlists = newPlaylists
        customCount = newCustomCount
        notifyDataSetChanged()
    }
}
