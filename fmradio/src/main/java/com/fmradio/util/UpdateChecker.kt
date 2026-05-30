package com.fmradio.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val VERSION_URL = "https://raw.githubusercontent.com/donmax76/IpTv/claude/rebuild-apk-sync-audio-qLxeF/fmradio/version.json"

    data class UpdateInfo(val versionCode: Int, val versionName: String, val downloadUrl: String)

    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            // Cache-bust: GitHub CDN caches raw.githubusercontent.com for ~5 min
            val cacheBust = System.currentTimeMillis() / 60000  // changes every minute
            val url = URL("$VERSION_URL?cb=$cacheBust")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.useCaches = false

            val code = conn.responseCode
            if (code != 200) {
                Log.e(TAG, "Update check failed: HTTP $code for $VERSION_URL")
                com.fmradio.dsp.DebugLog.log(TAG, "Update check: HTTP $code")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val versionCode = json.optInt("versionCode", 0)
            val versionName = json.optString("versionName", "")
            val downloadUrl = json.optString("downloadUrl", "")

            Log.i(TAG, "Update check: remote=$versionCode local=$currentVersionCode")
            com.fmradio.dsp.DebugLog.log(TAG, "Update: remote=$versionCode local=$currentVersionCode url=$downloadUrl")

            if (versionCode <= 0 || downloadUrl.isBlank()) {
                Log.e(TAG, "Invalid version.json: $body")
                return@withContext null
            }

            if (versionCode > currentVersionCode) {
                UpdateInfo(versionCode, versionName, downloadUrl)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            com.fmradio.dsp.DebugLog.log(TAG, "Update check error: ${e.message}")
            null
        }
    }
}
