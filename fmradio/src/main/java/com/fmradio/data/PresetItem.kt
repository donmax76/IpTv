package com.fmradio.data

data class PresetItem(
    val frequencyHz: Long,
    val name: String = ""
) {
    val frequencyMHz: Double get() = frequencyHz / 1_000_000.0

    val displayFrequency: String
        get() = String.format("%.1f", frequencyMHz)

    val displayName: String
        get() = if (name.isNotEmpty()) name else displayFrequency
}
