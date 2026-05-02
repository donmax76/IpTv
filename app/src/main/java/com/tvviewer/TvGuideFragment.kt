package com.tvviewer

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TvGuideFragment : Fragment() {

    companion object {
        const val TAG = "TvGuideFragment"
    }

    private lateinit var prefs: AppPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyLayout: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var epgStatus: TextView
    private lateinit var debugStatus: TextView
    private lateinit var searchEditText: EditText
    private lateinit var tvCurrentDate: TextView

    private var allChannelsWithEpg: List<EpgChannelItem> = emptyList()
    private var filteredItems: List<EpgChannelItem> = emptyList()
    private var selectedDateOffset = 0 // 0=today, -1=yesterday, 1=tomorrow
    /** Чтобы при пустом кэше один auto-refresh за сессию был
     *  гарантирован, а повторно при каждом onHiddenChanged не
     *  дёргался. Сбрасывается при destroy фрагмента. */
    private var triedAutoRefresh = false
    /** Активная EPG-загрузка. Отменяется при паузе фрагмента,
     *  чтобы парсер не ел CPU/heap, пока пользователь смотрит ТВ
     *  (отсюда были лаги видео). */
    private var refreshJob: kotlinx.coroutines.Job? = null

    data class EpgChannelItem(
        val channel: Channel,
        val programmes: List<EpgRepository.Programme>
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_tv_guide, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        recyclerView = view.findViewById(R.id.epgRecyclerView)
        progressBar = view.findViewById(R.id.epgProgressBar)
        emptyLayout = view.findViewById(R.id.epgEmptyLayout)
        emptyText = view.findViewById(R.id.epgEmptyText)
        epgStatus = view.findViewById(R.id.epgStatus)
        debugStatus = view.findViewById(R.id.epgDebugStatus)
        searchEditText = view.findViewById(R.id.epgSearchEditText)
        tvCurrentDate = view.findViewById(R.id.tvCurrentDate)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        view.findViewById<ImageButton>(R.id.btnRefreshEpg).setOnClickListener {
            refreshEpg()
        }

        view.findViewById<ImageButton>(R.id.btnPrevDay).setOnClickListener {
            selectedDateOffset--
            updateDateDisplay()
            filterAndDisplay()
        }

        view.findViewById<ImageButton>(R.id.btnNextDay).setOnClickListener {
            selectedDateOffset++
            updateDateDisplay()
            filterAndDisplay()
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterAndDisplay() }
        })

        updateDateDisplay()
        loadEpgData()
        // База iptv-org (логотипы + tvg-id по имени) грузится в фоне
        // при старте приложения. Когда дозагрузится — обновляем список,
        // чтобы появились лого / EPG-матчинг по name → tvg-id.
        ChannelMetaLookup.onLoaded {
            if (isAdded) loadEpgData()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) loadEpgData()
        // НЕ отменяем EPG-фетч при смене таба — пользователь может
        // вернуться. Отмена идёт только при паузе активити (см. onPause)
        // когда открывается плеер и видео.
    }

    override fun onPause() {
        super.onPause()
        // При паузе активити (например запуск плеера) отменяем
        // EPG-загрузку — освобождаем CPU/heap для видео.
        refreshJob?.cancel()
        refreshJob = null
        // Сбрасываем флаг, чтобы при возврате авто-refresh смог
        // запуститься заново (если EPG так и пуст).
        triedAutoRefresh = false
    }

    private fun updateDateDisplay() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, selectedDateOffset)
        val dateStr = when (selectedDateOffset) {
            0 -> getString(R.string.today)
            1 -> getString(R.string.tomorrow)
            -1 -> getString(R.string.yesterday)
            else -> SimpleDateFormat("dd MMMM", Locale.getDefault()).format(cal.time)
        }
        tvCurrentDate.text = dateStr
    }

    private fun loadEpgData() {
        val channels = ChannelDataHolder.allChannels
        // Диагностика рисуется СРАЗУ — до любой тяжёлой работы.
        // Если ниже что-то упадёт, юзер всё равно увидит счётчики.
        val mlLoaded = if (ChannelMetaLookup.isLoaded()) "✓" else "…"
        debugStatus.text = "Каналов: ${channels.size} | EPG-кэш: ${ChannelDataHolder.epgData.size} | iptv-org: $mlLoaded | загрузка…"

        if (channels.isEmpty()) {
            emptyLayout.visibility = View.VISIBLE
            emptyText.text = getString(R.string.epg_load_playlist_first)
            recyclerView.visibility = View.GONE
            epgStatus.text = ""
            return
        }

        // Подгружаем кэш если ChannelDataHolder пуст. Это БЕЗ
        // спиннера — мгновенно.
        if (ChannelDataHolder.epgData.isEmpty()) {
            EpgRepository.loadFromCache(requireContext())?.takeIf { it.isNotEmpty() }
                ?.let { ChannelDataHolder.epgData = it }
        }

        // Авто-refresh: если кэш пуст — пробуем ОДИН раз за сессию
        // фрагмента (флаг triedAutoRefresh). Если есть данные —
        // обновляем не чаще раза в 6 часов. Список каналов всегда
        // рендерится сразу (с программой если кэш есть, без — если
        // нет), refresh идёт в фоне.
        if (prefs.allEpgUrls().isNotEmpty()) {
            val empty = ChannelDataHolder.epgData.isEmpty()
            // Раньше было 6 часов — но на X4 X4 парсинг 43 MB EPG
            // занимает 30-60 сек CPU и бьёт по плееру (видео тормозит).
            // Подняли до 24ч: достаточно для актуальности (XMLTV-файлы
            // обычно содержат расписание на 3-7 дней вперёд), и
            // тяжёлый парсинг не запускается каждые пару часов.
            val sixHoursAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            val needFresh = !empty && prefs.epgLastUpdate < sixHoursAgo
            if ((empty && !triedAutoRefresh) || needFresh) {
                triedAutoRefresh = true
                refreshEpg()
            }
        }
        val epgData = ChannelDataHolder.epgData

        fun norm(s: String?): String =
            // Unicode-aware: \p{L} держит буквы любого алфавита (включая
            // кириллицу), \p{N} держит цифры. Должна совпадать с
            // EpgRepository.normalizeId, иначе ключи не сматчатся.
            s?.lowercase()?.replace(Regex("[^\\p{L}\\p{N}]"), "") ?: ""
        allChannelsWithEpg = channels.map { ch ->
            // Расширенный матчинг: пробуем
            //  1. tvg-id из плейлиста
            //  2. имя канала (XMLTV-парсер индексирует и по display-name)
            //  3. tvg-id, найденный ChannelMetaLookup'ом по имени (база
            //     iptv-org) — спасает каналы без tvg-id в M3U
            //  4. fuzzy-ключ: имя без суффиксов "HD/SD/4K/UK/RU/digits"
            //     — матчит "Sky Sports News HD 50 UK" с XMLTV id
            //     "skysportsnews.uk".
            val programmes = epgData[norm(ch.tvgId)]
                ?: epgData[norm(ch.name)]
                ?: ChannelMetaLookup.lookup(ch.name)?.tvgId?.let { epgData[norm(it)] }
                ?: epgData[EpgRepository.fuzzyKey(ch.name)]
                ?: epgData[EpgRepository.fuzzyKey(ch.tvgId)]
                ?: emptyList()
            EpgChannelItem(ch, programmes)
        }

        val channelsWithData = allChannelsWithEpg.count { it.programmes.isNotEmpty() }
        epgStatus.text = getString(R.string.epg_channels_count, channelsWithData)
        // Финальная диагностика — обновляем после того как всё посчитали.
        // Разбиваем на категории чтобы видеть откуда лого:
        //  fromM3U      — tvg-logo есть в плейлисте (включая favicon-fallback)
        //  fromIptvOrg  — нашли через ChannelMetaLookup
        //  noLogo       — не нашли никак
        var logosFromM3U = 0
        var logosFromIptvOrg = 0
        var logosNone = 0
        for (ch in channels) {
            if (ch.logoUrl != null) logosFromM3U++
            else if (ChannelMetaLookup.lookup(ch.name)?.logoUrl != null) logosFromIptvOrg++
            else logosNone++
        }
        val logosMatched = logosFromM3U + logosFromIptvOrg
        val ctx2 = context?.applicationContext
        if (ctx2 != null) {
            ErrorLogger.info(ctx2, "TVGUIDE",
                "logos: m3u=$logosFromM3U iptv-org=$logosFromIptvOrg none=$logosNone | " +
                "iptv-org-loaded=${ChannelMetaLookup.isLoaded()} index=${ChannelMetaLookup.indexSize()}")
            // Примеры каналов БЕЗ лого — поможет понять что в них особенного.
            val noLogoSample = channels.filter {
                it.logoUrl == null && ChannelMetaLookup.lookup(it.name)?.logoUrl == null
            }.take(5).joinToString { "${it.name}→${EpgRepository.fuzzyKey(it.name)}" }
            if (noLogoSample.isNotEmpty()) {
                ErrorLogger.info(ctx2, "TVGUIDE", "no-logo sample: $noLogoSample")
            }
            // Примеры логотипов с URL — чтобы видеть формат и хост.
            // Если Coil их не грузит, по URL/хосту сразу понятно почему.
            val logoSample = channels.mapNotNull { it.logoUrl }.distinctBy {
                try { java.net.URI(it).host } catch (_: Exception) { it }
            }.take(3).joinToString(" | ")
            if (logoSample.isNotEmpty()) {
                ErrorLogger.info(ctx2, "TVGUIDE", "logo URLs sample: ${logoSample.take(300)}")
            }
        }
        // Дамп: сколько ID/имён в плейлисте vs в EPG, сколько пересечений.
        // Это даёт понять, где теряется матч — в загрузке EPG, в его
        // парсинге или в нормализации ключей.
        val ctx = context?.applicationContext
        if (ctx != null) {
            val epgKeys = epgData.keys
            val playlistTvgIds = channels.mapNotNull { norm(it.tvgId).takeIf { k -> k.isNotEmpty() } }.toSet()
            val playlistNames = channels.map { norm(it.name) }.filter { it.isNotEmpty() }.toSet()
            val intersectByTvg = playlistTvgIds.count { it in epgKeys }
            val intersectByName = playlistNames.count { it in epgKeys }
            val sampleEpg = epgKeys.take(5).joinToString()
            val samplePl = (playlistTvgIds + playlistNames).take(5).joinToString()
            ErrorLogger.info(ctx, "TVGUIDE",
                "match: channels=${channels.size} epg-keys=${epgKeys.size} " +
                "match-by-tvgId=$intersectByTvg/${playlistTvgIds.size} " +
                "match-by-name=$intersectByName/${playlistNames.size} " +
                "channelsWithData=$channelsWithData | " +
                "epgSample=[$sampleEpg] plSample=[$samplePl]")
        }
        val baseLine = "Каналов: ${channels.size} | EPG-кэш: ${epgData.size} | " +
            "EPG-match: $channelsWithData | Лого: $logosMatched | iptv-org: $mlLoaded"
        // Если был fetch — показываем результат каждого источника +
        // ошибки. Без этого после refresh'а debugStatus перетирался
        // и юзер не успевал увидеть КАК провалились источники.
        val fetchInfo = buildString {
            val summary = EpgRepository.lastFetchSummary
            if (summary.isNotEmpty()) {
                append("\nИсточники: ")
                append(summary.joinToString(", ") { (url, count) ->
                    val host = url.substringAfter("://").substringBefore("/").take(20)
                    "$host:$count"
                })
            }
            val errs = EpgRepository.lastFetchErrors
            if (errs.isNotEmpty()) {
                append("\nОшибки: ")
                append(errs.joinToString("; ") { (url, msg) ->
                    val host = url.substringAfter("://").substringBefore("/").take(20)
                    "$host=${msg.take(80)}"
                })
            }
            val peek = EpgRepository.lastFetchPeek
            if (peek.isNotEmpty()) {
                append("\nПервые байты: ")
                append(peek.take(120))
            }
        }
        debugStatus.text = baseLine + fetchInfo

        if (prefs.epgLastUpdate > 0) {
            val dateStr = SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date(prefs.epgLastUpdate))
            epgStatus.text = "${epgStatus.text} • ${getString(R.string.epg_last_update, dateStr)}"
        }

        filterAndDisplay()
    }

    private fun filterAndDisplay() {
        val query = searchEditText.text.toString().trim().lowercase()

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, selectedDateOffset)
        val dayStart = cal.clone() as Calendar
        dayStart.set(Calendar.HOUR_OF_DAY, 0)
        dayStart.set(Calendar.MINUTE, 0)
        dayStart.set(Calendar.SECOND, 0)
        val dayEnd = cal.clone() as Calendar
        dayEnd.set(Calendar.HOUR_OF_DAY, 23)
        dayEnd.set(Calendar.MINUTE, 59)
        dayEnd.set(Calendar.SECOND, 59)

        filteredItems = allChannelsWithEpg
            .filter { item ->
                query.isEmpty() || item.channel.name.lowercase().contains(query)
            }
            .map { item ->
                val dayProgs = item.programmes.filter { p ->
                    p.start <= dayEnd.timeInMillis && p.end >= dayStart.timeInMillis
                }
                item.copy(programmes = dayProgs)
            }

        if (filteredItems.isEmpty()) {
            emptyLayout.visibility = View.VISIBLE
            emptyText.text = if (allChannelsWithEpg.isEmpty()) {
                getString(R.string.epg_load_playlist_first)
            } else {
                getString(R.string.epg_no_data)
            }
            recyclerView.visibility = View.GONE
        } else {
            emptyLayout.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            recyclerView.adapter = EpgAdapter(filteredItems) { channel ->
                playChannel(channel)
            }
        }
    }

    private fun refreshEpg() {
        val urls = prefs.allEpgUrls()
        if (urls.isEmpty()) {
            Toast.makeText(requireContext(), R.string.epg_no_data, Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        // applicationContext чтобы переживать detach.
        val appCtx = requireContext().applicationContext
        val started = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        debugStatus.text = "[$started] Запрашиваю ${urls.size} EPG-источник(ов)…"
        // Фильтр: парсим только программы каналов, что есть в текущем
        // плейлисте. Без этого парсер аллоцирует тысячи объектов
        // Раньше тут устанавливали EpgRepository.channelFilter под
        // текущий плейлист — это давало отфильтрованный кэш, и при
        // переключении плейлиста его каналы не находили программу.
        // Теперь парсер сохраняет ВСЕ каналы из EPG-источника,
        // фильтрация происходит уже в loadEpgData при матчинге
        // конкретного плейлиста с общим кэшем.
        EpgRepository.channelFilter = null
        // Подписываемся на прогресс — без этого debugStatus не менялся
        // от "Запрашиваю…" до финального результата, юзер думал зависло.
        EpgRepository.onProgress = { stage ->
            if (isAdded) debugStatus.text = "[$started] $stage"
        }
        Toast.makeText(appCtx, "EPG: запрашиваю ${urls.size} источник(ов)…", Toast.LENGTH_SHORT).show()
        // Отменяем предыдущий job если он ещё бежит (например юзер
        // успел нажать refresh дважды).
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            try {
                val data = EpgRepository.fetchAll(urls, appCtx)
                ChannelDataHolder.epgData = data
                // Метку времени ставим в любом случае — даже когда ответ
                // пустой / провалился. Иначе loadEpgData может крутить
                // повторные refresh'и.
                prefs.epgLastUpdate = System.currentTimeMillis()
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                loadEpgData()
                val summary = EpgRepository.lastFetchSummary
                    .joinToString(", ") { (url, count) ->
                        val host = url.substringAfter("://").substringBefore("/").take(20)
                        "$host:$count"
                    }
                val errSummary = EpgRepository.lastFetchErrors
                    .joinToString("; ") { (url, msg) ->
                        val host = url.substringAfter("://").substringBefore("/").take(20)
                        "$host=$msg".take(140)
                    }
                val finished = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val statusLine = if (errSummary.isNotEmpty())
                    "[$finished] $summary | err: $errSummary"
                else
                    "[$finished] $summary (всего ${data.size})"
                debugStatus.text = statusLine
                Toast.makeText(appCtx, statusLine, Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                // Throwable, не Exception: ловим и OOM / StackOverflow.
                // Без этого ошибка молча убивала корутину и юзер видел
                // вечный спиннер ("зависает обновление ТВ гида").
                Log.e(TAG, "EPG refresh error", t)
                ErrorLogger.logException(appCtx, t)
                prefs.epgLastUpdate = System.currentTimeMillis()
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                val errMsg = "EPG ошибка: ${t.javaClass.simpleName} — ${t.message?.take(80)}"
                debugStatus.text = errMsg
                Toast.makeText(appCtx, errMsg, Toast.LENGTH_LONG).show()
            } finally {
                EpgRepository.onProgress = null
            }
        }
    }

    private fun playChannel(channel: Channel) {
        val index = ChannelDataHolder.allChannels.indexOf(channel)
        ChannelDataHolder.currentChannelIndex = if (index >= 0) index else 0

        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_URL, channel.url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, ChannelDataHolder.currentChannelIndex)
        }
        startActivity(intent)
    }
}
