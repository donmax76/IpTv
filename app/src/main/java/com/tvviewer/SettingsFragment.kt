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
        setupAbout(view)
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

        view.findViewById<TextView>(R.id.sortValue)?.text = when (prefs.channelSort) {
            "name" -> getString(R.string.sort_name)
            "group" -> getString(R.string.sort_group)
            else -> getString(R.string.sort_default)
        }

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
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.player)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.playerType = if (which == 0) AppPreferences.PLAYER_INTERNAL else AppPreferences.PLAYER_EXTERNAL
                    playerTypeValue.text = options[which]
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun setupLanguage(view: View) {
        val langValue = view.findViewById<TextView>(R.id.languageValue)
        langValue.text = LocaleHelper.supportedLanguages.find { it.first == prefs.language }?.second ?: "System"

        view.findViewById<LinearLayout>(R.id.languageLayout).setOnClickListener {
            val names = LocaleHelper.supportedLanguages.map { it.second }.toTypedArray()
            val codes = LocaleHelper.supportedLanguages.map { it.first }
            val current = codes.indexOf(prefs.language).coerceAtLeast(0)

            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.language)
                .setSingleChoiceItems(names, current) { dialog, which ->
                    prefs.language = codes[which]
                    langValue.text = names[which]
                    dialog.dismiss()
                    activity?.recreate()
                }
                .show()
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
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.color_theme)
                .setSingleChoiceItems(names, current) { dialog, which ->
                    prefs.colorTheme = values[which]
                    themeValue.text = names[which]
                    dialog.dismiss()
                    activity?.recreate()
                }
                .show()
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
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.list_display)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.listDisplayMode = if (which == 0) "list" else "grid"
                    displayValue.text = options[which]
                    dialog.dismiss()
                }
                .show()
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
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.quality)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.preferredQuality = values[which]
                    qualityValue.text = options[which]
                    dialog.dismiss()
                }
                .show()
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
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.buffer_mode)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.bufferMode = values[which]
                    bufferValue.text = options[which]
                    dialog.dismiss()
                }
                .show()
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
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.screen_orientation)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.screenOrientation = values[which]
                    orientationValue.text = options[which]
                    dialog.dismiss()
                    activity?.recreate()
                }
                .show()
        }

        // Channel sort
        val sortValue = view.findViewById<TextView>(R.id.sortValue)
        sortValue.text = when (prefs.channelSort) {
            "name" -> getString(R.string.sort_name)
            "group" -> getString(R.string.sort_group)
            else -> getString(R.string.sort_default)
        }

        view.findViewById<LinearLayout>(R.id.sortLayout).setOnClickListener {
            val options = arrayOf(getString(R.string.sort_default), getString(R.string.sort_name), getString(R.string.sort_group))
            val values = arrayOf("default", "name", "group")
            val current = values.indexOf(prefs.channelSort).coerceAtLeast(0)
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.channel_sort)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.channelSort = values[which]
                    sortValue.text = options[which]
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun setupPlayerSettings(view: View) {
        // Auto-hide controls
        val autoHideValue = view.findViewById<TextView>(R.id.autoHideValue)
        autoHideValue.text = getString(R.string.controls_hide_seconds, prefs.channelListAutoHideSeconds)

        view.findViewById<LinearLayout>(R.id.autoHideLayout).setOnClickListener {
            val options = arrayOf("3", "5", "7", "10", "15", "20")
            val values = intArrayOf(3, 5, 7, 10, 15, 20)
            val current = values.indexOfFirst { it == prefs.channelListAutoHideSeconds }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.list_autohide)
                .setSingleChoiceItems(options.map { "$it сек" }.toTypedArray(), current) { dialog, which ->
                    prefs.channelListAutoHideSeconds = values[which]
                    autoHideValue.text = getString(R.string.controls_hide_seconds, values[which])
                    dialog.dismiss()
                }
                .show()
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
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.time_display)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.timeDisplayPosition = values[which]
                    timeDisplayValue.text = options[which]
                    dialog.dismiss()
                }
                .show()
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
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                .setTitle(R.string.sleep_timer)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.sleepTimerMinutes = values[which]
                    sleepTimerValue.text = options[which]
                    dialog.dismiss()
                    if (values[which] > 0) {
                        Toast.makeText(requireContext(), getString(R.string.sleep_timer_set, options[which]), Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }

        // Autoplay
        val autoplayValue = view.findViewById<TextView>(R.id.autoplayValue)
        autoplayValue.text = if (prefs.autoplayLast) getString(R.string.autoplay_hint) else getString(R.string.time_off)

        view.findViewById<LinearLayout>(R.id.autoplayLayout).setOnClickListener {
            prefs.autoplayLast = !prefs.autoplayLast
            autoplayValue.text = if (prefs.autoplayLast) getString(R.string.autoplay_hint) else getString(R.string.time_off)
        }

        // EPG auto-update
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
        lifecycleScope.launch {
            try {
                val prefs = AppPreferences(requireContext())
                val result = UpdateChecker.check(prefs.updateCheckUrl)
                val updateInfo = result.getOrNull()
                if (updateInfo != null && updateInfo.versionCode > BuildConfig.VERSION_CODE) {
                    AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
                        .setTitle(getString(R.string.update_available, updateInfo.versionName))
                        .setPositiveButton(R.string.update_download) { _, _ ->
                            UpdateInstaller.downloadAndInstall(requireContext(), updateInfo.downloadUrl)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    Toast.makeText(requireContext(), R.string.update_latest, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.update_check_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showErrorLog() {
        val content = ErrorLogger.getErrorContent(requireContext())
        if (content.isBlank()) {
            Toast.makeText(requireContext(), R.string.no_errors_saved, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer_Dialog)
            .setTitle(R.string.error_log)
            .setMessage(content.takeLast(3000))
            .setPositiveButton(R.string.copy_errors) { _, _ ->
                val clip = ClipData.newPlainText("errors", content)
                (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(requireContext(), R.string.copied_send_to_dev, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
