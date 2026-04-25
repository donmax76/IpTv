package com.tvviewer

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.nio.charset.Charset

object M3UParser {

    private const val TAG = "TVViewer"

    data class ParseResult(val channels: List<Channel>, val epgUrl: String?)

    /**
     * Parse M3U playlist from string content, including EPG URL from header.
     */
    fun parseWithEpg(content: String, baseUrl: String? = null): ParseResult {
        val epgUrl = Regex("""x-tvg-url="([^"]+)"""").find(content)?.groupValues?.get(1)
        return ParseResult(parse(content, baseUrl), epgUrl)
    }

    /**
     * Parse M3U playlist from string content.
     * Format:
     * #EXTM3U
     * #EXTINF:-1 tvg-id="..." tvg-name="..." tvg-logo="..." group-title="...",Channel Name
     * http://stream.url
     */
    fun parse(content: String, baseUrl: String? = null): List<Channel> {
        val channels = mutableListOf<Channel>()
        // Strip a UTF-8 BOM if present.
        val cleaned = content.removePrefix("﻿")
        val lines = cleaned.lines()
        var i = 0

        Log.d(TAG, "Parsing M3U, ${lines.size} lines")
        // Detect "extended" M3U (has #EXTINF). If we see any, treat the file
        // as extended; otherwise fall back to "simple" (one URL per line)
        // mode at the bottom of this function.
        val hasExtInf = lines.any { it.trim().startsWith("#EXTINF:") }
        if (hasExtInf) {
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXTINF:")) {
                    val extInf = parseExtInf(line)
                    i++
                    // Some playlists carry the category on a separate
                    // line (e.g. zedomS-style: '#EXTGRP:новости' between
                    // #EXTINF and the URL). Capture that and use it when
                    // EXTINF itself didn't have group-title=…
                    var extGroup: String? = null
                    while (i < lines.size && lines[i].trim().startsWith("#")) {
                        val d = lines[i].trim()
                        if (d.startsWith("#EXTGRP:", ignoreCase = true)) {
                            extGroup = d.substringAfter(':').trim().ifEmpty { null }
                        }
                        i++
                    }
                    if (i < lines.size) {
                        var url = lines[i].trim()
                        if (url.isNotEmpty()) {
                            if (baseUrl != null && !url.startsWith("http") && !url.startsWith("rtmp") && !url.startsWith("rtsp")) {
                                url = resolveUrl(baseUrl, url)
                            }
                            var logoUrl = extInf.logo
                            if (logoUrl != null && baseUrl != null && !logoUrl.startsWith("http")) {
                                logoUrl = resolveUrl(baseUrl, logoUrl)
                            }
                            channels.add(
                                Channel(
                                    name = extInf.name,
                                    url = url,
                                    logoUrl = logoUrl ?: faviconFor(url),
                                    group = extInf.group ?: extGroup,
                                    tvgId = extInf.tvgId
                                )
                            )
                        }
                    }
                }
                i++
            }
        } else {
            // Simple M3U: every non-comment / non-empty line is a stream URL.
            // The playlist host (e.g. flyvideo.ucoz.ru/zedomS.m3u) often uses
            // this minimal format. Derive a name from the URL itself.
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                var url = line
                if (baseUrl != null && !url.startsWith("http") && !url.startsWith("rtmp") && !url.startsWith("rtsp")) {
                    url = resolveUrl(baseUrl, url)
                }
                if (!(url.startsWith("http") || url.startsWith("rtmp") || url.startsWith("rtsp") || url.startsWith("file"))) {
                    continue
                }
                val derived = Regex("""/([^/?#]+?)(?:\.[^./]+)?(?:[?#].*)?$""").find(url)
                    ?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                    ?: "Channel ${channels.size + 1}"
                channels.add(Channel(
                    name = derived,
                    url = url,
                    logoUrl = faviconFor(url),
                    group = null,
                    tvgId = null,
                ))
            }
        }
        Log.d(TAG, "Parsed ${channels.size} channels (extended=$hasExtInf)")
        return channels
    }

    private data class ExtInf(
        val name: String,
        val logo: String?,
        val group: String?,
        val tvgId: String?
    )

    private fun parseExtInf(line: String): ExtInf {
        var name = "Unknown"
        var logo: String? = null
        var group: String? = null
        var tvgId: String? = null

        val commaIndex = line.indexOf(',')
        val attrs = if (commaIndex >= 0) {
            name = line.substring(commaIndex + 1).trim()
            line.substring(0, commaIndex)
        } else {
            line
        }

        val tvgLogoRegex = """tvg-logo="([^"]*)"""".toRegex()
        tvgLogoRegex.find(attrs)?.groupValues?.get(1)?.let { logo = it.ifEmpty { null } }

        val groupRegex = """group-title="([^"]*)"""".toRegex()
        groupRegex.find(attrs)?.groupValues?.get(1)?.let { group = it.ifEmpty { null } }

        val tvgIdRegex = """tvg-id="([^"]*)"""".toRegex()
        tvgIdRegex.find(attrs)?.groupValues?.get(1)?.let { tvgId = it.ifEmpty { null } }

        val tvgNameRegex = """tvg-name="([^"]*)"""".toRegex()
        tvgNameRegex.find(attrs)?.groupValues?.get(1)?.let {
            if (name == "Unknown") name = it
        }

        return ExtInf(name = name, logo = logo, group = group, tvgId = tvgId)
    }

    /**
     * Channels that didn't ship a tvg-logo in their #EXTINF still need
     * SOMETHING in the row, otherwise the user just sees the generic
     * placeholder for every entry. Use the streaming host's favicon as a
     * cheap fallback — Google's free S2 service returns a 128px PNG for
     * any domain, no auth.
     */
    private fun faviconFor(streamUrl: String): String? {
        return try {
            val host = java.net.URI(streamUrl).host?.takeIf { it.isNotEmpty() } ?: return null
            "https://www.google.com/s2/favicons?domain=$host&sz=128"
        } catch (_: Exception) { null }
    }

    private fun resolveUrl(baseUrl: String, relativePath: String): String {
        return try {
            URL(URL(baseUrl), relativePath).toString()
        } catch (e: Exception) {
            relativePath
        }
    }

    fun parseFromInputStream(inputStream: InputStream, baseUrl: String? = null): List<Channel> {
        val reader = BufferedReader(InputStreamReader(inputStream, Charset.defaultCharset()))
        val content = reader.readText()
        return parse(content, baseUrl)
    }
}
