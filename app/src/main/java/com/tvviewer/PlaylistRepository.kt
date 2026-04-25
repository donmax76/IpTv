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
                .header("Accept", "*/*")
                .build()
            val response = client.newCall(request).execute()
            Log.d(TAG, "Response: ${response.code} ${response.message}")
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP error: ${response.code}")
                return@withContext PlaylistResult(emptyList(), null)
            }
            // Read raw bytes and try to recover the right encoding. Many
            // Russian-hosted .m3u files (ucoz.ru, etc.) are still served
            // as Windows-1251 without a charset header — UTF-8 decoding
            // turns Cyrillic names into mojibake but the structure parses.
            // Try UTF-8 first; if the content has lots of '?' or invalid
            // chars relative to the byte length, fall back to CP1251.
            val bytes = response.body?.bytes() ?: run {
                Log.e(TAG, "Empty response body")
                return@withContext PlaylistResult(emptyList(), null)
            }
            val body = decodePlaylistBytes(bytes)
            Log.d(TAG, "Response size: ${body.length} chars (raw ${bytes.size} bytes)")
            val baseUrl = url.substringBeforeLast("/") + "/"
            val result = M3UParser.parseWithEpg(body, baseUrl)
            PlaylistResult(result.channels, result.epgUrl)
        } catch (e: Exception) {
            Log.e(TAG, "fetchPlaylist error", e)
            throw e
        }
    }

    private fun decodePlaylistBytes(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        // Heuristic: too many U+FFFD replacement chars → likely wrong encoding.
        val replacements = utf8.count { it == '�' }
        if (replacements > 10) {
            try {
                return String(bytes, java.nio.charset.Charset.forName("windows-1251"))
            } catch (_: Exception) {}
        }
        return utf8
    }

    fun parseLocal(content: String): PlaylistResult {
        val result = M3UParser.parseWithEpg(content)
        return PlaylistResult(result.channels, result.epgUrl)
    }
}
