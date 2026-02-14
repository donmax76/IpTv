package com.tvviewer

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.nio.charset.Charset

object M3UParser {

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
                        channels.add(
                            Channel(
                                name = extInf.name,
                                url = url,
                                logoUrl = extInf.logo,
                                group = extInf.group
                            )
                        )
                    }
                }
            }
            i++
        }
        return channels
    }

    private data class ExtInf(
        val name: String,
        val logo: String?,
        val group: String?
    )

    private fun parseExtInf(line: String): ExtInf {
        var name = "Unknown"
        var logo: String? = null
        var group: String? = null

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

        val tvgNameRegex = """tvg-name="([^"]*)"""".toRegex()
        tvgNameRegex.find(attrs)?.groupValues?.get(1)?.let {
            if (name == "Unknown") name = it
        }

        return ExtInf(name = name, logo = logo, group = group)
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
