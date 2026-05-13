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
    /** Round 215: GitHub API сортирует /releases по created_at DESC, но
     *  у нашего репо все теги имеют одну и ту же created_at дату (дата
     *  создания репо). При совпадающих created_at API возвращает
     *  СТАРЕЙШИЕ первыми (по id ASC), и первая страница содержит билды
     *  86-185 примерно — наши свежие 280+ не находились, авто-проверка
     *  видела «На сервере: build 95».
     *  Решение: пагинация. Перебираем до 20 страниц по 100 релизов
     *  (2000 макс), фильтруем v*-buildN, берём максимальный билд. */
    private const val GITHUB_API_BASE = "https://api.github.com/repos/donmax76/IpTv/releases"
    private const val MAX_PAGES = 20
    private const val PER_PAGE = 100
    private val OUR_TAG_REGEX = Regex("^v\\d+\\.\\d+-build\\d+$")

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
            fun safeStr(o: JSONObject, key: String): String =
                if (o.isNull(key)) "" else o.optString(key, "")

            // Round 215: пагинация. GitHub API возвращает наши релизы в
            // непредсказуемом порядке (все имеют одинаковый created_at),
            // поэтому первая страница не обязательно содержит самый
            // свежий билд. Перебираем до MAX_PAGES страниц.
            var bestRelease: JSONObject? = null
            var bestCode = 0
            var page = 1
            while (page <= MAX_PAGES) {
                val request = Request.Builder()
                    .url("$GITHUB_API_BASE?per_page=$PER_PAGE&page=$page")
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "TVViewer-App")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    if (page == 1) {
                        return Result.failure(Exception("GitHub API HTTP ${response.code}"))
                    }
                    break  // rate limit или нет данных — используем то что собрали
                }
                val body = response.body?.string() ?: break
                val arr = JSONArray(body)
                if (arr.length() == 0) break  // конец страниц

                for (i in 0 until arr.length()) {
                    val rel = arr.getJSONObject(i)
                    val tag = safeStr(rel, "tag_name")
                    if (!OUR_TAG_REGEX.matches(tag)) continue
                    val code = extractVersionCode(tag)
                    if (code > bestCode) {
                        bestCode = code
                        bestRelease = rel
                    }
                }

                if (arr.length() < PER_PAGE) break  // последняя страница
                page++
            }

            if (bestRelease == null || bestCode <= 0) {
                Log.d(TAG, "No matching v*-buildN release found (scanned $page pages)")
                return Result.success(null)
            }
            val json = bestRelease

            val tagName = safeStr(json, "tag_name") // e.g. "v5.2-build27"
            val releaseNotes = safeStr(json, "body")

            val versionCode = bestCode
            val versionName = extractVersionName(tagName)

            // Find APK download URL from assets
            val assets = json.optJSONArray("assets") ?: JSONArray()
            var downloadUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = safeStr(asset, "name")
                if (assetName.endsWith(".apk")) {
                    downloadUrl = safeStr(asset, "browser_download_url")
                    break
                }
            }

            if (downloadUrl.isBlank()) {
                Log.d(TAG, "Release $tagName has no APK asset yet")
                return Result.success(null)
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
