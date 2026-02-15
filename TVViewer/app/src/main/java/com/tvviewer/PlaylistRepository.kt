package com.tvviewer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object PlaylistRepository {

    private const val TAG = "TVViewer"

    data class PlaylistResult(val channels: List<Channel>, val epgUrl: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchPlaylist(url: String): PlaylistResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching playlist: $url")
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            Log.d(TAG, "Response: ${response.code} ${response.message}")
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP error: ${response.code}")
                return@withContext PlaylistResult(emptyList(), null)
            }
            val body = response.body?.string() ?: run {
                Log.e(TAG, "Empty response body")
                return@withContext PlaylistResult(emptyList(), null)
            }
            Log.d(TAG, "Response size: ${body.length} bytes")
            val baseUrl = url.substringBeforeLast("/") + "/"
            val result = M3UParser.parseWithEpg(body, baseUrl)
            PlaylistResult(result.channels, result.epgUrl)
        } catch (e: Exception) {
            Log.e(TAG, "fetchPlaylist error", e)
            throw e
        }
    }

    fun parseLocal(content: String): PlaylistResult {
        val result = M3UParser.parseWithEpg(content)
        return PlaylistResult(result.channels, result.epgUrl)
    }
}
