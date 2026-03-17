package com.tvviewer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Fetches and parses XMLTV EPG data with disk caching.
 * Returns map: channelId (normalized) -> list of (start, end, title, description)
 */
object EpgRepository {

    private const val TAG = "TVViewer"
    private const val EPG_CACHE_FILE = "epg_cache.json"
    private const val EPG_CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L // 6 hours

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Programme(
        val start: Long,
        val end: Long,
        val title: String,
        val description: String = ""
    )

    /**
     * Fetch EPG from URL with disk caching.
     * First tries to download fresh data. If fails, returns cached data.
     */
    suspend fun fetchEpg(epgUrl: String?, context: Context? = null): Map<String, List<Programme>> = withContext(Dispatchers.IO) {
        if (epgUrl.isNullOrBlank()) {
            // Try to load from cache
            return@withContext loadFromCache(context) ?: emptyMap()
        }
        try {
            Log.d(TAG, "Fetching EPG from: $epgUrl")
            val request = Request.Builder()
                .url(epgUrl)
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "EPG HTTP error: ${response.code}")
                return@withContext loadFromCache(context) ?: emptyMap()
            }

            val body = response.body ?: run {
                return@withContext loadFromCache(context) ?: emptyMap()
            }

            // Handle gzip
            val bodyString = try {
                val contentEncoding = response.header("Content-Encoding")
                val contentType = response.header("Content-Type") ?: ""
                if (contentEncoding == "gzip" || epgUrl.endsWith(".gz") || contentType.contains("gzip")) {
                    val bytes = body.bytes()
                    try {
                        GZIPInputStream(bytes.inputStream()).bufferedReader().readText()
                    } catch (e: Exception) {
                        // Not actually gzipped
                        String(bytes)
                    }
                } else {
                    body.string()
                }
            } catch (e: Exception) {
                Log.e(TAG, "EPG body read error", e)
                return@withContext loadFromCache(context) ?: emptyMap()
            }

            Log.d(TAG, "EPG data size: ${bodyString.length} chars")
            val result = parseXmltv(bodyString)
            Log.d(TAG, "EPG parsed: ${result.size} channels with data")

            // Save to cache
            saveToCache(context, result)

            result
        } catch (e: Exception) {
            Log.e(TAG, "EPG fetch error", e)
            // Try to load from cache on error
            loadFromCache(context) ?: emptyMap()
        }
    }

    /**
     * Load cached EPG data from disk.
     */
    fun loadFromCache(context: Context?): Map<String, List<Programme>>? {
        if (context == null) return null
        try {
            val file = File(context.filesDir, EPG_CACHE_FILE)
            if (!file.exists()) return null

            // Check age
            val age = System.currentTimeMillis() - file.lastModified()
            if (age > EPG_CACHE_MAX_AGE_MS * 4) {
                // Too old (24h), delete
                file.delete()
                return null
            }

            val json = file.readText()
            return deserializeEpg(json)
        } catch (e: Exception) {
            Log.e(TAG, "EPG cache load error", e)
            return null
        }
    }

    /**
     * Save EPG data to disk cache.
     */
    private fun saveToCache(context: Context?, data: Map<String, List<Programme>>) {
        if (context == null) return
        try {
            val json = serializeEpg(data)
            val file = File(context.filesDir, EPG_CACHE_FILE)
            file.writeText(json)
            Log.d(TAG, "EPG cached to disk: ${file.length()} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "EPG cache save error", e)
        }
    }

    private fun serializeEpg(data: Map<String, List<Programme>>): String {
        val sb = StringBuilder()
        sb.append("{")
        var first = true
        for ((channelId, programmes) in data) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(escapeJson(channelId)).append("\":[")
            var pFirst = true
            for (p in programmes) {
                if (!pFirst) sb.append(",")
                pFirst = false
                sb.append("{\"s\":").append(p.start)
                sb.append(",\"e\":").append(p.end)
                sb.append(",\"t\":\"").append(escapeJson(p.title)).append("\"")
                if (p.description.isNotEmpty()) {
                    sb.append(",\"d\":\"").append(escapeJson(p.description)).append("\"")
                }
                sb.append("}")
            }
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun deserializeEpg(json: String): Map<String, List<Programme>> {
        val result = mutableMapOf<String, MutableList<Programme>>()
        try {
            val obj = org.json.JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val channelId = keys.next()
                val arr = obj.getJSONArray(channelId)
                val programmes = mutableListOf<Programme>()
                for (i in 0 until arr.length()) {
                    val pObj = arr.getJSONObject(i)
                    programmes.add(Programme(
                        start = pObj.getLong("s"),
                        end = pObj.getLong("e"),
                        title = pObj.getString("t"),
                        description = pObj.optString("d", "")
                    ))
                }
                result[channelId] = programmes
            }
        } catch (e: Exception) {
            Log.e(TAG, "EPG deserialize error", e)
        }
        return result
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
        var description = ""
        var inProgramme = false
        var inTitle = false
        var inDesc = false

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
                            description = ""
                        }
                        "title" -> if (inProgramme) inTitle = true
                        "desc" -> if (inProgramme) inDesc = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTitle) title = parser.text.trim()
                    if (inDesc) description = parser.text.trim()
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            if (channelId != null && title.isNotEmpty()) {
                                result.getOrPut(channelId) { mutableListOf() }
                                    .add(Programme(start, end, title, description))
                            }
                            inProgramme = false
                        }
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
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
            // Handle timezone offset in XMLTV format: 20240101120000 +0300
            val clean = s.replace(" ", "").take(14)
            val offsetStr = s.replace(Regex("[^+\\-0-9]"), "").let {
                if (it.length > 14) it.substring(14) else ""
            }
            val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            if (offsetStr.isNotEmpty()) {
                try {
                    val fullFormat = SimpleDateFormat("yyyyMMddHHmmssZ", Locale.US)
                    fullFormat.parse(clean + offsetStr)?.time ?: 0L
                } catch (_: Exception) {
                    sdf.parse(clean)?.time ?: 0L
                }
            } else {
                sdf.parse(clean)?.time ?: 0L
            }
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

    /**
     * Get detailed now/next info with times.
     */
    fun getNowNextDetailed(epg: Map<String, List<Programme>>, tvgId: String?): Pair<Programme?, Programme?> {
        if (tvgId.isNullOrBlank()) return null to null
        val norm = normalizeId(tvgId)
        val programmes = epg[norm] ?: return null to null
        val now = System.currentTimeMillis()
        var nowProg: Programme? = null
        var nextProg: Programme? = null
        for (p in programmes) {
            when {
                now in p.start..p.end -> nowProg = p
                now < p.start && nextProg == null -> { nextProg = p; break }
            }
        }
        return nowProg to nextProg
    }

    /**
     * Get all programmes for a channel on a specific day.
     */
    fun getProgrammesForDay(epg: Map<String, List<Programme>>, tvgId: String?, dayStartMs: Long, dayEndMs: Long): List<Programme> {
        if (tvgId.isNullOrBlank()) return emptyList()
        val norm = normalizeId(tvgId)
        val programmes = epg[norm] ?: return emptyList()
        return programmes.filter { it.start <= dayEndMs && it.end >= dayStartMs }
    }

    /**
     * Get progress of current programme (0.0 to 1.0).
     */
    fun getCurrentProgress(programme: Programme?): Float {
        if (programme == null) return 0f
        val now = System.currentTimeMillis()
        val total = programme.end - programme.start
        if (total <= 0) return 0f
        val elapsed = now - programme.start
        return (elapsed.toFloat() / total).coerceIn(0f, 1f)
    }
}
