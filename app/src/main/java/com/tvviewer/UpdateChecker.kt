package com.tvviewer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks for app updates by fetching a JSON from URL.
 * Expected format: {"versionCode": 17, "versionName": "4.1", "downloadUrl": "https://..."}
 */
object UpdateChecker {

    private const val TAG = "TVViewer"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(val versionCode: Int, val versionName: String, val downloadUrl: String)

    suspend fun check(url: String?): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext Result.success(null)
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            val json = JSONObject(body)
            val versionCode = json.optInt("versionCode", 0)
            val versionName = json.optString("versionName", "")
            val downloadUrl = json.optString("downloadUrl", "")
            if (versionCode <= 0 || downloadUrl.isBlank()) {
                return@withContext Result.failure(Exception("Invalid version.json"))
            }
            Result.success(UpdateInfo(versionCode, versionName, downloadUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            Result.failure(e)
        }
    }
}
