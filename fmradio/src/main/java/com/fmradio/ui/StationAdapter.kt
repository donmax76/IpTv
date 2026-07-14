package com.fmradio.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.fmradio.R
import com.fmradio.data.RadioStation

class StationAdapter(
    private var stations: List<RadioStation>,
    private val onStationClick: (RadioStation) -> Unit,
    private val onLongClick: (RadioStation) -> Unit
) : BaseAdapter() {

    private var selectedFrequency: Long = 0

    override fun getCount() = stations.size
    override fun getItem(position: Int) = stations[position]
    override fun getItemId(position: Int) = stations[position].frequencyHz

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station, parent, false)

        val station = stations[position]

        val tvFrequency = view.findViewById<TextView>(R.id.tvStationFrequency)
        val tvName = view.findViewById<TextView>(R.id.tvStationName)
        val tvSignal = view.findViewById<TextView>(R.id.tvSignalStrength)

        tvFrequency.text = station.displayFrequency
        tvName.text = when {
            station.name.isNotEmpty() -> station.name
            station.rdsPs.isNotBlank() -> station.rdsPs
            else -> "---"
        }

        val signalBars = when {
            station.signalStrength > -8 -> "\u2588\u2588\u2588\u2588\u2588"
            station.signalStrength > -12 -> "\u2588\u2588\u2588\u2588"
            station.signalStrength > -18 -> "\u2588\u2588\u2588"
            station.signalStrength > -30 -> "\u2588\u2588"
            else -> "\u2588"
        }
        tvSignal.text = signalBars

        view.isSelected = station.frequencyHz == selectedFrequency

        view.setOnClickListener { onStationClick(station) }
        view.setOnLongClickListener {
            onLongClick(station)
            true
        }

        return view
    }

    fun updateStations(newStations: List<RadioStation>) {
        stations = newStations
        notifyDataSetChanged()
    }

    fun setSelectedFrequency(freq: Long) {
        selectedFrequency = freq
        notifyDataSetChanged()
    }
}
