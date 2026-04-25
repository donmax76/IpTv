package com.tvviewer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object PlaylistRepository {

    private const val TAG = "TVViewer"

    data class PlaylistResult(val channels: List<Channel>, val epgUrl: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Fetch a playlist — either from a remote http(s) URL or a local file:// URI
     * (used when the user imports an .m3u/.m3u8 file via OpenDocument).
     */
    suspend fun fetchPlaylist(url: String, context: Context? = null): PlaylistResult = withContext(Dispatchers.IO) {
        val userAgent = context?.let { AppPreferences(it).userAgent } ?: AppPreferences.DEFAULT_USER_AGENT
        try {
            if (url.startsWith("file://")) {
                val path = url.removePrefix("file://")
                val file = File(path)
                if (!file.exists()) {
                    Log.e(TAG, "Local playlist file missing: $path")
                    return@withContext PlaylistResult(emptyList(), null)
                }
                val body = file.readText()
                val baseUrl = "file://${file.parentFile?.absolutePath ?: ""}/"
                val result = M3UParser.parseWithEpg(body, baseUrl)
                return@withContext PlaylistResult(result.channels, result.epgUrl)
            }

            Log.d(TAG, "Fetching playlist: $url")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
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
