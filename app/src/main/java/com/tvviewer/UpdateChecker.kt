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
    // Round 228a: 5 страниц по 100 = до 500 релизов. Хватит на все
    // build* теги репо. Запрашиваем ПАРАЛЛЕЛЬНО (см. checkGitHubReleases),
    // поэтому общее время — почти как один HTTP-запрос (~200-500 мс).
    private const val MAX_PAGES = 5
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

            // Round 228a: страницы запрашиваются ПАРАЛЛЕЛЬНО — общее
            // время = время одного HTTP-запроса, а не суммы. Раньше
            // (Round 215) перебирали страницы последовательно, на сети
            // это занимало 1-2 сек.
            val bodies = java.util.concurrent.ConcurrentHashMap<Int, String>()
            val firstPageError = java.util.concurrent.atomic.AtomicReference<Exception?>()
            val latch = java.util.concurrent.CountDownLatch(MAX_PAGES)
            for (page in 1..MAX_PAGES) {
                val request = Request.Builder()
                    .url("$GITHUB_API_BASE?per_page=$PER_PAGE&page=$page")
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "TVViewer-App")
                    .build()
                client.newCall(request).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (page == 1) firstPageError.compareAndSet(null, e)
                        latch.countDown()
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        try {
                            if (response.isSuccessful) {
                                val b = response.body?.string()
                                if (b != null) bodies[page] = b
                            } else if (page == 1) {
                                firstPageError.compareAndSet(null,
                                    Exception("GitHub API HTTP ${response.code}"))
                            }
                        } finally {
                            response.close()
                            latch.countDown()
                        }
                    }
                })
            }
            // Лимитируем общее ожидание чтобы splash не висел дольше
            // своего CHECK_TIMEOUT_MS (3 сек). withTimeoutOrNull
            // снаружи (в SplashActivity) обрежет ещё раз для подстраховки.
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)

            val firstErr = firstPageError.get()
            if (firstErr != null && bodies.isEmpty()) {
                return Result.failure(firstErr)
            }

            var bestRelease: JSONObject? = null
            var bestCode = 0
            for ((_, body) in bodies) {
                val arr = try { JSONArray(body) } catch (_: Exception) { continue }
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
            }

            if (bestRelease == null || bestCode <= 0) {
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
