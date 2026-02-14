package com.tvviewer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

object PlaylistRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchPlaylist(url: String): List<Channel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            return@withContext emptyList<Channel>()
        }
        val body = response.body?.string() ?: return@withContext emptyList()
        val baseUrl = url.substringBeforeLast("/") + "/"
        M3UParser.parse(body, baseUrl)
    }

    fun parseLocal(content: String): List<Channel> {
        return M3UParser.parse(content)
    }
}
