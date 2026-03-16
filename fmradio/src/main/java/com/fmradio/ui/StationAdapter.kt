package com.fmradio.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fmradio.R
import com.fmradio.data.RadioStation

class StationAdapter(
    private var stations: List<RadioStation>,
    private val onStationClick: (RadioStation) -> Unit,
    private val onFavoriteClick: (RadioStation) -> Unit,
    private val onLongClick: (RadioStation) -> Unit
) : RecyclerView.Adapter<StationAdapter.ViewHolder>() {

    private var selectedFrequency: Long = 0

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFrequency: TextView = view.findViewById(R.id.tvStationFrequency)
        val tvName: TextView = view.findViewById(R.id.tvStationName)
        val tvSignal: TextView = view.findViewById(R.id.tvSignalStrength)
        val btnFavorite: ImageButton = view.findViewById(R.id.btnFavorite)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stations[position]

        holder.tvFrequency.text = station.displayFrequency
        holder.tvName.text = when {
            station.name.isNotEmpty() -> station.name
            station.rdsPs.isNotBlank() -> station.rdsPs
            else -> "---"
        }

        val signalBars = when {
            station.signalStrength > -5 -> "\u2588\u2588\u2588\u2588\u2588"
            station.signalStrength > -10 -> "\u2588\u2588\u2588\u2588"
            station.signalStrength > -15 -> "\u2588\u2588\u2588"
            station.signalStrength > -20 -> "\u2588\u2588"
            else -> "\u2588"
        }
        holder.tvSignal.text = signalBars

        holder.btnFavorite.setImageResource(
            if (station.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )

        holder.itemView.isSelected = station.frequencyHz == selectedFrequency

        holder.itemView.setOnClickListener { onStationClick(station) }
        holder.btnFavorite.setOnClickListener { onFavoriteClick(station) }
        holder.itemView.setOnLongClickListener {
            onLongClick(station)
            true
        }
    }

    override fun getItemCount() = stations.size

    fun updateStations(newStations: List<RadioStation>) {
        stations = newStations
        notifyDataSetChanged()
    }

    fun setSelectedFrequency(freq: Long) {
        selectedFrequency = freq
        notifyDataSetChanged()
    }
}
