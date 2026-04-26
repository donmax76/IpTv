package com.fmradio.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val VERSION_URL = "https://raw.githubusercontent.com/donmax76/IpTv/main/fmradio/version.json"

    data class UpdateInfo(val versionCode: Int, val versionName: String, val downloadUrl: String)

    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(VERSION_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) {
                Log.e(TAG, "Update check failed: HTTP ${conn.responseCode}")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val versionCode = json.optInt("versionCode", 0)
            val versionName = json.optString("versionName", "")
            val downloadUrl = json.optString("downloadUrl", "")

            if (versionCode <= 0 || downloadUrl.isBlank()) {
                Log.e(TAG, "Invalid version.json")
                return@withContext null
            }

            if (versionCode > currentVersionCode) {
                UpdateInfo(versionCode, versionName, downloadUrl)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            null
        }
    }
}
