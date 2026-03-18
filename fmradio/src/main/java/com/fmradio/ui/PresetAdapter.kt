package com.fmradio.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.TextView
import com.fmradio.R
import com.fmradio.data.PresetItem

class PresetAdapter(
    private var presets: List<PresetItem>,
    private val onPresetClick: (PresetItem) -> Unit,
    private val onPresetLongClick: (PresetItem) -> Unit,
    private val onDeleteClick: (PresetItem) -> Unit
) : BaseAdapter() {

    private var selectedFrequency: Long = 0

    override fun getCount() = presets.size
    override fun getItem(position: Int) = presets[position]
    override fun getItemId(position: Int) = presets[position].frequencyHz

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_preset, parent, false)

        val preset = presets[position]

        val tvIndex = view.findViewById<TextView>(R.id.tvPresetIndex)
        val tvFrequency = view.findViewById<TextView>(R.id.tvPresetFrequency)
        val tvName = view.findViewById<TextView>(R.id.tvPresetName)
        val btnDelete = view.findViewById<ImageButton>(R.id.btnPresetDelete)

        tvIndex.text = "P${position + 1}"
        tvFrequency.text = "${preset.displayFrequency} MHz"

        if (preset.name.isNotEmpty()) {
            tvName.text = preset.name
            tvName.visibility = View.VISIBLE
        } else {
            tvName.visibility = View.GONE
        }

        view.isSelected = preset.frequencyHz == selectedFrequency

        view.setOnClickListener { onPresetClick(preset) }
        view.setOnLongClickListener { onPresetLongClick(preset); true }
        btnDelete.setOnClickListener { onDeleteClick(preset) }

        return view
    }

    fun updatePresets(newPresets: List<PresetItem>) {
        presets = newPresets
        notifyDataSetChanged()
    }

    fun setSelectedFrequency(freq: Long) {
        selectedFrequency = freq
        notifyDataSetChanged()
    }
}
