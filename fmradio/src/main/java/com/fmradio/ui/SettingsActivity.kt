package com.fmradio.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.fmradio.data.StationStorage
import com.fmradio.dsp.DebugLog
import com.fmradio.util.ErrorLogger
import com.fmradio.util.LocaleHelper
import com.fmradio.util.UpdateChecker
import com.fmradio.util.UpdateInstaller
import kotlinx.coroutines.*

class SettingsActivity : Activity() {

    private lateinit var stationStorage: StationStorage
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Colors matching main app theme
    private val bgColor = 0xFF050508.toInt()
    private val sectionBg = 0xFF0E0E12.toInt()
    private val amberColor = 0xFFFFC107.toInt()
    private val greenColor = 0xFF00FF88.toInt()
    private val cyanColor = 0xFF00EFFF.toInt()
    private val dimColor = 0xFF2D5E4A.toInt()
    private val textSecondary = 0xFFB0BEC5.toInt()
    private val redColor = 0xFFFF4444.toInt()

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("fm_radio_stations", MODE_PRIVATE)
        val lang = prefs.getString("app_language", "system") ?: "system"
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stationStorage = StationStorage(this)
        setContentView(buildLayout())
    }

    private fun buildLayout(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 24)
        }

        // Top bar with back button and title
        root.addView(buildTopBar())

        // Audio section
        root.addView(buildSectionHeader(getString(com.fmradio.R.string.settings_audio)))
        root.addView(buildAudioSection())

        // Language section
        root.addView(buildSectionHeader(getString(com.fmradio.R.string.settings_language)))
        root.addView(buildLanguageSection())

        // Debug section
        root.addView(buildSectionHeader(getString(com.fmradio.R.string.settings_debug)))
        root.addView(buildDebugSection())

        // Updates section
        root.addView(buildSectionHeader(getString(com.fmradio.R.string.settings_updates)))
        root.addView(buildUpdatesSection())

        // About section
        root.addView(buildSectionHeader(getString(com.fmradio.R.string.settings_about)))
        root.addView(buildAboutSection())

        scroll.addView(root)
        return scroll
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)

            val backBtn = Button(this@SettingsActivity).apply {
                text = "<  ${getString(com.fmradio.R.string.settings_back)}"
                setTextColor(cyanColor)
                textSize = 14f
                setBackgroundColor(0x00000000)
                setOnClickListener { finish() }
            }
            addView(backBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            val title = TextView(this@SettingsActivity).apply {
                text = getString(com.fmradio.R.string.settings_title)
                setTextColor(amberColor)
                textSize = 20f
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.CENTER
            }
            addView(title, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))

            // Spacer to balance back button
            addView(android.view.View(this@SettingsActivity), LinearLayout.LayoutParams(
                backBtn.layoutParams.width.coerceAtLeast(80),
                1
            ))
        }
    }

    private fun buildSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(greenColor)
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(8, 20, 0, 6)
        }
    }

    private fun buildAudioSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(sectionBg)
            setPadding(16, 12, 16, 12)

            // Volume row
            addView(buildSliderRow("VOL", amberColor, 0, 100,
                (stationStorage.lastVolume * 100).toInt()) { progress ->
                stationStorage.lastVolume = progress / 100f
            })

            // Bass row
            addView(buildSliderRow("BASS", cyanColor, 0, 20,
                stationStorage.bassLevel, isBipolar = true) { progress ->
                stationStorage.bassLevel = progress
            })

            // Treble row
            addView(buildSliderRow("TREB", cyanColor, 0, 20,
                stationStorage.trebleLevel, isBipolar = true) { progress ->
                stationStorage.trebleLevel = progress
            })
        }
    }

    private fun buildLanguageSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(sectionBg)
            setPadding(16, 12, 16, 12)

            val prefs = getSharedPreferences("fm_radio_stations", MODE_PRIVATE)
            val currentLang = prefs.getString("app_language", "system") ?: "system"

            val row = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 8)
            }
            val label = TextView(this@SettingsActivity).apply {
                text = getString(com.fmradio.R.string.settings_app_language)
                setTextColor(textSecondary)
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val languages = LocaleHelper.supportedLanguages
            val currentName = languages.find { it.first == currentLang }?.second ?: "System"

            val langBtn = Button(this@SettingsActivity).apply {
                text = currentName
                setTextColor(amberColor)
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                setBackgroundColor(0xFF363640.toInt())
                setPadding(24, 8, 24, 8)
                setOnClickListener {
                    val names = languages.map { it.second }.toTypedArray()
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(getString(com.fmradio.R.string.settings_select_language))
                        .setItems(names) { _, which ->
                            val selected = languages[which].first
                            prefs.edit().putString("app_language", selected).apply()
                            // Recreate activity to apply new language
                            recreate()
                        }
                        .show()
                }
            }
            row.addView(langBtn)
            addView(row)
        }
    }

    private fun buildSliderRow(
        label: String,
        color: Int,
        min: Int,
        max: Int,
        initial: Int,
        isBipolar: Boolean = false,
        onChange: (Int) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)

            val lbl = TextView(this@SettingsActivity).apply {
                text = label
                setTextColor(color)
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                width = 60
            }
            addView(lbl)

            val valueText = TextView(this@SettingsActivity).apply {
                setTextColor(color)
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.END
                width = 48
                text = if (isBipolar) {
                    val v = initial - 10
                    if (v > 0) "+$v" else v.toString()
                } else initial.toString()
            }

            val seek = SeekBar(this@SettingsActivity).apply {
                this.max = max
                progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        if (isBipolar) {
                            val v = progress - 10
                            valueText.text = if (v > 0) "+$v" else v.toString()
                        } else {
                            valueText.text = progress.toString()
                        }
                        if (fromUser) onChange(progress)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueText)
        }
    }

    private fun buildDebugSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(sectionBg)
            setPadding(16, 12, 16, 12)

            // LOG ON/OFF toggle row
            val logRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 8)
            }
            val logLabel = TextView(this@SettingsActivity).apply {
                text = getString(com.fmradio.R.string.settings_file_logging)
                setTextColor(textSecondary)
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            logRow.addView(logLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val logToggle = Switch(this@SettingsActivity).apply {
                isChecked = DebugLog.fileLoggingEnabled
                thumbTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(greenColor, 0xFF888888.toInt())
                )
                trackTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(0xFF005533.toInt(), 0xFF444444.toInt())
                )
                setOnCheckedChangeListener { _, checked ->
                    DebugLog.fileLoggingEnabled = checked
                }
            }
            logRow.addView(logToggle)
            addView(logRow)

            // Button row
            val btnRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 4, 0, 4)
            }

            btnRow.addView(makeButton(getString(com.fmradio.R.string.settings_send_log), cyanColor) { sendDebugLog() })
            btnRow.addView(makeButton(getString(com.fmradio.R.string.settings_view_errors), amberColor) { viewErrors() })
            btnRow.addView(makeButton(getString(com.fmradio.R.string.settings_clear_errors), redColor) { clearErrors() })
            addView(btnRow)

            // Open Debug Panel button
            val debugBtnRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 4)
            }
            debugBtnRow.addView(makeButton(getString(com.fmradio.R.string.settings_open_debug_panel), amberColor) {
                openDebugPanel()
            })
            addView(debugBtnRow)
        }
    }

    private fun openDebugPanel() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("show_debug", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun buildUpdatesSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(sectionBg)
            setPadding(16, 12, 16, 12)

            // Current version
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) { "unknown" }

            val versionRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 8)
            }
            val versionLabel = TextView(this@SettingsActivity).apply {
                text = "${getString(com.fmradio.R.string.settings_current_version)}: $versionName"
                setTextColor(textSecondary)
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            versionRow.addView(versionLabel)
            addView(versionRow)

            // Auto-update toggle row
            val autoRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 8)
            }
            val autoLabel = TextView(this@SettingsActivity).apply {
                text = getString(com.fmradio.R.string.settings_auto_update)
                setTextColor(textSecondary)
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            autoRow.addView(autoLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val prefs = getSharedPreferences("fm_radio_stations", MODE_PRIVATE)
            val autoToggle = Switch(this@SettingsActivity).apply {
                isChecked = prefs.getBoolean("auto_update", true)
                thumbTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(greenColor, 0xFF888888.toInt())
                )
                trackTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(0xFF005533.toInt(), 0xFF444444.toInt())
                )
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("auto_update", checked).apply()
                }
            }
            autoRow.addView(autoToggle)
            addView(autoRow)

            // Check update button
            val btnRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 4, 0, 4)
            }
            btnRow.addView(makeButton(getString(com.fmradio.R.string.settings_check_update), greenColor) { checkForUpdates() })
            addView(btnRow)
        }
    }

    private fun buildAboutSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(sectionBg)
            setPadding(16, 12, 16, 12)

            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) { "unknown" }
            val versionCode = try {
                packageManager.getPackageInfo(packageName, 0).versionCode
            } catch (_: Exception) { 0 }

            addView(makeInfoRow("App", "FM Radio RTL-SDR"))
            addView(makeInfoRow("Version", "$versionName (build $versionCode)"))
            addView(makeInfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}"))
            addView(makeInfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
            addView(makeInfoRow("Arch", Build.SUPPORTED_ABIS.joinToString(", ")))
        }
    }

    private fun makeInfoRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)

            val lbl = TextView(this@SettingsActivity).apply {
                text = "$label:"
                setTextColor(dimColor)
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                width = 120
            }
            addView(lbl)

            val val_ = TextView(this@SettingsActivity).apply {
                text = value
                setTextColor(amberColor)
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            addView(val_)
        }
    }

    private fun makeButton(text: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(0xFF363640.toInt())
            setPadding(24, 8, 24, 8)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(4, 0, 4, 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun sendDebugLog() {
        val debugLog = DebugLog.getText()
        val errorLog = com.fmradio.util.ErrorLogger.getErrorContent(this)
        val combined = buildString {
            // Always first: this one is written even when file logging is off,
            // and it is the only record when the app dies before it can log.
            val startup = com.fmradio.util.StartupLog.read()
            if (startup.isNotBlank()) { append("=== STARTUP LOG ===\n"); append(startup); append("\n\n") }
            if (debugLog.isNotBlank()) { append("=== DEBUG LOG ===\n"); append(debugLog); append("\n\n") }
            if (errorLog.isNotBlank()) { append("=== ERROR LOG ===\n"); append(errorLog) }
        }
        if (combined.isBlank()) {
            Toast.makeText(this, "No log available", Toast.LENGTH_SHORT).show()
            return
        }
        com.fmradio.util.CrashReporter.sendLog(this, combined)
    }

    private fun viewErrors() {
        val content = ErrorLogger.getErrorContent(this)
        if (content.isBlank()) {
            Toast.makeText(this, "No errors logged", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Error Log")
            .setMessage(content.takeLast(4000))
            .setPositiveButton("OK", null)
            .setNeutralButton("Send") { _, _ ->
                com.fmradio.util.CrashReporter.sendLog(this, content)
            }
            .show()
    }

    private fun clearErrors() {
        ErrorLogger.clear(this)
        Toast.makeText(this, "Errors cleared", Toast.LENGTH_SHORT).show()
    }

    private fun checkForUpdates() {
        Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val versionCode = try {
                    packageManager.getPackageInfo(packageName, 0).versionCode
                } catch (_: Exception) { 0 }

                val update = UpdateChecker.check(versionCode)
                if (update != null) {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Update Available")
                        .setMessage("New version ${update.versionName} is available.\nCurrent build: $versionCode\n\nUpdate now?")
                        .setPositiveButton("Update") { _, _ ->
                            UpdateInstaller.downloadAndInstall(this@SettingsActivity, update.downloadUrl)
                        }
                        .setNegativeButton("Later", null)
                        .show()
                } else {
                    Toast.makeText(this@SettingsActivity, "App is up to date", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Update check failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
