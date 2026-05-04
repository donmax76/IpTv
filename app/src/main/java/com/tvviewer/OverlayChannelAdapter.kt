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
    initialChannels: List<Channel>,
    private var epgData: Map<String, List<EpgRepository.Programme>>,
    private var currentIndex: Int,
    private var favorites: Set<String> = emptySet(),
    private val onChannelClick: (Int) -> Unit,
    private val onFavoriteClick: ((Channel) -> Unit)? = null,
    // DPAD_RIGHT после "избранное" — показываем детальную информацию
    // о текущей программе на канале.
    private val onShowDetailsClick: ((Channel) -> Unit)? = null
) : RecyclerView.Adapter<OverlayChannelAdapter.ViewHolder>() {

    private var channels: List<Channel> = initialChannels

    /** Обновляет содержимое списка без пересоздания адаптера. Использует
     *  notifyDataSetChanged потому что и список и индексы меняются
     *  целиком (например при фильтре поиска). */
    fun updateChannels(newChannels: List<Channel>, newCurrentIndex: Int) {
        channels = newChannels
        currentIndex = newCurrentIndex
        notifyDataSetChanged()
    }

    /** Подменяет EPG-карту и перерисовывает все строки. Вызывается когда
     *  фоновый fetchAll / loadFromCache принёс свежие данные — без этого
     *  список открытый ДО прихода EPG так и оставался без программы
     *  (детали в боковой панели появлялись потому что они дёргают
     *  EpgRepository.getNowNextDetailed заново при открытии). */
    fun updateEpg(newEpg: Map<String, List<EpgRepository.Programme>>) {
        epgData = newEpg
        notifyDataSetChanged()
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.overlayChannelNumber)
        val logo: ImageView = view.findViewById(R.id.overlayChannelLogo)
        val name: TextView = view.findViewById(R.id.overlayChannelName)
        val epg: TextView = view.findViewById(R.id.overlayChannelEpg)
        val source: TextView = view.findViewById(R.id.overlayChannelSource)
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
        val ctx = holder.itemView.context
        val knownH = AppPreferences(ctx).getChannelHeight(channel.url)
        val realLabel = QualityUtil.detectByHeight(knownH)
        holder.name.text = QualityUtil.formatNameWithQualityBadge(
            ctx, channel.name, realLabel
        )
        // Источник плейлиста (для избранных) — "▸ Россия".
        val src = channel.sourcePlaylist
        if (!src.isNullOrBlank()) {
            holder.source.text = "▸ $src"
            holder.source.visibility = View.VISIBLE
        } else {
            holder.source.visibility = View.GONE
        }

        // Лого: tvg-logo → обучаемый кэш → iptv-org.
        val logoUrl = channel.logoUrl
            ?: LearnedLogos.lookup(channel.name)
            ?: ChannelMetaLookup.lookup(channel.name)?.logoUrl
        holder.logo.load(logoUrl) {
            crossfade(true)
            error(R.drawable.ic_channel_placeholder)
            placeholder(R.drawable.ic_channel_placeholder)
        }

        // EPG now/next with time
        val (nowProg, nextProg) = EpgRepository.getNowNextDetailed(epgData, channel.tvgId, channel.name)
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

        // Highlight the currently-playing channel WITHOUT overriding the
        // background (the bg_epg_item selector handles focus state and we
        // must keep it intact so the D-pad focus highlight works).
        holder.itemView.isSelected = (position == currentIndex)
        holder.name.setTextColor(
            if (position == currentIndex) 0xFF7C6CF7.toInt() else 0xFFFFFFFF.toInt()
        )
        holder.number.setTextColor(
            if (position == currentIndex) 0xFF7C6CF7.toInt() else 0xFFFFFFFF.toInt()
        )

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
        holder.itemView.setOnLongClickListener {
            onFavoriteClick?.invoke(channel); true
        }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        holder.favoriteBtn.requestFocus(); true
                    }
                    android.view.KeyEvent.KEYCODE_F,
                    android.view.KeyEvent.KEYCODE_BUTTON_Y,
                    android.view.KeyEvent.KEYCODE_PROG_YELLOW,
                    android.view.KeyEvent.KEYCODE_BOOKMARK -> {
                        onFavoriteClick?.invoke(channel); true
                    }
                    else -> false
                }
            } else false
        }
        holder.favoriteBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        holder.itemView.requestFocus(); true
                    }
                    // Ещё раз вправо после "избранное" — детальная инфа.
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onShowDetailsClick?.invoke(channel); true
                    }
                    else -> false
                }
            } else false
        }
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
