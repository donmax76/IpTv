package com.fmradio.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
        private const val KEY_PRESETS_LIST = "presets_list"
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
        autoBackup()
    }

    fun removeStation(frequencyHz: Long) {
        val stations = loadStations().toMutableList()
        stations.removeAll { it.frequencyHz == frequencyHz }
        saveStations(stations)
        autoBackup()
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

    // ---- Expandable presets list (unlimited) ----

    fun loadPresets(): MutableList<PresetItem> {
        val json = prefs.getString(KEY_PRESETS_LIST, null)
        if (json != null) {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    PresetItem(
                        frequencyHz = obj.getLong("frequencyHz"),
                        name = obj.optString("name", "")
                    )
                }.toMutableList()
            } catch (_: Exception) { migrateOldPresets() }
        }
        return migrateOldPresets()
    }

    fun savePresets(presets: List<PresetItem>) {
        val arr = JSONArray()
        for (p in presets) {
            arr.put(JSONObject().apply {
                put("frequencyHz", p.frequencyHz)
                put("name", p.name)
            })
        }
        prefs.edit().putString(KEY_PRESETS_LIST, arr.toString()).apply()
    }

    fun addPresetItem(frequencyHz: Long, name: String = "") {
        val presets = loadPresets()
        presets.removeAll { it.frequencyHz == frequencyHz }
        presets.add(PresetItem(frequencyHz, name))
        savePresets(presets)
    }

    fun removePresetItem(frequencyHz: Long) {
        val presets = loadPresets()
        presets.removeAll { it.frequencyHz == frequencyHz }
        savePresets(presets)
    }

    fun renamePresetItem(frequencyHz: Long, newName: String) {
        val presets = loadPresets()
        val idx = presets.indexOfFirst { it.frequencyHz == frequencyHz }
        if (idx >= 0) {
            presets[idx] = presets[idx].copy(name = newName)
            savePresets(presets)
        }
    }

    // ========== Export / Import station list ==========

    private val ctx = context.applicationContext

    /**
     * Export stations to a JSON file in the app's external files directory.
     * Returns the file, or null on failure.
     */
    fun exportToFile(): File? {
        return try {
            val stations = loadStations()
            val presets = loadPresets()
            val root = JSONObject()
            val stArr = JSONArray()
            for (s in stations) {
                stArr.put(JSONObject().apply {
                    put("frequencyHz", s.frequencyHz)
                    put("name", s.name)
                    put("isFavorite", s.isFavorite)
                    put("rdsPs", s.rdsPs)
                    put("rdsRt", s.rdsRt)
                    put("rdsPty", s.rdsPty)
                })
            }
            root.put("stations", stArr)

            val prArr = JSONArray()
            for (p in presets) {
                prArr.put(JSONObject().apply {
                    put("frequencyHz", p.frequencyHz)
                    put("name", p.name)
                })
            }
            root.put("presets", prArr)
            root.put("lastFrequency", lastFrequency)
            root.put("band", currentBandName)
            root.put("bass", bassLevel)
            root.put("treble", trebleLevel)

            val dir = File(ctx.getExternalFilesDir(null), "backup")
            dir.mkdirs()
            val file = File(dir, "fm_stations.json")
            file.writeText(root.toString(2))
            Log.i("StationStorage", "Exported ${stations.size} stations to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e("StationStorage", "Export failed", e)
            null
        }
    }

    /**
     * Import stations from a JSON file. Merges with existing stations
     * (duplicates within 50 kHz are replaced by imported version).
     * Returns number of stations imported, or -1 on failure.
     */
    fun importFromFile(file: File): Int {
        return try {
            val json = file.readText()
            val root = JSONObject(json)

            val stArr = root.optJSONArray("stations")
            if (stArr != null) {
                val imported = (0 until stArr.length()).map { i ->
                    val obj = stArr.getJSONObject(i)
                    RadioStation(
                        frequencyHz = obj.getLong("frequencyHz"),
                        name = obj.optString("name", ""),
                        isFavorite = obj.optBoolean("isFavorite", false),
                        rdsPs = obj.optString("rdsPs", ""),
                        rdsRt = obj.optString("rdsRt", ""),
                        rdsPty = obj.optString("rdsPty", "")
                    )
                }
                // Merge: imported stations override existing at same frequency
                val existing = loadStations().toMutableList()
                for (s in imported) {
                    existing.removeAll { Math.abs(it.frequencyHz - s.frequencyHz) < 50000 }
                    existing.add(s)
                }
                existing.sortBy { it.frequencyHz }
                saveStations(existing)
            }

            val prArr = root.optJSONArray("presets")
            if (prArr != null) {
                val imported = (0 until prArr.length()).map { i ->
                    val obj = prArr.getJSONObject(i)
                    PresetItem(
                        frequencyHz = obj.getLong("frequencyHz"),
                        name = obj.optString("name", "")
                    )
                }
                savePresets(imported.toMutableList())
            }

            if (root.has("lastFrequency")) lastFrequency = root.getLong("lastFrequency")
            if (root.has("band")) currentBandName = root.getString("band")
            if (root.has("bass")) bassLevel = root.getInt("bass")
            if (root.has("treble")) trebleLevel = root.getInt("treble")

            val count = stArr?.length() ?: 0
            Log.i("StationStorage", "Imported $count stations from ${file.absolutePath}")
            count
        } catch (e: Exception) {
            Log.e("StationStorage", "Import failed", e)
            -1
        }
    }

    /**
     * Import from default backup location (app external files dir).
     */
    fun importFromBackup(): Int {
        val file = File(ctx.getExternalFilesDir(null), "backup/fm_stations.json")
        if (!file.exists()) return 0
        return importFromFile(file)
    }

    /**
     * Export stations to Downloads folder (user-accessible).
     * Returns the saved file path, or null on failure.
     */
    fun exportToDownloads(): File? {
        val src = exportToFile() ?: return null
        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            val dst = File(downloads, "fm_stations.json")
            src.copyTo(dst, overwrite = true)
            Log.i("StationStorage", "Exported to ${dst.absolutePath}")
            dst
        } catch (e: Exception) {
            Log.e("StationStorage", "Export to Downloads failed", e)
            null
        }
    }

    /**
     * Import stations from Downloads folder.
     * Returns number of stations imported, or -1 on failure.
     */
    fun importFromDownloads(): Int {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloads, "fm_stations.json")
        if (!file.exists()) return 0
        return importFromFile(file)
    }

    /**
     * Create a share Intent for the exported station file.
     */
    fun getExportShareIntent(): Intent? {
        val file = exportToFile() ?: return null
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (_: Exception) {
            android.net.Uri.fromFile(file)
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "FM Radio Stations")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Auto-backup: save to external file after each station change.
     * Call this from addStation/removeStation/updateStation.
     */
    fun autoBackup() {
        try { exportToFile() } catch (_: Exception) {}
    }

    private fun migrateOldPresets(): MutableList<PresetItem> {
        val list = mutableListOf<PresetItem>()
        for (i in 0 until PRESET_COUNT) {
            val freq = getPreset(i)
            if (freq > 0) list.add(PresetItem(freq))
        }
        if (list.isNotEmpty()) savePresets(list)
        return list
    }
}
