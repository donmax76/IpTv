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

    private val client: OkHttpClient = run {
        // IPTV-плейлисты часто на CDN с самоподписанными / несовпадающими
        // сертами (типа streamlock.net с DN=*.maksnet.tv). Ослабляем.
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<java.security.cert.X509Certificate>,
                authType: String
            ) {}
            override fun checkServerTrusted(
                chain: Array<java.security.cert.X509Certificate>,
                authType: String
            ) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                emptyArray()
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(trust), java.security.SecureRandom())
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .sslSocketFactory(ctx.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

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

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .build()
            val response = client.newCall(request).execute()
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
            val baseUrl = url.substringBeforeLast("/") + "/"
            val result = M3UParser.parseWithEpg(body, baseUrl)
            // Diagnostic: if a remote playlist parsed to zero channels,
            // ship the first 1.5 KB of raw bytes (UTF-8 + CP1251 + hex
            // preview) up to the developer so we can see what format the
            // server actually returned. Saves the user having to dig out
            // and paste the raw .m3u.
            if (result.channels.isEmpty() && context != null) {
                runCatching { sendEmptyParseDiagnostic(context, url, bytes) }
            }
            PlaylistResult(result.channels, result.epgUrl)
        } catch (e: Exception) {
            Log.e(TAG, "fetchPlaylist error", e)
            throw e
        }
    }

    /** Posts the first ~1500 bytes of an unparsable playlist response to
     *  the dev's report channel so we can see exactly what the server
     *  returned (raw hex + utf-8 + cp1251 decode previews). Once is
     *  enough — rate-limited via GitHubReporter's silent dedup. */
    private fun sendEmptyParseDiagnostic(context: Context, url: String, bytes: ByteArray) {
        val n = bytes.size.coerceAtMost(1500)
        val sample = bytes.copyOf(n)
        val hex = sample.joinToString("") { "%02x".format(it) }
        val utf8 = String(sample, Charsets.UTF_8)
        val cp1251 = try {
            String(sample, java.nio.charset.Charset.forName("windows-1251"))
        } catch (_: Exception) { "(cp1251 decode failed)" }
        val title = "[Empty parse] $url"
        val body = buildString {
            append(GitHubReporter.systemInfo()).append("\n")
            append("URL: $url\n")
            append("Bytes received: ${bytes.size}\n\n")
            append("**UTF-8 sample**:\n```\n").append(utf8).append("\n```\n\n")
            append("**CP1251 sample**:\n```\n").append(cp1251).append("\n```\n\n")
            append("**Hex (first $n bytes)**:\n```\n").append(hex).append("\n```\n")
        }
        GitHubReporter.report(context, title, body, silent = true)
    }

    private fun decodePlaylistBytes(bytes: ByteArray): String {
        // Try UTF-8 first, then Windows-1251 (Russian / Bulgarian /
        // Ukrainian / Belarusian / etc. — the dominant Cyrillic 8-bit
        // codepage on legacy hosts), then KOI8-R as a last-resort. The
        // "best" decoding wins by replacement-char count.
        val candidates = listOf(
            "UTF-8",
            "windows-1251",
            "KOI8-R",
            "windows-1252",
        )
        var best: Pair<String, Int>? = null
        for (cs in candidates) {
            val decoded = try {
                String(bytes, java.nio.charset.Charset.forName(cs))
            } catch (_: Exception) { continue }
            val score = decoded.count { it == '�' }
            if (best == null || score < best.second) best = decoded to score
            if (score == 0) break
        }
        return best?.first ?: String(bytes, Charsets.UTF_8)
    }

    fun parseLocal(content: String): PlaylistResult {
        val result = M3UParser.parseWithEpg(content)
        return PlaylistResult(result.channels, result.epgUrl)
    }
}
