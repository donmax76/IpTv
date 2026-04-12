package com.tvviewer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks for app updates via GitHub Releases API or a custom version.json URL.
 */
object UpdateChecker {

    private const val TAG = "TVViewer"
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/donmax76/IpTv/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String = ""
    )

    /**
     * Check for updates. Tries GitHub Releases API first, then falls back to custom URL.
     */
    suspend fun check(customUrl: String? = null): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        // Try GitHub Releases API first
        try {
            val result = checkGitHubReleases()
            if (result.isSuccess && result.getOrNull() != null) {
                return@withContext result
            }
        } catch (e: Exception) {
            Log.d(TAG, "GitHub releases check failed, trying custom URL", e)
        }

        // Fall back to custom version.json URL
        if (!customUrl.isNullOrBlank()) {
            return@withContext checkCustomUrl(customUrl)
        }

        Result.success(null)
    }

    private fun checkGitHubReleases(): Result<UpdateInfo?> {
        try {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "TVViewer-App")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("GitHub API HTTP ${response.code}"))
            }

            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "") // e.g. "v5.2-build27"
            val releaseName = json.optString("name", "") // e.g. "TVViewer v5.2 (Build 27)"
            val releaseNotes = json.optString("body", "")

            // Parse version from tag: "v5.2-build27" -> versionCode from build number
            val versionCode = extractVersionCode(tagName)
            val versionName = extractVersionName(tagName)

            // Find APK download URL from assets
            val assets = json.optJSONArray("assets") ?: JSONArray()
            var downloadUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk")) {
                    downloadUrl = asset.optString("browser_download_url", "")
                    break
                }
            }

            if (downloadUrl.isBlank()) {
                // No APK in assets, use the HTML URL
                downloadUrl = json.optString("html_url", "")
            }

            if (versionCode <= 0 || downloadUrl.isBlank()) {
                return Result.failure(Exception("Invalid release data"))
            }

            return Result.success(UpdateInfo(versionCode, versionName, downloadUrl, releaseNotes))
        } catch (e: Exception) {
            Log.e(TAG, "GitHub releases check failed", e)
            return Result.failure(e)
        }
    }

    private fun checkCustomUrl(url: String): Result<UpdateInfo?> {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "TVViewer-App")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return Result.failure(Exception("HTTP ${response.code}"))
            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            val json = JSONObject(body)
            val versionCode = json.optInt("versionCode", 0)
            val versionName = json.optString("versionName", "")
            val downloadUrl = json.optString("downloadUrl", "")
            if (versionCode <= 0 || downloadUrl.isBlank()) {
                return Result.failure(Exception("Invalid version.json"))
            }
            Result.success(UpdateInfo(versionCode, versionName, downloadUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Custom URL check failed", e)
            return Result.failure(e)
        }
        return Result.success(null)
    }

    /**
     * Extract build number as version code from tag like "v5.2-build27" -> 27
     * Or from "v5.3" -> parse as 530
     */
    private fun extractVersionCode(tag: String): Int {
        // Try "v5.2-build27" format
        val buildMatch = Regex("build(\\d+)").find(tag)
        if (buildMatch != null) {
            return buildMatch.groupValues[1].toIntOrNull() ?: 0
        }

        // Try "v5.2" format -> 520
        val versionMatch = Regex("v?(\\d+)\\.(\\d+)").find(tag)
        if (versionMatch != null) {
            val major = versionMatch.groupValues[1].toIntOrNull() ?: 0
            val minor = versionMatch.groupValues[2].toIntOrNull() ?: 0
            return major * 100 + minor * 10
        }

        return 0
    }

    /**
     * Extract version name from tag like "v5.2-build27" -> "5.2"
     */
    private fun extractVersionName(tag: String): String {
        val match = Regex("v?(\\d+\\.\\d+)").find(tag)
        return match?.groupValues?.get(1) ?: tag.removePrefix("v")
    }
}
