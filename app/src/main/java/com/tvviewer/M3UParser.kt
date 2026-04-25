package com.tvviewer

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.nio.charset.Charset

/**
 * Tolerant M3U / M3U8 parser. Handles every variant the project has
 * encountered in the wild:
 *
 *   • #EXTM3U with x-tvg-url / url-tvg / tvg-shift on the header
 *   • #EXTINF with attrs in any case (TVG-ID / tvg-id), with " or '
 *     quotes, with no quotes, in any order
 *   • Per-channel category as either group-title="…" inside EXTINF
 *     or as a separate #EXTGRP:… line between EXTINF and the URL
 *   • #KODIPROP / #EXTVLCOPT / #EXTBITRATE / etc. directives between
 *     EXTINF and the URL — silently skipped, not lost
 *   • Stream URLs of any scheme (http(s), rtmp(s), rtsp, udp, mms,
 *     acestream, magnet, file)
 *   • Simple format: one URL per non-comment line, no #EXTINF
 *   • UTF-8 / UTF-16 BOM stripping
 *   • #EXTM3U missing entirely
 */
object M3UParser {

    private const val TAG = "TVViewer"

    data class ParseResult(val channels: List<Channel>, val epgUrl: String?)

    fun parseWithEpg(content: String, baseUrl: String? = null): ParseResult {
        val cleaned = stripBom(content)
        // Extract EPG URL from the header — try every known attribute name.
        val epgUrl = listOf(
            """(?i)\bx-tvg-url\s*=\s*"([^"]+)"""".toRegex(),
            """(?i)\bx-tvg-url\s*=\s*'([^']+)'""".toRegex(),
            """(?i)\burl-tvg\s*=\s*"([^"]+)"""".toRegex(),
            """(?i)\burl-tvg\s*=\s*'([^']+)'""".toRegex(),
            """(?i)\btvg-url\s*=\s*"([^"]+)"""".toRegex(),
        ).firstNotNullOfOrNull { it.find(cleaned)?.groupValues?.get(1) }
        return ParseResult(parse(cleaned, baseUrl), epgUrl)
    }

    fun parse(content: String, baseUrl: String? = null): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = stripBom(content).lines()
        var i = 0

        Log.d(TAG, "Parsing M3U, ${lines.size} lines")
        val hasExtInf = lines.any { it.trim().startsWith("#EXTINF:", ignoreCase = true) }
        if (hasExtInf) {
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    val extInf = parseExtInf(line)
                    i++
                    // Capture #EXTGRP / #KODIPROP / #EXTVLCOPT… and skip
                    // through any other directive lines until we hit the
                    // actual stream URL.
                    var extGroup: String? = null
                    var extLogo: String? = null
                    while (i < lines.size && lines[i].trim().let { it.isEmpty() || it.startsWith("#") }) {
                        val d = lines[i].trim()
                        when {
                            d.startsWith("#EXTGRP:", true) ->
                                extGroup = d.substringAfter(':').trim().ifEmpty { null }
                            d.startsWith("#EXTLOGO:", true) ->
                                extLogo = d.substringAfter(':').trim().ifEmpty { null }
                        }
                        i++
                    }
                    if (i < lines.size) {
                        var url = lines[i].trim()
                        if (url.isNotEmpty() && isLikelyStreamUrl(url)) {
                            if (baseUrl != null && !url.matches(SCHEME_PREFIX_REGEX)) {
                                url = resolveUrl(baseUrl, url)
                            }
                            var logoUrl = extInf.logo ?: extLogo
                            if (logoUrl != null && baseUrl != null && !logoUrl.startsWith("http")) {
                                logoUrl = resolveUrl(baseUrl, logoUrl)
                            }
                            val name = extInf.name.ifBlank { deriveNameFromUrl(url) ?: "Channel ${channels.size + 1}" }
                            channels.add(
                                Channel(
                                    name = name,
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
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                var url = line
                if (baseUrl != null && !url.matches(SCHEME_PREFIX_REGEX)) {
                    url = resolveUrl(baseUrl, url)
                }
                if (!isLikelyStreamUrl(url)) continue
                val derived = deriveNameFromUrl(url) ?: "Channel ${channels.size + 1}"
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

    /** Match attr names case-insensitively, accept double / single / un-quoted values. */
    private fun attr(line: String, name: String): String? {
        val patterns = listOf(
            """(?i)\b$name\s*=\s*"([^"]*)"""".toRegex(),
            """(?i)\b$name\s*=\s*'([^']*)'""".toRegex(),
            """(?i)\b$name\s*=\s*(\S+)""".toRegex(),
        )
        for (p in patterns) {
            val v = p.find(line)?.groupValues?.get(1)
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    private fun parseExtInf(line: String): ExtInf {
        // Channel name is whatever follows the LAST comma (so commas
        // inside attribute values don't split it).
        val commaIndex = line.lastIndexOf(',')
        val (attrsPart, namePart) = if (commaIndex >= 0) {
            line.substring(0, commaIndex) to line.substring(commaIndex + 1).trim()
        } else {
            line to ""
        }

        val name = namePart
            .ifEmpty { attr(attrsPart, "tvg-name") }
            ?: ""
        val logo = attr(attrsPart, "tvg-logo")
        val group = attr(attrsPart, "group-title") ?: attr(attrsPart, "group")
        val tvgId = attr(attrsPart, "tvg-id") ?: attr(attrsPart, "channel-id")

        return ExtInf(name = name, logo = logo, group = group, tvgId = tvgId)
    }

    /** Stream-URL schemes the player can attempt. Anything else (e.g. a
     *  comment, malformed URL, raw XML) is skipped instead of being
     *  treated as a channel. */
    private val SCHEME_PREFIX_REGEX =
        Regex("""^(?i)(https?|rtmp|rtmps|rtsp|rtsps|udp|mms|file|acestream|magnet)://.*""")

    private fun isLikelyStreamUrl(url: String): Boolean =
        SCHEME_PREFIX_REGEX.matches(url) || url.startsWith("acestream:", true) ||
            url.startsWith("magnet:", true)

    private fun deriveNameFromUrl(url: String): String? {
        val pathName = Regex("""/([^/?#]+?)(?:\.[^./]+)?(?:[?#].*)?$""").find(url)
            ?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
        return pathName ?: try {
            java.net.URI(url).host?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /** Strip UTF-8 / UTF-16 byte-order marks. */
    private fun stripBom(s: String): String {
        if (s.isEmpty()) return s
        return when {
            s.startsWith("﻿") -> s.substring(1)        // UTF-8/16 BOM
            s.startsWith("￾") -> s.substring(1)        // UTF-16 reversed
            else -> s
        }
    }

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
