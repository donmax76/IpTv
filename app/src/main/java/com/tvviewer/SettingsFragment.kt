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
        setupDisplay(view)
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
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer)
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

            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer)
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

    private fun setupDisplay(view: View) {
        val displayValue = view.findViewById<TextView>(R.id.displayModeValue)
        displayValue.text = if (prefs.listDisplayMode == "grid") getString(R.string.list_display_grid)
        else getString(R.string.list_display_list)

        view.findViewById<LinearLayout>(R.id.displayModeLayout).setOnClickListener {
            val options = arrayOf(getString(R.string.list_display_list), getString(R.string.list_display_grid))
            val current = if (prefs.listDisplayMode == "list") 0 else 1
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer)
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
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer)
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
            AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer)
                .setTitle(R.string.buffer_mode)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    prefs.bufferMode = values[which]
                    bufferValue.text = options[which]
                    dialog.dismiss()
                }
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

        AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer)
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
                    AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer)
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

        AlertDialog.Builder(requireContext(), R.style.Theme_TVViewer)
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
