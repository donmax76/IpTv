package com.tvviewer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object PlaylistRepository {

    private const val TAG = "TVViewer"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchPlaylist(url: String): List<Channel> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching playlist: $url")
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            Log.d(TAG, "Response: ${response.code} ${response.message}")
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP error: ${response.code}")
                return@withContext emptyList<Channel>()
            }
            val body = response.body?.string() ?: run {
                Log.e(TAG, "Empty response body")
                return@withContext emptyList()
            }
            Log.d(TAG, "Response size: ${body.length} bytes")
            val baseUrl = url.substringBeforeLast("/") + "/"
            M3UParser.parse(body, baseUrl)
        } catch (e: Exception) {
            Log.e(TAG, "fetchPlaylist error", e)
            throw e
        }
    }

    fun parseLocal(content: String): List<Channel> {
        return M3UParser.parse(content)
    }
}
