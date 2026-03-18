package com.fmradio.data

/**
 * Represents a saved FM radio station.
 */
data class RadioStation(
    val frequencyHz: Long,
    val name: String = "",
    val isFavorite: Boolean = false,
    val signalStrength: Float = 0f,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val rdsPs: String = "",      // RDS Programme Service name
    val rdsRt: String = "",      // RDS RadioText
    val rdsPty: String = ""      // RDS Programme Type
) {
    val frequencyMHz: Double get() = frequencyHz / 1_000_000.0

    val displayFrequency: String
        get() = String.format("%.1f FM", frequencyMHz)

    val displayName: String
        get() = when {
            name.isNotEmpty() -> name
            rdsPs.isNotBlank() -> rdsPs
            else -> displayFrequency
        }
}
