package com.tvviewer

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    companion object {
        const val TAG = "SettingsFragment"
    }

    private lateinit var prefs: AppPreferences

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        setupPlayerType(view)
        setupLanguage(view)
        setupColorTheme(view)
        setupDisplay(view)
        setupPlayerSettings(view)
        setupCustomChannels(view)
        setupNetwork(view)
        setupAbout(view)
    }

    private fun setupNetwork(view: View) {
        // User-Agent
        val uaValue = view.findViewById<TextView>(R.id.userAgentValue)
        uaValue?.text = prefs.userAgent
        view.findViewById<LinearLayout>(R.id.userAgentLayout)?.setOnClickListener {
            val edit = EditText(requireContext()).apply {
                setText(prefs.userAgent)
                setSingleLine(false)
                setSelection(text.length)
            }
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.user_agent)
                .setView(edit)
                .setPositiveButton(R.string.ok) { _, _ ->
                    prefs.userAgent = edit.text.toString()
                    uaValue?.text = prefs.userAgent
                }
                .setNeutralButton(R.string.reset) { _, _ ->
                    prefs.userAgent = AppPreferences.DEFAULT_USER_AGENT
                    uaValue?.text = prefs.userAgent
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
                .installFocusListBackground()
        }

        // HTTP Referer (default: auto = stream's own scheme://host)
        val referValue = view.findViewById<TextView>(R.id.refererValue)
        fun refererSummary(): String {
            return if (prefs.httpReferer.isBlank()) getString(R.string.referer_auto)
            else prefs.httpReferer
        }
        referValue?.text = refererSummary()
        view.findViewById<LinearLayout>(R.id.refererLayout)?.setOnClickListener {
            val edit = EditText(requireContext()).apply {
                setText(prefs.httpReferer)
                hint = getString(R.string.referer_auto)
                setSingleLine(true)
                setSelection(text.length)
            }
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.http_referer)
                .setView(edit)
                .setPositiveButton(R.string.ok) { _, _ ->
                    prefs.httpReferer = edit.text.toString()
                    referValue?.text = refererSummary()
                }
                .setNeutralButton(R.string.reset) { _, _ ->
                    prefs.httpReferer = ""
                    referValue?.text = refererSummary()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
                .installFocusListBackground()
        }

        // Multi-EPG list
        val epgUrlsValue = view.findViewById<TextView>(R.id.epgUrlsValue)
        epgUrlsValue?.text = epgUrlsSummary()
        view.findViewById<LinearLayout>(R.id.epgUrlsLayout)?.setOnClickListener {
            showEpgUrlsDialog(epgUrlsValue)
        }

        // Ручное обновление ТВ Гида с visual-status. Юзер просил
        // видеть когда обновление идёт, и иметь возможность дёрнуть
        // его руками не открывая вкладку "ТВ программа".
        val epgManualStatus = view.findViewById<TextView>(R.id.epgManualRefreshStatus)
        updateEpgManualStatus(epgManualStatus)
        view.findViewById<LinearLayout>(R.id.epgManualRefreshLayout)?.setOnClickListener {
            triggerManualEpgRefresh(epgManualStatus)
        }
    }

    private fun updateEpgManualStatus(statusView: TextView?) {
        statusView ?: return
        val last = prefs.epgLastUpdate
        if (last <= 0) {
            statusView.text = "Нажмите чтобы обновить сейчас"
        } else {
            val ts = SimpleDateFormat("HH:mm dd.MM", Locale.getDefault())
                .format(java.util.Date(last))
            statusView.text = "Последнее обновление: $ts"
        }
    }

    private var manualRefreshJob: kotlinx.coroutines.Job? = null

    override fun onDestroyView() {
        // Job НЕ отменяем — fetchAll в applicationScope, должен
        // дописать кэш в фоне (Round 117). Но обнуляем onProgress
        // чтобы лямбда не держала ссылку на этот View и не пыталась
        // постить апдейты в уничтоженный statusView.
        EpgRepository.onProgress = null
        super.onDestroyView()
    }

    private fun triggerManualEpgRefresh(statusView: TextView?) {
        if (manualRefreshJob?.isActive == true) {
            Toast.makeText(requireContext(), "Обновление уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        val urls = prefs.allEpgUrls()
        if (urls.isEmpty()) {
            Toast.makeText(requireContext(), "Сначала добавьте EPG-источник", Toast.LENGTH_SHORT).show()
            return
        }
        // Защита "уже обновлено сегодня" срабатывает ТОЛЬКО когда у нас
        // действительно есть данные в кэше. Если ChannelDataHolder.epgData
        // пуст (а lastUpdate был выставлен от неуспешного refresh'а) —
        // даём свободно повторить. Плюс показываем диалог "Yes/No"
        // на случай если юзер всё равно хочет принудительно обновить.
        val last = prefs.epgLastUpdate
        val ageMs = System.currentTimeMillis() - last
        val cacheEmpty = ChannelDataHolder.epgData.isEmpty()
        if (last > 0 && ageMs < 24L * 60 * 60 * 1000 && !cacheEmpty) {
            val ts = SimpleDateFormat("HH:mm dd.MM", Locale.getDefault())
                .format(java.util.Date(last))
            android.app.AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle("Уже обновлено в $ts")
                .setMessage("Обновить заново?")
                .setPositiveButton("Обновить") { _, _ ->
                    runEpgRefresh(statusView, urls)
                }
                .setNegativeButton("Отмена", null)
                .show()
                .installFocusListBackground()
            return
        }
        runEpgRefresh(statusView, urls)
    }

    private fun runEpgRefresh(statusView: TextView?, urls: List<String>) {
        statusView?.text = "Запускаю обновление…"
        val ctx = requireContext().applicationContext
        // Подписываемся на live-прогресс из EpgRepository, чтобы
        // отображать "скачиваю / парсю / готово" в этой же строке.
        EpgRepository.onProgress = { stage ->
            view?.post { statusView?.text = stage }
        }
        // Используем applicationScope — НЕ lifecycleScope — иначе
        // когда юзер уходит из настроек до завершения, await ловит
        // CancellationException и мы показываем "Ошибка". Сам
        // EpgRepository.fetchAll работает в SupervisorJob, ему
        // отмена await не мешает.
        manualRefreshJob = TVViewerApp.applicationScope.launch {
            try {
                val data = EpgRepository.fetchAll(urls, ctx)
                if (data.isNotEmpty()) {
                    ChannelDataHolder.epgData = data
                    // epgLastUpdate ставим ТОЛЬКО если получили что-то.
                    // Иначе guard "уже обновлено" блокирует юзера хотя
                    // программ нет.
                    prefs.epgLastUpdate = System.currentTimeMillis()
                    view?.post {
                        statusView?.text = "Готово: ${data.size} каналов, " +
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
                    }
                    showToast(ctx, "ТВ Гид обновлён: ${data.size} каналов")
                } else {
                    view?.post { statusView?.text = "Источники пустые — попробуйте другой EPG" }
                    showToast(ctx, "Источники не отдали программу. Проверьте URL.", Toast.LENGTH_LONG)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Юзер ушёл из настроек, но fetchAll сам по себе
                // продолжается в fetchScope — не показываем ошибку.
            } catch (t: Throwable) {
                view?.post { statusView?.text = "Ошибка: ${t.javaClass.simpleName}" }
                showToast(ctx, "Ошибка обновления: ${t.message?.take(80)}", Toast.LENGTH_LONG)
            } finally {
                EpgRepository.onProgress = null
            }
        }
    }

    /** Toast с background-thread можно показывать ТОЛЬКО через main
     *  looper. Прямой вызов Toast.makeText из Dispatchers.IO падает с
     *  NullPointerException ("Can't toast on a thread that has not
     *  called Looper.prepare()"). */
    private fun showToast(ctx: android.content.Context, text: String, length: Int = Toast.LENGTH_SHORT) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx, text, length).show()
        }
    }

    private fun showEpgUrlsDialog(label: TextView?) {
        val items = prefs.allEpgUrls().toTypedArray()
        val builder = AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
            .setTitle(R.string.epg_urls)

        if (items.isEmpty()) {
            builder.setMessage(R.string.epg_urls_empty)
        } else {
            builder.setItems(items) { _, which ->
                AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                    .setTitle(R.string.delete)
                    .setMessage(items[which])
                    .setPositiveButton(R.string.delete) { _, _ ->
                        val toRemove = items[which]
                        if (prefs.lastEpgUrl == toRemove) {
                            prefs.lastEpgUrl = null
                        }
                        prefs.removeEpgUrl(toRemove)
                        label?.text = epgUrlsSummary()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                    .installFocusListBackground()
            }
        }

        builder.setPositiveButton(R.string.add_playlist) { _, _ ->
            val edit = EditText(requireContext()).apply {
                hint = "https://example.com/epg.xml.gz"
                setSingleLine(true)
            }
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.epg_urls_add)
                .setView(edit)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val url = edit.text.toString().trim()
                    if (url.isNotBlank()) {
                        if (prefs.lastEpgUrl.isNullOrBlank()) {
                            prefs.lastEpgUrl = url
                        } else {
                            prefs.addEpgUrl(url)
                        }
                        label?.text = epgUrlsSummary()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
                .installFocusListBackground()
        }
        // Кнопка "Из списка": готовые источники EPG. FocusableDialog
        // даёт надёжную D-pad подсветку (тот же селектор что у
        // настроечных диалогов).
        builder.setNeutralButton("Из списка") { _, _ ->
            val pairs = AppPreferences.SUGGESTED_EPG_URLS
            val titles = pairs.map { it.first }.toTypedArray()
            FocusableDialog.show(
                requireContext(),
                "Готовые EPG-источники",
                titles,
                0
            ) { which ->
                val url = pairs[which].second
                if (prefs.lastEpgUrl.isNullOrBlank()) {
                    prefs.lastEpgUrl = url
                } else {
                    prefs.addEpgUrl(url)
                }
                label?.text = epgUrlsSummary()
                Toast.makeText(requireContext(), "Добавлено: $url", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton(R.string.cancel, null)
        // installFocusListBackground для верхнего диалога (где идёт
        // setItems с EPG-URL'ами для удаления). Без него подсветка
        // выбранной строки не показывается на TV-боксе.
        builder.show().installFocusListBackground()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) view?.let { refreshValues(it) }
    }

    private fun refreshValues(view: View) {
        view.findViewById<TextView>(R.id.playerTypeValue)?.text =
            if (prefs.playerType == AppPreferences.PLAYER_INTERNAL)
                getString(R.string.player_internal) else getString(R.string.player_external)

        val langName = LocaleHelper.supportedLanguages.find { it.first == prefs.language }?.second ?: "System"
        view.findViewById<TextView>(R.id.languageValue)?.text = langName

        view.findViewById<TextView>(R.id.colorThemeValue)?.text = getThemeName(prefs.colorTheme)

        view.findViewById<TextView>(R.id.displayModeValue)?.text =
            if (prefs.listDisplayMode == "grid") getString(R.string.list_display_grid)
            else getString(R.string.list_display_list)

        view.findViewById<TextView>(R.id.qualityValue)?.text = when (prefs.preferredQuality) {
            "1080" -> getString(R.string.quality_1080)
            "4k" -> getString(R.string.quality_4k)
            else -> getString(R.string.quality_auto)
        }

        view.findViewById<TextView>(R.id.bufferValue)?.text = when (prefs.bufferMode) {
            "low" -> getString(R.string.buffer_low)
            "high" -> getString(R.string.buffer_high)
            else -> getString(R.string.buffer_normal)
        }

        view.findViewById<TextView>(R.id.orientationValue)?.text = when (prefs.screenOrientation) {
            "portrait" -> getString(R.string.orientation_portrait)
            "landscape" -> getString(R.string.orientation_landscape)
            else -> getString(R.string.orientation_auto)
        }

        view.findViewById<TextView>(R.id.sortValue)?.text = sortLabel(prefs.channelSort)
        view.findViewById<TextView>(R.id.userAgentValue)?.text = prefs.userAgent
        view.findViewById<TextView>(R.id.epgUrlsValue)?.text = epgUrlsSummary()

        view.findViewById<TextView>(R.id.autoHideValue)?.text =
            getString(R.string.controls_hide_seconds, prefs.channelListAutoHideSeconds)

        view.findViewById<TextView>(R.id.timeDisplayValue)?.text = when (prefs.timeDisplayPosition) {
            "left" -> getString(R.string.time_left)
            "right" -> getString(R.string.time_right)
            else -> getString(R.string.time_off)
        }

        view.findViewById<TextView>(R.id.sleepTimerValue)?.text = when (prefs.sleepTimerMinutes) {
            30 -> getString(R.string.sleep_timer_30)
            60 -> getString(R.string.sleep_timer_60)
            90 -> getString(R.string.sleep_timer_90)
            120 -> getString(R.string.sleep_timer_120)
            else -> getString(R.string.sleep_timer_off)
        }

        view.findViewById<TextView>(R.id.autoplayValue)?.text =
            if (prefs.autoplayLast) getString(R.string.autoplay_hint) else getString(R.string.time_off)

        view.findViewById<TextView>(R.id.epgAutoUpdateValue)?.text =
            if (prefs.epgAutoUpdate) getString(R.string.epg_auto_update_hint) else getString(R.string.time_off)

        view.findViewById<TextView>(R.id.parentalValue)?.text =
            if (prefs.parentalPin != null) getString(R.string.pin_set) else getString(R.string.time_off)

        view.findViewById<TextView>(R.id.versionText)?.text =
            getString(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    private fun setupPlayerType(view: View) {
        val playerTypeValue = view.findViewById<TextView>(R.id.playerTypeValue)
        playerTypeValue.text = if (prefs.playerType == AppPreferences.PLAYER_INTERNAL)
            getString(R.string.player_internal) else getString(R.string.player_external)

        view.findViewById<LinearLayout>(R.id.playerTypeLayout).setOnClickListener {
            val options = arrayOf(getString(R.string.player_internal), getString(R.string.player_external))
            val current = if (prefs.playerType == AppPreferences.PLAYER_INTERNAL) 0 else 1
            FocusableDialog.show(requireContext(), getString(R.string.player), options, current) { which ->
                    prefs.playerType = if (which == 0) AppPreferences.PLAYER_INTERNAL else AppPreferences.PLAYER_EXTERNAL
                    playerTypeValue.text = options[which]
                }
        }
    }

    private fun setupLanguage(view: View) {
        val langValue = view.findViewById<TextView>(R.id.languageValue)
        langValue.text = LocaleHelper.supportedLanguages.find { it.first == prefs.language }?.second ?: "System"

        view.findViewById<LinearLayout>(R.id.languageLayout).setOnClickListener {
            val names = LocaleHelper.supportedLanguages.map { it.second }.toTypedArray()
            val codes = LocaleHelper.supportedLanguages.map { it.first }
            val current = codes.indexOf(prefs.language).coerceAtLeast(0)

            FocusableDialog.show(requireContext(), getString(R.string.language), names, current) { which ->
                    prefs.language = codes[which]
                    langValue.text = names[which]
                    activity?.recreate()
                }
        }
    }

    private fun setupColorTheme(view: View) {
        val themeValue = view.findViewById<TextView>(R.id.colorThemeValue)
        themeValue.text = getThemeName(prefs.colorTheme)

        view.findViewById<LinearLayout>(R.id.colorThemeLayout).setOnClickListener {
            val names = arrayOf(
                getString(R.string.theme_purple),
                getString(R.string.theme_blue),
                getString(R.string.theme_green),
                getString(R.string.theme_orange),
                getString(R.string.theme_red)
            )
            val values = arrayOf("purple", "blue", "green", "orange", "red")
            val current = values.indexOf(prefs.colorTheme).coerceAtLeast(0)
            FocusableDialog.show(requireContext(), getString(R.string.color_theme), names, current) { which ->
                    prefs.colorTheme = values[which]
                    themeValue.text = names[which]
                    activity?.recreate()
                }
        }
    }

    private fun getThemeName(theme: String): String = when (theme) {
        "blue" -> getString(R.string.theme_blue)
        "green" -> getString(R.string.theme_green)
        "orange" -> getString(R.string.theme_orange)
        "red" -> getString(R.string.theme_red)
        else -> getString(R.string.theme_purple)
    }

    private fun setupDisplay(view: View) {
        val displayValue = view.findViewById<TextView>(R.id.displayModeValue)
        displayValue.text = if (prefs.listDisplayMode == "grid") getString(R.string.list_display_grid)
        else getString(R.string.list_display_list)

        view.findViewById<LinearLayout>(R.id.displayModeLayout).setOnClickListener {
            val options = arrayOf(getString(R.string.list_display_list), getString(R.string.list_display_grid))
            val current = if (prefs.listDisplayMode == "list") 0 else 1
            FocusableDialog.show(requireContext(), getString(R.string.list_display), options, current) { which ->
                    prefs.listDisplayMode = if (which == 0) "list" else "grid"
                    displayValue.text = options[which]
                }
        }

        val qualityValue = view.findViewById<TextView>(R.id.qualityValue)
        qualityValue.text = when (prefs.preferredQuality) {
            "1080" -> getString(R.string.quality_1080)
            "4k" -> getString(R.string.quality_4k)
            else -> getString(R.string.quality_auto)
        }

        view.findViewById<LinearLayout>(R.id.qualityLayout).setOnClickListener {
            val options = arrayOf(getString(R.string.quality_auto), getString(R.string.quality_1080), getString(R.string.quality_4k))
            val values = arrayOf("auto", "1080", "4k")
            val current = values.indexOf(prefs.preferredQuality).coerceAtLeast(0)
            FocusableDialog.show(requireContext(), getString(R.string.quality), options, current) { which ->
                    prefs.preferredQuality = values[which]
                    qualityValue.text = options[which]
                }
        }

        val bufferValue = view.findViewById<TextView>(R.id.bufferValue)
        bufferValue.text = when (prefs.bufferMode) {
            "low" -> getString(R.string.buffer_low)
            "high" -> getString(R.string.buffer_high)
            else -> getString(R.string.buffer_normal)
        }

        view.findViewById<LinearLayout>(R.id.bufferLayout).setOnClickListener {
            val options = arrayOf(getString(R.string.buffer_low), getString(R.string.buffer_normal), getString(R.string.buffer_high))
            val values = arrayOf("low", "normal", "high")
            val current = values.indexOf(prefs.bufferMode).coerceAtLeast(0)
            FocusableDialog.show(requireContext(), getString(R.string.buffer_mode), options, current) { which ->
                    prefs.bufferMode = values[which]
                    bufferValue.text = options[which]
                }
        }

        // Orientation
        val orientationValue = view.findViewById<TextView>(R.id.orientationValue)
        orientationValue.text = when (prefs.screenOrientation) {
            "portrait" -> getString(R.string.orientation_portrait)
            "landscape" -> getString(R.string.orientation_landscape)
            else -> getString(R.string.orientation_auto)
        }

        view.findViewById<LinearLayout>(R.id.orientationLayout).setOnClickListener {
            val options = arrayOf(getString(R.string.orientation_auto), getString(R.string.orientation_portrait), getString(R.string.orientation_landscape))
            val values = arrayOf("auto", "portrait", "landscape")
            val current = values.indexOf(prefs.screenOrientation).coerceAtLeast(0)
            FocusableDialog.show(requireContext(), getString(R.string.screen_orientation), options, current) { which ->
                    prefs.screenOrientation = values[which]
                    orientationValue.text = options[which]
                    activity?.recreate()
                }
        }

        // Channel sort
        val sortValue = view.findViewById<TextView>(R.id.sortValue)
        sortValue.text = sortLabel(prefs.channelSort)

        view.findViewById<LinearLayout>(R.id.sortLayout).setOnClickListener {
            val values = arrayOf("default", "number", "name", "group", "quality")
            val options = values.map { sortLabel(it) }.toTypedArray()
            val current = values.indexOf(prefs.channelSort).coerceAtLeast(0)
            FocusableDialog.show(requireContext(), getString(R.string.channel_sort), options, current) { which ->
                    prefs.channelSort = values[which]
                    sortValue.text = options[which]
                }
        }
    }

    private fun sortLabel(value: String): String = when (value) {
        "name" -> getString(R.string.sort_name)
        "group" -> getString(R.string.sort_group)
        "number" -> getString(R.string.sort_number)
        "quality" -> getString(R.string.sort_quality)
        else -> getString(R.string.sort_default)
    }

    private fun epgUrlsSummary(): String {
        val urls = prefs.allEpgUrls()
        if (urls.isEmpty()) return getString(R.string.epg_urls_empty)
        return getString(R.string.epg_urls_count, urls.size)
    }

    private fun setupPlayerSettings(view: View) {
        // Auto-hide controls
        val autoHideValue = view.findViewById<TextView>(R.id.autoHideValue)
        autoHideValue.text = getString(R.string.controls_hide_seconds, prefs.channelListAutoHideSeconds)

        view.findViewById<LinearLayout>(R.id.autoHideLayout).setOnClickListener {
            val options = arrayOf("3", "5", "7", "10", "15", "20")
            val values = intArrayOf(3, 5, 7, 10, 15, 20)
            val current = values.indexOfFirst { it == prefs.channelListAutoHideSeconds }.coerceAtLeast(0)
            FocusableDialog.show(requireContext(), getString(R.string.list_autohide), options.map { "$it сек" }.toTypedArray(), current) { which ->
                    prefs.channelListAutoHideSeconds = values[which]
                    autoHideValue.text = getString(R.string.controls_hide_seconds, values[which])
                }
        }

        // Time display
        val timeDisplayValue = view.findViewById<TextView>(R.id.timeDisplayValue)
        timeDisplayValue.text = when (prefs.timeDisplayPosition) {
            "left" -> getString(R.string.time_left)
            "right" -> getString(R.string.time_right)
            else -> getString(R.string.time_off)
        }

        view.findViewById<LinearLayout>(R.id.timeDisplayLayout).setOnClickListener {
            val options = arrayOf(getString(R.string.time_off), getString(R.string.time_left), getString(R.string.time_right))
            val values = arrayOf("off", "left", "right")
            val current = values.indexOf(prefs.timeDisplayPosition).coerceAtLeast(0)
            FocusableDialog.show(requireContext(), getString(R.string.time_display), options, current) { which ->
                    prefs.timeDisplayPosition = values[which]
                    timeDisplayValue.text = options[which]
                }
        }

        // Sleep timer
        val sleepTimerValue = view.findViewById<TextView>(R.id.sleepTimerValue)
        sleepTimerValue.text = when (prefs.sleepTimerMinutes) {
            30 -> getString(R.string.sleep_timer_30)
            60 -> getString(R.string.sleep_timer_60)
            90 -> getString(R.string.sleep_timer_90)
            120 -> getString(R.string.sleep_timer_120)
            else -> getString(R.string.sleep_timer_off)
        }

        view.findViewById<LinearLayout>(R.id.sleepTimerLayout).setOnClickListener {
            val options = arrayOf(
                getString(R.string.sleep_timer_off),
                getString(R.string.sleep_timer_30),
                getString(R.string.sleep_timer_60),
                getString(R.string.sleep_timer_90),
                getString(R.string.sleep_timer_120)
            )
            val values = intArrayOf(0, 30, 60, 90, 120)
            val current = values.indexOfFirst { it == prefs.sleepTimerMinutes }.coerceAtLeast(0)
            FocusableDialog.show(requireContext(), getString(R.string.sleep_timer), options, current) { which ->
                    prefs.sleepTimerMinutes = values[which]
                    sleepTimerValue.text = options[which]
                    if (values[which] > 0) {
                        Toast.makeText(requireContext(), getString(R.string.sleep_timer_set, options[which]), Toast.LENGTH_SHORT).show()
                    }
                }
        }

        // Autoplay
        val autoplayValue = view.findViewById<TextView>(R.id.autoplayValue)
        autoplayValue.text = if (prefs.autoplayLast) getString(R.string.autoplay_hint) else getString(R.string.time_off)

        view.findViewById<LinearLayout>(R.id.autoplayLayout).setOnClickListener {
            prefs.autoplayLast = !prefs.autoplayLast
            autoplayValue.text = if (prefs.autoplayLast) getString(R.string.autoplay_hint) else getString(R.string.time_off)
        }

        // EPG auto-update
        // Built-in playlists toggle
        val showBuiltinValue = view.findViewById<TextView>(R.id.showBuiltinValue)
        fun updateBuiltinValue() {
            showBuiltinValue.text = if (prefs.showBuiltInPlaylists)
                getString(R.string.time_on) else getString(R.string.time_off)
        }
        updateBuiltinValue()
        view.findViewById<LinearLayout>(R.id.showBuiltinLayout).setOnClickListener {
            prefs.showBuiltInPlaylists = !prefs.showBuiltInPlaylists
            updateBuiltinValue()
        }

        val epgAutoUpdateValue = view.findViewById<TextView>(R.id.epgAutoUpdateValue)
        epgAutoUpdateValue.text = if (prefs.epgAutoUpdate) getString(R.string.epg_auto_update_hint) else getString(R.string.time_off)

        view.findViewById<LinearLayout>(R.id.epgAutoUpdateLayout).setOnClickListener {
            prefs.epgAutoUpdate = !prefs.epgAutoUpdate
            epgAutoUpdateValue.text = if (prefs.epgAutoUpdate) getString(R.string.epg_auto_update_hint) else getString(R.string.time_off)
        }

        // Parental control
        val parentalValue = view.findViewById<TextView>(R.id.parentalValue)
        parentalValue.text = if (prefs.parentalPin != null) getString(R.string.pin_set) else getString(R.string.time_off)

        view.findViewById<LinearLayout>(R.id.parentalLayout).setOnClickListener {
            if (prefs.parentalPin != null) {
                // Remove PIN
                AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                    .setTitle(R.string.parental_control)
                    .setMessage(R.string.pin_enter)
                    .setView(EditText(requireContext()).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD; id = android.R.id.edit })
                    .setPositiveButton(R.string.ok) { dialog, _ ->
                        val input = (dialog as AlertDialog).findViewById<EditText>(android.R.id.edit)?.text.toString()
                        if (input == prefs.parentalPin) {
                            prefs.parentalPin = null
                            parentalValue.text = getString(R.string.time_off)
                            Toast.makeText(requireContext(), R.string.pin_removed, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                    .installFocusListBackground()
            } else {
                // Set PIN
                val editText = EditText(requireContext()).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    hint = getString(R.string.pin_enter)
                    id = android.R.id.edit
                }
                AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                    .setTitle(R.string.parental_control)
                    .setView(editText)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        val pin = editText.text.toString()
                        if (pin.length >= 4) {
                            prefs.parentalPin = pin
                            parentalValue.text = getString(R.string.pin_set)
                            Toast.makeText(requireContext(), R.string.pin_set, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                    .installFocusListBackground()
            }
        }

        // Clear cache
        view.findViewById<LinearLayout>(R.id.clearCacheLayout).setOnClickListener {
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.clear_cache)
                .setMessage(R.string.clear_cache_hint)
                .setPositiveButton(R.string.ok) { _, _ ->
                    ChannelDataHolder.epgData = emptyMap()
                    ChannelDataHolder.allChannels = emptyList()
                    requireContext().imageLoader.memoryCache?.clear()
                    // Delete EPG cache file
                    try {
                        java.io.File(requireContext().filesDir, "epg_cache.json").delete()
                    } catch (_: Exception) {}
                    Toast.makeText(requireContext(), R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
                .installFocusListBackground()
        }
    }

    private fun setupCustomChannels(view: View) {
        val recycler = view.findViewById<RecyclerView>(R.id.customChannelsRecyclerView)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        refreshCustomChannels(recycler)

        view.findViewById<LinearLayout>(R.id.addChannelLayout).setOnClickListener {
            showAddChannelDialog(recycler)
        }
    }

    private fun refreshCustomChannels(recycler: RecyclerView) {
        val channels = prefs.customChannels
        recycler.adapter = CustomChannelAdapter(channels) { index ->
            prefs.removeCustomChannel(index)
            refreshCustomChannels(recycler)
        }
    }

    private fun showAddChannelDialog(recycler: RecyclerView) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_channel, null)
        val nameEdit = dialogView.findViewById<EditText>(R.id.editChannelName)
        val urlEdit = dialogView.findViewById<EditText>(R.id.editChannelUrl)

        AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
            .setTitle(R.string.custom_channels)
            .setView(dialogView)
            .setPositiveButton(R.string.add_playlist) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val url = urlEdit.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    prefs.addCustomChannel(name, url)
                    refreshCustomChannels(recycler)
                } else {
                    Toast.makeText(requireContext(), R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .installFocusListBackground()
    }

    private fun setupAbout(view: View) {
        val versionText = view.findViewById<TextView>(R.id.versionText)
        versionText.text = getString(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        view.findViewById<LinearLayout>(R.id.updateLayout).setOnClickListener {
            checkForUpdates(versionText)
        }

        view.findViewById<LinearLayout>(R.id.errorLogLayout).setOnClickListener {
            showErrorLog()
        }
    }

    private fun checkForUpdates(versionText: TextView) {
        versionText.text = getString(R.string.checking_updates)
        lifecycleScope.launch {
            try {
                val prefs = AppPreferences(requireContext())
                val result = UpdateChecker.check(prefs.updateCheckUrl)
                val updateInfo = result.getOrNull()
                versionText.text = getString(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                if (updateInfo != null && updateInfo.versionCode > BuildConfig.VERSION_CODE) {
                    val message = buildString {
                        append("${getString(R.string.current_version)}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        append("\n${getString(R.string.new_version)}: ${updateInfo.versionName} (${updateInfo.versionCode})")
                        if (updateInfo.releaseNotes.isNotBlank()) {
                            append("\n\n${updateInfo.releaseNotes.take(500)}")
                        }
                    }
                    AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                        .setTitle(getString(R.string.update_available, updateInfo.versionName))
                        .setMessage(message)
                        .setPositiveButton(R.string.update_download) { _, _ ->
                            UpdateInstaller.downloadAndInstall(requireContext(), updateInfo.downloadUrl)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                        .installFocusListBackground()
                } else {
                    val msg = if (updateInfo != null) {
                        // Found a release but it's not newer — show both versions
                        "${getString(R.string.update_latest)}\n\n" +
                        "${getString(R.string.current_version)}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "GitHub: ${updateInfo.versionName} (${updateInfo.versionCode})"
                    } else {
                        "${getString(R.string.update_latest)}\n\n" +
                        "${getString(R.string.current_version)}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    }
                    AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                        .setTitle(R.string.check_for_updates)
                        .setMessage(msg)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                        .installFocusListBackground()
                }
            } catch (e: Exception) {
                versionText.text = getString(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                    .setTitle(R.string.update_check_failed)
                    .setMessage(e.message ?: e.javaClass.simpleName)
                    .setPositiveButton(R.string.ok, null)
                    .show()
                    .installFocusListBackground()
            }
        }
    }

    private fun showErrorLog() {
        val content = ErrorLogger.getErrorContent(requireContext())
        if (content.isBlank()) {
            Toast.makeText(requireContext(), R.string.no_errors_saved, Toast.LENGTH_SHORT).show()
            return
        }

        // Build the dialog with 3 actions ourselves so we can fit Send /
        // Copy / Clear / Cancel — AlertDialog only has 3 button slots
        // (positive / neutral / negative), so 'Clear' is offered as a
        // separate confirmation after Cancel for convenience.
        AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
            .setTitle(R.string.error_log)
            .setMessage(content.takeLast(3000))
            .setPositiveButton(R.string.send_to_github) { _, _ ->
                val title = "[Android error log] " +
                    content.lineSequence().firstOrNull { it.isNotBlank() }?.take(80).orEmpty()
                val body = buildString {
                    append("Лог ошибок отправлен из настроек.\n\n")
                    append(GitHubReporter.systemInfo())
                    append("\n**Log tail**:\n```\n")
                    append(content.takeLast(4000))
                    append("\n```\n")
                }
                GitHubReporter.report(requireContext(), title, body)
            }
            .setNeutralButton(R.string.clear_errors) { _, _ ->
                AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                    .setTitle(R.string.clear_errors)
                    .setMessage(R.string.clear_errors_confirm)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        ErrorLogger.clear(requireContext())
                        Toast.makeText(requireContext(), R.string.errors_cleared, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
                    .installFocusListBackground()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .installFocusListBackground()
    }
}
