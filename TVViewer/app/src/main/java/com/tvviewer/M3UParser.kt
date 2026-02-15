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
        val lines = content.lines()
        var i = 0

        Log.d(TAG, "Parsing M3U, ${lines.size} lines")
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val extInf = parseExtInf(line)
                i++
                if (i < lines.size) {
                    var url = lines[i].trim()
                    if (url.isNotEmpty() && !url.startsWith("#")) {
                        if (baseUrl != null && !url.startsWith("http")) {
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
                                logoUrl = logoUrl,
                                group = extInf.group,
                                tvgId = extInf.tvgId
                            )
                        )
                    }
                }
            }
            i++
        }
        Log.d(TAG, "Parsed ${channels.size} channels")
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
