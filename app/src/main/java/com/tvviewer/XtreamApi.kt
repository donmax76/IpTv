package com.tvviewer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object XtreamApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class XtreamInfo(
        val serverUrl: String,
        val username: String,
        val password: String,
        val serverInfo: JSONObject? = null
    )

    /**
     * Authenticate and get server info
     */
    suspend fun authenticate(server: String, username: String, password: String): Result<XtreamInfo> =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = server.trimEnd('/')
                val url = "$baseUrl/player_api.php?username=$username&password=$password"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Server error: ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
                val json = JSONObject(body)

                val userInfo = json.optJSONObject("user_info")
                if (userInfo == null || userInfo.optString("auth", "0") != "1") {
                    return@withContext Result.failure(Exception("Authentication failed"))
                }

                Result.success(XtreamInfo(baseUrl, username, password, json.optJSONObject("server_info")))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Build M3U playlist URL from Xtream credentials
     */
    fun buildM3uUrl(server: String, username: String, password: String): String {
        val baseUrl = server.trimEnd('/')
        return "$baseUrl/get.php?username=$username&password=$password&type=m3u_plus&output=ts"
    }

    /**
     * Get live categories
     */
    suspend fun getLiveCategories(server: String, username: String, password: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = server.trimEnd('/')
                val url = "$baseUrl/player_api.php?username=$username&password=$password&action=get_live_categories"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val arr = JSONArray(body)
                (0 until arr.length()).map {
                    val obj = arr.getJSONObject(it)
                    obj.getString("category_id") to obj.getString("category_name")
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
}
