package com.fmradio.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistent storage for saved radio stations using SharedPreferences + Gson.
 */
class StationStorage(context: Context) {

    companion object {
        private const val PREFS_NAME = "fm_radio_stations"
        private const val KEY_STATIONS = "saved_stations"
        private const val KEY_LAST_FREQUENCY = "last_frequency"
        private const val KEY_LAST_VOLUME = "last_volume"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveStations(stations: List<RadioStation>) {
        val json = gson.toJson(stations)
        prefs.edit().putString(KEY_STATIONS, json).apply()
    }

    fun loadStations(): List<RadioStation> {
        val json = prefs.getString(KEY_STATIONS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<RadioStation>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addStation(station: RadioStation) {
        val stations = loadStations().toMutableList()
        // Avoid duplicates (within 50 kHz)
        stations.removeAll { Math.abs(it.frequencyHz - station.frequencyHz) < 50000 }
        stations.add(station)
        stations.sortBy { it.frequencyHz }
        saveStations(stations)
    }

    fun removeStation(frequencyHz: Long) {
        val stations = loadStations().toMutableList()
        stations.removeAll { it.frequencyHz == frequencyHz }
        saveStations(stations)
    }

    fun updateStation(station: RadioStation) {
        val stations = loadStations().toMutableList()
        val index = stations.indexOfFirst { it.frequencyHz == station.frequencyHz }
        if (index >= 0) {
            stations[index] = station
            saveStations(stations)
        }
    }

    fun toggleFavorite(frequencyHz: Long): RadioStation? {
        val stations = loadStations().toMutableList()
        val index = stations.indexOfFirst { it.frequencyHz == frequencyHz }
        if (index >= 0) {
            val updated = stations[index].copy(isFavorite = !stations[index].isFavorite)
            stations[index] = updated
            saveStations(stations)
            return updated
        }
        return null
    }

    fun renameStation(frequencyHz: Long, newName: String) {
        val stations = loadStations().toMutableList()
        val index = stations.indexOfFirst { it.frequencyHz == frequencyHz }
        if (index >= 0) {
            stations[index] = stations[index].copy(name = newName)
            saveStations(stations)
        }
    }

    fun clearAllStations() {
        prefs.edit().remove(KEY_STATIONS).apply()
    }

    var lastFrequency: Long
        get() = prefs.getLong(KEY_LAST_FREQUENCY, 100000000L) // Default 100.0 MHz
        set(value) = prefs.edit().putLong(KEY_LAST_FREQUENCY, value).apply()

    var lastVolume: Float
        get() = prefs.getFloat(KEY_LAST_VOLUME, 0.8f)
        set(value) = prefs.edit().putFloat(KEY_LAST_VOLUME, value).apply()
}
