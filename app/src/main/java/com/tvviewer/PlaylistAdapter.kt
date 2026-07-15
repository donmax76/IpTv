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
    private val onDeleteClick: (Int) -> Unit,
    // Android Round 372: отдельная кнопка «Редактировать».
    private val onEditClick: (Int) -> Unit = {}
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlistName)
        val url: TextView = view.findViewById(R.id.playlistUrl)
        val channelCount: TextView = view.findViewById(R.id.playlistChannelCount)
        val icon: ImageView = view.findViewById(R.id.playlistIcon)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
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

        // Android Round 372: две отдельные видимые кнопки у СВОИХ
        // плейлистов — «Редактировать» (карандаш) и «Удалить»
        // (корзина). Долгое нажатие полностью убрано: на ТВ оно давало
        // двойное срабатывание (открывался и плейлист, и меню). Клик по
        // строке — открыть плейлист; кнопки — правка/удаление.
        val isCustom = position < customCount
        if (isCustom) {
            holder.btnEdit.visibility = View.VISIBLE
            holder.btnEdit.isFocusable = true
            holder.btnEdit.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos in 0 until customCount) onEditClick(pos)
            }
            holder.btnDelete.visibility = View.VISIBLE
            holder.btnDelete.isFocusable = true
            holder.btnDelete.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos in 0 until customCount) onDeleteClick(pos)
            }
        } else {
            holder.btnEdit.visibility = View.GONE
            holder.btnEdit.isFocusable = false
            holder.btnEdit.setOnClickListener(null)
            holder.btnDelete.visibility = View.GONE
            holder.btnDelete.isFocusable = false
            holder.btnDelete.setOnClickListener(null)
        }

        // Different icon tint for built-in vs custom
        holder.icon.setColorFilter(
            if (isCustom) holder.itemView.context.getColor(R.color.secondary)
            else holder.itemView.context.getColor(R.color.primary)
        )

        // Короткий клик по строке — открыть плейлист. Long-press НЕ
        // используется (см. выше).
        holder.itemView.setOnClickListener { onPlaylistClick(playlist) }
        holder.itemView.setOnLongClickListener(null)
        holder.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == KeyEvent.KEYCODE_ENTER)) {
                v.performClick()
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
