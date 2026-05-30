package com.fmradio.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val TAG = "UpdateChecker"

    // Use GitHub API instead of raw.githubusercontent.com — the API has proper
    // cache headers and doesn't serve stale content like the CDN does.
    private const val API_URL = "https://api.github.com/repos/donmax76/IpTv/contents/fmradio/version.json?ref=claude/rebuild-apk-sync-audio-qLxeF"

    data class UpdateInfo(val versionCode: Int, val versionName: String, val downloadUrl: String)

    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val versionJson = fetchVersionJson()
            if (versionJson == null) {
                com.fmradio.dsp.DebugLog.log(TAG, "Update: failed to fetch version.json")
                return@withContext null
            }

            val json = JSONObject(versionJson)
            val versionCode = json.optInt("versionCode", 0)
            val versionName = json.optString("versionName", "")
            val downloadUrl = json.optString("downloadUrl", "")

            Log.i(TAG, "Update check: remote=$versionCode local=$currentVersionCode")
            com.fmradio.dsp.DebugLog.log(TAG, "Update: remote=$versionCode local=$currentVersionCode")

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
            com.fmradio.dsp.DebugLog.log(TAG, "Update error: ${e.message}")
            null
        }
    }

    private fun fetchVersionJson(): String? {
        // Primary: GitHub API (returns JSON with base64-encoded content)
        try {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "FmRadio-Updater")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val apiJson = JSONObject(body)
                val content = apiJson.optString("content", "")
                    .replace("\n", "").replace("\r", "")
                if (content.isNotBlank()) {
                    val decoded = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
                    return String(decoded, Charsets.UTF_8).trim()
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "GitHub API failed, trying raw fallback: ${e.message}")
        }

        // Fallback: raw.githubusercontent.com with cache-bust
        try {
            val ts = System.currentTimeMillis()
            val url = "https://raw.githubusercontent.com/donmax76/IpTv/claude/rebuild-apk-sync-audio-qLxeF/fmradio/version.json?t=$ts"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Cache-Control", "no-cache, no-store")
                useCaches = false
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return body.trim()
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Raw fallback also failed: ${e.message}")
        }

        return null
    }
}
