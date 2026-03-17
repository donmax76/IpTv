package com.fmradio.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class StationStorage(context: Context) {

    companion object {
        private const val PREFS_NAME = "fm_radio_stations"
        private const val KEY_STATIONS = "saved_stations"
        private const val KEY_LAST_FREQUENCY = "last_frequency"
        private const val KEY_LAST_VOLUME = "last_volume"
        private const val KEY_PRESET_PREFIX = "preset_"
        private const val KEY_BASS = "eq_bass"
        private const val KEY_TREBLE = "eq_treble"
        private const val KEY_AF_ENABLED = "af_enabled"
        private const val KEY_TA_ENABLED = "ta_enabled"
        private const val KEY_BAND = "current_band"
        const val PRESET_COUNT = 6
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveStations(stations: List<RadioStation>) {
        val arr = JSONArray()
        for (s in stations) {
            arr.put(JSONObject().apply {
                put("frequencyHz", s.frequencyHz)
                put("name", s.name)
                put("isFavorite", s.isFavorite)
                put("signalStrength", s.signalStrength.toDouble())
                put("addedTimestamp", s.addedTimestamp)
                put("rdsPs", s.rdsPs)
                put("rdsRt", s.rdsRt)
                put("rdsPty", s.rdsPty)
            })
        }
        prefs.edit().putString(KEY_STATIONS, arr.toString()).apply()
    }

    fun loadStations(): List<RadioStation> {
        val json = prefs.getString(KEY_STATIONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RadioStation(
                    frequencyHz = obj.getLong("frequencyHz"),
                    name = obj.optString("name", ""),
                    isFavorite = obj.optBoolean("isFavorite", false),
                    signalStrength = obj.optDouble("signalStrength", 0.0).toFloat(),
                    addedTimestamp = obj.optLong("addedTimestamp", System.currentTimeMillis()),
                    rdsPs = obj.optString("rdsPs", ""),
                    rdsRt = obj.optString("rdsRt", ""),
                    rdsPty = obj.optString("rdsPty", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addStation(station: RadioStation) {
        val stations = loadStations().toMutableList()
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
        get() = prefs.getLong(KEY_LAST_FREQUENCY, 100000000L)
        set(value) = prefs.edit().putLong(KEY_LAST_FREQUENCY, value).apply()

    var lastVolume: Float
        get() = prefs.getFloat(KEY_LAST_VOLUME, 0.8f)
        set(value) = prefs.edit().putFloat(KEY_LAST_VOLUME, value).apply()

    fun getPreset(index: Int): Long {
        return prefs.getLong("${KEY_PRESET_PREFIX}$index", 0L)
    }

    fun setPreset(index: Int, frequencyHz: Long) {
        prefs.edit().putLong("${KEY_PRESET_PREFIX}$index", frequencyHz).apply()
    }

    var bassLevel: Int
        get() = prefs.getInt(KEY_BASS, 10)
        set(value) = prefs.edit().putInt(KEY_BASS, value).apply()

    var trebleLevel: Int
        get() = prefs.getInt(KEY_TREBLE, 10)
        set(value) = prefs.edit().putInt(KEY_TREBLE, value).apply()

    var afEnabled: Boolean
        get() = prefs.getBoolean(KEY_AF_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AF_ENABLED, value).apply()

    var taEnabled: Boolean
        get() = prefs.getBoolean(KEY_TA_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TA_ENABLED, value).apply()

    var currentBandName: String
        get() = prefs.getString(KEY_BAND, "FM_BROADCAST") ?: "FM_BROADCAST"
        set(value) = prefs.edit().putString(KEY_BAND, value).apply()
}
