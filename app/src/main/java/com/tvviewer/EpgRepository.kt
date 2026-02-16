package com.tvviewer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fetches and parses XMLTV EPG data.
 * Returns map: channelId (normalized) -> list of (start, end, title)
 */
object EpgRepository {

    private const val TAG = "TVViewer"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Programme(val start: Long, val end: Long, val title: String)

    suspend fun fetchEpg(epgUrl: String?): Map<String, List<Programme>> = withContext(Dispatchers.IO) {
        if (epgUrl.isNullOrBlank()) return@withContext emptyMap()
        try {
            val request = Request.Builder().url(epgUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyMap()
            val body = response.body?.string() ?: return@withContext emptyMap()
            parseXmltv(body)
        } catch (e: Exception) {
            Log.e(TAG, "EPG fetch error", e)
            emptyMap()
        }
    }

    private fun parseXmltv(xml: String): Map<String, List<Programme>> {
        val result = mutableMapOf<String, MutableList<Programme>>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var channelId: String? = null
        var start: Long = 0
        var end: Long = 0
        var title = ""
        var inProgramme = false
        var inTitle = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            inProgramme = true
                            channelId = parser.getAttributeValue(null, "channel")?.let { normalizeId(it) }
                            start = parseXmltvTime(parser.getAttributeValue(null, "start"))
                            end = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                            title = ""
                        }
                        "title" -> if (inProgramme) inTitle = true
                    }
                }
                XmlPullParser.TEXT -> if (inTitle) title = parser.text.trim()
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            if (channelId != null && title.isNotEmpty()) {
                                result.getOrPut(channelId) { mutableListOf() }
                                    .add(Programme(start, end, title))
                            }
                            inProgramme = false
                        }
                        "title" -> inTitle = false
                    }
                }
            }
            eventType = parser.next()
        }

        result.values.forEach { it.sortBy { p -> p.start } }
        return result
    }

    private fun normalizeId(id: String): String =
        id.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun parseXmltvTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        return try {
            val clean = s.take(14)
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(clean)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    fun getNowNext(epg: Map<String, List<Programme>>, tvgId: String?): Pair<String?, String?> {
        if (tvgId.isNullOrBlank()) return null to null
        val norm = normalizeId(tvgId)
        val programmes = epg[norm] ?: return null to null
        val now = System.currentTimeMillis()
        var nowTitle: String? = null
        var nextTitle: String? = null
        for (p in programmes) {
            when {
                now in p.start..p.end -> nowTitle = p.title
                now < p.start && nextTitle == null -> { nextTitle = p.title; break }
            }
        }
        return nowTitle to nextTitle
    }
}
