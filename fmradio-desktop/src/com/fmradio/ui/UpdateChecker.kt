package com.fmradio.ui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * Checks for FM Radio Desktop updates by fetching a small JSON manifest from GitHub.
 * Expected format: {"versionCode": 7, "versionName": "1.7", "windowsDownloadUrl": "...", "releaseNotes": "..."}
 */
object UpdateChecker {

    const val CHECK_URL =
        "https://raw.githubusercontent.com/donmax76/IpTv/main/fmradio-version.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    /** Blocking network call — always run from a background thread. */
    fun check(url: String = CHECK_URL): Result<UpdateInfo?> {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return Result.failure(Exception("HTTP $code"))
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val versionCode = json.optInt("versionCode", 0)
            val versionName = json.optString("versionName", "")
            val downloadUrl = json.optString("windowsDownloadUrl", "")
            val releaseNotes = json.optString("releaseNotes", "")
            if (versionCode <= 0 || downloadUrl.isBlank()) {
                return Result.failure(Exception("Invalid version manifest"))
            }
            Result.success(UpdateInfo(versionCode, versionName, downloadUrl, releaseNotes))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
