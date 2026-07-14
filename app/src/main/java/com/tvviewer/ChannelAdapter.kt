package com.tvviewer

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private var channels: List<Channel>,
    private var favorites: Set<String>,
    private var epgData: Map<String, List<EpgRepository.Programme>> = emptyMap(),
    private val isGridMode: () -> Boolean,
    private val onChannelClick: (Channel) -> Unit,
    private val onFavoriteClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val channelNumber: TextView? = view.findViewById(R.id.channelNumber)
        val channelName: TextView = view.findViewById(R.id.channelName)
        val channelLogo: ImageView = view.findViewById(R.id.channelLogo)
        val channelEpg: TextView? = view.findViewById(R.id.channelEpg)
        val channelGroup: TextView? = view.findViewById(R.id.channelGroup)
        val btnFavorite: ImageButton = view.findViewById(R.id.btnFavorite)
    }

    override fun getItemViewType(position: Int) = if (isGridMode()) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == 1) R.layout.item_channel_grid else R.layout.item_channel
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        // Если notify пришёл только с пометкой "epg" — обновляем
        // ТОЛЬКО now/next текст и progress, НЕ дёргая Coil заново
        // на лого. Это убирает мерцание лого при каждом обновлении
        // EPG (Round 120's epgUpdateListener вызывал updateEpg ->
        // notifyDataSetChanged -> .load() заново -> placeholder ->
        // лого, что выглядит как "лого пропали и вернулись").
        if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_EPG)) {
            val channel = channels.getOrNull(position) ?: return
            bindEpgOnly(holder, channel)
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        val context = holder.itemView.context

        holder.channelNumber?.text = "${position + 1}"

        // Render the channel name with the quality token (HD / FHD / 4K /
        // SD / 1080p …) coloured so it pops out without being mistaken for
        // part of the channel name.
        // Бейдж качества: сначала фактическая высота из prefs (если канал
        // когда-то открывался), иначе парсинг имени канала.
        val knownH = AppPreferences(context).getChannelHeight(channel.url)
        val realLabel = QualityUtil.detectByHeight(knownH)
        holder.channelName.text = QualityUtil.formatNameWithQualityBadge(
            context, channel.name, realLabel
        )

        // Подзаголовок: для избранных каналов показываем имя
        // плейлиста-источника (▸ Россия), для обычных — группу
        // канала из плейлиста (если есть). sourcePlaylist имеет
        // приоритет: для favorites юзеру важнее знать ОТКУДА канал.
        holder.channelGroup?.let { tv ->
            val src = channel.sourcePlaylist
            val grp = channel.group
            when {
                !src.isNullOrBlank() -> {
                    tv.text = "▸ $src"
                    tv.visibility = View.VISIBLE
                }
                !grp.isNullOrBlank() -> {
                    tv.text = grp
                    tv.visibility = View.VISIBLE
                }
                else -> tv.visibility = View.GONE
            }
        }

        // Logo priority: tvg-logo > learned cache > iptv-org name lookup.
        // LearnedLogos накапливает (имя→лого) из всех когда-либо
        // открытых плейлистов, чтобы лого с одного плейлиста
        // подхватывалось в других.
        val resolvedLogo = channel.logoUrl
            ?: LearnedLogos.lookup(channel.name)
            ?: ChannelMetaLookup.lookup(channel.name)?.logoUrl
        // Round 221c: если ни одно из трёх не дало URL — рисуем плашку
        // с инициалами и цветом из имени канала вместо пустого
        // placeholder'а.
        val tile = LetterTileDrawable(channel.name)
        // Android Round 353: дохлые URL не грузим повторно — см.
        // FailedLogoUrls (fallback(tile) обрабатывает null).
        val logoToLoad = resolvedLogo?.takeUnless(FailedLogoUrls::isFailed)
        holder.channelLogo.load(logoToLoad) {
            crossfade(true)
            error(tile)
            placeholder(tile)
            fallback(tile)
            listener(onError = { req, _ ->
                FailedLogoUrls.markFailed(req.data as? String)
            })
        }

        // EPG
        val (now, next) = EpgRepository.getNowNext(epgData, channel.tvgId, channel.name)
        holder.channelEpg?.let { epg ->
            epg.text = when {
                now != null -> now
                next != null -> "-> $next"
                else -> null
            }
            epg.visibility = if (epg.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        // Favorite
        val isFav = channel.url in favorites
        holder.btnFavorite.setColorFilter(
            ContextCompat.getColor(context, if (isFav) R.color.favorite_active else R.color.favorite_inactive)
        )

        holder.btnFavorite.isFocusable = true
        holder.btnFavorite.isFocusableInTouchMode = false
        holder.btnFavorite.setOnClickListener { onFavoriteClick(channel) }
        holder.itemView.setOnClickListener { onChannelClick(channel) }
        // Long-press on the row also toggles favourite (works with mouse and
        // remote OK-hold, in addition to the dedicated button)
        holder.itemView.setOnLongClickListener {
            onFavoriteClick(channel)
            true
        }

        // D-pad: center/enter selects channel, right focuses favorite button,
        // F (or yellow / channel-info button) toggles favourite directly.
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        holder.itemView.performClick()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        holder.btnFavorite.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_F,
                    KeyEvent.KEYCODE_BUTTON_Y,
                    KeyEvent.KEYCODE_PROG_YELLOW,
                    KeyEvent.KEYCODE_BOOKMARK -> {
                        onFavoriteClick(channel)
                        true
                    }
                    else -> false
                }
            } else false
        }

        // D-pad on favorite button:
        //   LEFT  — back to channel card
        //   UP/DOWN — eaten so focus stays on the heart of the current row
        //             (prevents accidentally favouriting the wrong channel
        //             if user keeps the heart "armed" and nudges UP/DOWN).
        holder.btnFavorite.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        // Round 200: clearFocus + post чтобы фокус
                        // действительно переехал на строку (см. коммент
                        // в OverlayChannelAdapter).
                        holder.btnFavorite.clearFocus()
                        holder.itemView.post { holder.itemView.requestFocus() }
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    override fun getItemCount() = channels.size

    fun getChannels(): List<Channel> = channels

    fun updateChannels(newChannels: List<Channel>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    fun updateEpg(epg: Map<String, List<EpgRepository.Programme>>) {
        epgData = epg
        // payload "epg" — onBindViewHolder с payloads обновит только
        // EPG-текст, не перегружая лого через Coil. Без этого после
        // каждого fetchAll в списке мерцали все логотипы.
        notifyItemRangeChanged(0, channels.size, PAYLOAD_EPG)
    }

    /** Обновляет только EPG-зависимые view — без trogания логотипа. */
    private fun bindEpgOnly(holder: ViewHolder, channel: Channel) {
        val (now, next) = EpgRepository.getNowNext(epgData, channel.tvgId, channel.name)
        holder.channelEpg?.let { epg ->
            epg.text = when {
                now != null -> now
                next != null -> "-> $next"
                else -> null
            }
            epg.visibility = if (epg.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    companion object {
        private const val PAYLOAD_EPG = "epg"
    }

    fun updateFavorites(newFavorites: Set<String>) {
        favorites = newFavorites
        notifyDataSetChanged()
    }
}
