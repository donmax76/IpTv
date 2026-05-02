package com.tvviewer

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Looks up logo URL and tvg-id for a channel by its display name using
 * the iptv-org public channel database (https://iptv-org.github.io/api/
 * channels.json). Used as a fallback when the user's M3U doesn't carry
 * tvg-logo / tvg-id attributes.
 *
 * Strategy:
 *  - Fetch channels.json once. Cache as a JSON file in app filesDir.
 *  - Refresh in the background every 7 days; until the first fetch
 *    completes, lookups return null and callers fall back to the host
 *    favicon. After it arrives, the channels list refreshes itself.
 *  - Build an in-memory map of normalized(name) -> (logoUrl, tvgId).
 */
object ChannelMetaLookup {

    private const val TAG = "ChannelMetaLookup"
    private const val URL = "https://iptv-org.github.io/api/channels.json"
    private const val CACHE_FILE = "iptv_org_channels.json"
    private const val CACHE_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000

    private val client: OkHttpClient = run {
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
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .sslSocketFactory(ctx.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    data class Meta(val logoUrl: String?, val tvgId: String?)

    private val byName = HashMap<String, Meta>(8000)
    // Параллельный fuzzy-индекс: fuzzyKey(name) → Meta. Для каждого
    // канала из iptv-org добавляется ещё запись по fuzzy-ключу. Это
    // позволяет матчить плейлисты с suffix'ами ("Cartoon Network HD")
    // на голые имена ("Cartoon Network") за O(1).
    private val byFuzzy = HashMap<String, Meta>(8000)
    @Volatile private var loaded = false
    private val loadingStarted = AtomicBoolean(false)
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun isLoaded(): Boolean = loaded

    /** Сколько каналов в индексе (чтобы понять — БД скачалась но
     *  пустая, или вообще не загрузилась). */
    @Synchronized
    fun indexSize(): Int = byName.size

    /** Первые N ключей byName для диагностики формата. */
    @Synchronized
    fun sampleKeys(n: Int = 10): List<String> = byName.keys.take(n).toList()

    /** Проверяет конкретные ключи — есть ли точное совпадение в индексе. */
    @Synchronized
    fun hasKeys(keys: List<String>): List<Pair<String, Boolean>> =
        keys.map { it to (it in byName) }

    /** Notify the caller (typically a RecyclerView adapter) once the
     *  database becomes available, so it can re-render channel rows
     *  with the freshly-found logos. */
    @Synchronized
    fun onLoaded(callback: () -> Unit) {
        if (loaded) callback() else listeners.add(callback)
    }

    @Synchronized
    fun lookup(channelName: String): Meta? {
        if (!loaded || channelName.isBlank()) return null
        // 1. Точное совпадение по нормализованному имени.
        byName[normalize(channelName)]?.let { return it }
        // 2. Fuzzy: убираем HD/SD/4K/UK/RU и хвостовые цифры. Например
        //    "Cartoon Network HD" matches "Cartoon Network".
        val fuzzy = EpgRepository.fuzzyKey(channelName)
        if (fuzzy.isNotEmpty()) byFuzzy[fuzzy]?.let { return it }
        return null
    }

    /** Kick off a background load. Safe to call repeatedly. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        if (!loadingStarted.compareAndSet(false, true)) return
        Thread {
            try {
                val cache = File(context.filesDir, CACHE_FILE)
                val fresh = cache.exists() &&
                    System.currentTimeMillis() - cache.lastModified() < CACHE_LIFETIME_MS
                val text = if (fresh) cache.readText() else fetchAndCache(cache)
                if (text != null) parseAndIndex(text)
                if (!fresh) {
                    // We loaded from a stale cache; queue a fresh fetch
                    // in the background so the next session has new data.
                    Thread { fetchAndCache(cache) }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureLoaded failed", e)
            } finally {
                loadingStarted.set(false)
                synchronized(this) {
                    loaded = true
                    val cbs = listeners.toList()
                    listeners.clear()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        cbs.forEach { it.invoke() }
                    }
                }
            }
        }.start()
    }

    private fun fetchAndCache(cache: File): String? {
        return try {
            val req = Request.Builder().url(URL).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string() ?: return null
                cache.writeText(body)
                body
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetch failed", e)
            null
        }
    }

    private fun parseAndIndex(text: String) {
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").takeIf { it.isNotEmpty() } ?: continue
                val tvgId = o.optString("id", "").takeIf { it.isNotEmpty() }
                val logo = o.optString("logo", "").takeIf { it.isNotEmpty() }
                if (logo == null && tvgId == null) continue
                val key = normalize(name)
                val meta = Meta(logo, tvgId)
                if (key.isNotEmpty() && key !in byName) {
                    byName[key] = meta
                    val fk = EpgRepository.fuzzyKey(name)
                    if (fk.isNotEmpty() && fk !in byFuzzy) byFuzzy[fk] = meta
                }
                // Also index alternative names
                val alt = o.optJSONArray("alt_names") ?: continue
                for (j in 0 until alt.length()) {
                    val altName = alt.optString(j, "")
                    val k = normalize(altName)
                    if (k.isNotEmpty() && k !in byName) {
                        byName[k] = meta
                        val fk = EpgRepository.fuzzyKey(altName)
                        if (fk.isNotEmpty() && fk !in byFuzzy) byFuzzy[fk] = meta
                    }
                }
            }
            Log.d(TAG, "indexed ${byName.size} channels")
        } catch (e: Exception) {
            Log.e(TAG, "parseAndIndex failed", e)
        }
    }

    private fun normalize(s: String): String =
        // Unicode-aware: \p{L} держит буквы любого алфавита (Cyrillic,
        // азербайджанский ə, türk ç, и пр.), \p{N} — цифры. Должно
        // совпадать с EpgRepository.normalizeId / TvGuideFragment.norm.
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}
