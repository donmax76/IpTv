package com.fmradio.ui

import android.app.Activity
import android.util.Log
import android.app.AlertDialog
import android.widget.Button
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.*
import android.widget.ScrollView
import com.fmradio.R
import com.fmradio.data.PresetItem
import com.fmradio.data.RadioStation
import com.fmradio.data.StationStorage
import com.fmradio.dsp.DebugLog
import com.fmradio.dsp.FmScanner
import com.fmradio.dsp.RdsDecoder
import com.fmradio.rtlsdr.RtlSdrDevice
import com.fmradio.rtlsdr.UsbPermissionHelper
import kotlinx.coroutines.*

class MainActivity : Activity() {

    private lateinit var stationStorage: StationStorage
    private lateinit var permissionHelper: UsbPermissionHelper

    private var rtlSdrDevice: RtlSdrDevice? = null
    private var radioService: FmRadioService? = null
    private var serviceBound = false
    private var scanner: FmScanner? = null

    // Pending device that was opened before service was bound
    private var pendingDevice: RtlSdrDevice? = null
    private var pendingUsbDeviceName: String? = null

    // Handle USB device detach to clean up state
    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                onUsbDeviceDetached()
            }
        }
    }

    // Handle USB device attach — auto-connect when plugged in while app is open
    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                if (rtlSdrDevice == null || rtlSdrDevice?.isDeviceOpen() != true) {
                    rtlSdrDevice = null
                    connectDevice()
                }
            }
        }
    }

    private var currentBand: FmScanner.Band = FmScanner.Band.FM_BROADCAST

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView

    private lateinit var tvFrequency: TextView
    private lateinit var tvBandIndicator: TextView
    private lateinit var tvStereoIndicator: TextView
    private lateinit var tvRdsIndicator: TextView
    private lateinit var tvTaIndicator: TextView
    private lateinit var tvAfIndicator: TextView
    private lateinit var tvSignalBars: TextView
    private lateinit var tvRdsPs: TextView
    private lateinit var tvRdsRt: TextView
    private lateinit var tvRdsPty: TextView
    private lateinit var seekFrequency: SeekBar
    private lateinit var tvBandStart: TextView
    private lateinit var tvBandEnd: TextView

    private lateinit var tvStationName: TextView
    private lateinit var spectrumView: SpectrumView

    private lateinit var btnSeekBack: ImageButton
    private lateinit var btnFreqDown: ImageButton
    private lateinit var btnPlayStop: ImageButton
    private lateinit var btnFreqUp: ImageButton
    private lateinit var btnSeekForward: ImageButton

    private lateinit var tvStationsHeader: TextView
    private var stationsExpanded = true

    private lateinit var seekVolume: SeekBar
    private lateinit var seekBass: SeekBar
    private lateinit var seekTreble: SeekBar
    private lateinit var tvVolumeValue: TextView
    private lateinit var tvBassValue: TextView
    private lateinit var tvTrebleValue: TextView

    private lateinit var btnScan: Button
    private lateinit var btnAddStation: TextView
    private lateinit var btnAf: Button
    private lateinit var btnTa: Button
    private lateinit var btnPty: Button
    private lateinit var btnBand: Button

    private lateinit var layoutScanning: View
    private lateinit var progressScan: ProgressBar
    private lateinit var tvScanStatus: TextView

    // Debug panel
    private lateinit var layoutDebug: View
    private lateinit var tvDebugLog: TextView
    private lateinit var scrollDebug: ScrollView
    private lateinit var btnDebug: Button
    private lateinit var btnDebugSave: Button
    private lateinit var btnDebugClear: Button
    private lateinit var btnDebugClose: Button

    private lateinit var lvStations: ListView
    private lateinit var stationAdapter: StationAdapter

    private var currentFrequency: Long = 100000000L

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as FmRadioService.LocalBinder
            radioService = binder.getService()
            serviceBound = true

            radioService?.currentBand = currentBand

            radioService?.onFrequencyChanged = { freq ->
                runOnUiThread {
                    currentFrequency = freq
                    updateFrequencyDisplay(freq)
                    updateStationNameDisplay(freq)
                    clearRdsDisplay()
                    seekFrequency.progress = frequencyToProgress(freq)
                    stationAdapter.setSelectedFrequency(freq)
                    stationStorage.lastFrequency = freq
                }
            }
            radioService?.onRdsDataReceived = { rdsData ->
                runOnUiThread { updateRdsDisplay(rdsData) }
            }
            radioService?.onStereoChanged = { stereo ->
                runOnUiThread { updateStereoIndicator(stereo) }
            }
            radioService?.onSeekComplete = { foundFreq ->
                runOnUiThread {
                    if (foundFreq != null) {
                        currentFrequency = foundFreq
                        updateFrequencyDisplay(foundFreq)
                        seekFrequency.progress = frequencyToProgress(foundFreq)
                        stationStorage.lastFrequency = foundFreq
                    } else {
                        showToast(getString(R.string.msg_no_station_found))
                    }
                    tvStatus.text = if (radioService?.isPlaying == true)
                        getString(R.string.status_playing) else getString(R.string.status_connected)
                }
            }
            radioService?.onSignalStrengthChanged = { db ->
                runOnUiThread { updateSignalBars(db) }
            }
            radioService?.onAudioData = { samples, count ->
                spectrumView.updateAudio(samples, count)
            }
            radioService?.onPlaybackStateChanged = { playing ->
                runOnUiThread {
                    if (playing) {
                        btnPlayStop.setImageResource(R.drawable.ic_stop)
                        tvStatus.text = getString(R.string.status_playing)
                    } else {
                        btnPlayStop.setImageResource(R.drawable.ic_play)
                        tvStatus.text = getString(R.string.status_stopped)
                    }
                    updateFrequencyDisplay(radioService?.currentFrequency ?: currentFrequency)
                }
            }

            radioService?.afEnabled = stationStorage.afEnabled
            radioService?.taEnabled = stationStorage.taEnabled

            // If device was opened before service was bound, initialize now
            val pending = pendingDevice
            if (pending != null) {
                pendingDevice = null
                radioService?.initDevice(pending)
                tvStatus.text = getString(R.string.status_connected)
                tvDeviceInfo.text = getString(R.string.device_info_format,
                    pending.getTunerType().name, pendingUsbDeviceName ?: "")
                pendingUsbDeviceName = null
                setControlsEnabled(true)
                startPlayback()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            radioService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on for car use
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        stationStorage = StationStorage(this)
        // Auto-restore stations from backup if empty (fresh install / reinstall)
        if (stationStorage.loadStations().isEmpty()) {
            val restored = stationStorage.importFromBackup()
            if (restored > 0) {
                android.widget.Toast.makeText(this,
                    "Восстановлено $restored станций из бэкапа", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        permissionHelper = UsbPermissionHelper(this)
        permissionHelper.register()

        // Register USB detach receiver (system broadcast — needs RECEIVER_EXPORTED on Android 14+)
        val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        val attachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, detachFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(usbAttachReceiver, attachFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbDetachReceiver, detachFilter)
            registerReceiver(usbAttachReceiver, attachFilter)
        }

        initViews()
        setupListeners()
        loadSavedStations()
        restoreBand()
        restoreSettings()

        // Request notification permission on Android 13+ (for foreground service notification)
        // Start service immediately regardless — it works without the permission,
        // the notification just won't be visible to the user
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        startRadioService()

        // Auto-connect: always try to find and open RTL-SDR on startup
        connectDevice()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)

        tvFrequency = findViewById(R.id.tvFrequency)
        tvBandIndicator = findViewById(R.id.tvBandIndicator)
        tvStereoIndicator = findViewById(R.id.tvStereoIndicator)
        tvRdsIndicator = findViewById(R.id.tvRdsIndicator)
        tvTaIndicator = findViewById(R.id.tvTaIndicator)
        tvAfIndicator = findViewById(R.id.tvAfIndicator)
        tvSignalBars = findViewById(R.id.tvSignalBars)
        tvRdsPs = findViewById(R.id.tvRdsPs)
        tvRdsRt = findViewById(R.id.tvRdsRt)
        tvRdsPty = findViewById(R.id.tvRdsPty)
        seekFrequency = findViewById(R.id.seekFrequency)
        tvBandStart = findViewById(R.id.tvBandStart)
        tvBandEnd = findViewById(R.id.tvBandEnd)

        tvStationName = findViewById(R.id.tvStationName)
        spectrumView = findViewById(R.id.spectrumView)

        btnSeekBack = findViewById(R.id.btnSeekBack)
        btnFreqDown = findViewById(R.id.btnFreqDown)
        btnPlayStop = findViewById(R.id.btnPlayStop)
        btnFreqUp = findViewById(R.id.btnFreqUp)
        btnSeekForward = findViewById(R.id.btnSeekForward)

        tvStationsHeader = findViewById(R.id.tvStationsHeader)

        seekVolume = findViewById(R.id.seekVolume)
        seekBass = findViewById(R.id.seekBass)
        seekTreble = findViewById(R.id.seekTreble)
        tvVolumeValue = findViewById(R.id.tvVolumeValue)
        tvBassValue = findViewById(R.id.tvBassValue)
        tvTrebleValue = findViewById(R.id.tvTrebleValue)

        btnScan = findViewById(R.id.btnScan)
        btnAf = findViewById(R.id.btnAf)
        btnTa = findViewById(R.id.btnTa)
        btnPty = findViewById(R.id.btnPty)
        btnBand = findViewById(R.id.btnBand)
        layoutScanning = findViewById(R.id.layoutScanning)
        progressScan = findViewById(R.id.progressScan)
        tvScanStatus = findViewById(R.id.tvScanStatus)

        btnAddStation = findViewById(R.id.btnAddStation)
        btnAddStation.isClickable = true

        // Export/Import station buttons
        findViewById<android.widget.TextView>(R.id.btnExportStations).setOnClickListener { exportStations() }
        findViewById<android.widget.TextView>(R.id.btnImportStations).setOnClickListener { importStations() }

        lvStations = findViewById(R.id.lvStations)
        stationAdapter = StationAdapter(
            stations = emptyList(),
            onStationClick = { tuneToStation(it) },
            onLongClick = { showStationOptions(it) }
        )
        lvStations.adapter = stationAdapter

        seekVolume.max = 100
        layoutScanning.visibility = View.GONE

        // Debug panel
        layoutDebug = findViewById(R.id.layoutDebug)
        tvDebugLog = findViewById(R.id.tvDebugLog)
        scrollDebug = findViewById(R.id.scrollDebug)
        btnDebug = findViewById(R.id.btnDebug)
        btnDebugSave = findViewById(R.id.btnDebugSave)
        btnDebugClear = findViewById(R.id.btnDebugClear)
        btnDebugClose = findViewById(R.id.btnDebugClose)

        // Set version from BuildConfig (generated from git in build.gradle.kts)
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<android.widget.TextView>(R.id.tvVersion)?.text = "v$versionName"
        } catch (_: Exception) {}
    }

    private fun restoreBand() {
        val bandName = stationStorage.currentBandName
        currentBand = try {
            FmScanner.Band.valueOf(bandName)
        } catch (_: Exception) {
            FmScanner.Band.FM_BROADCAST
        }
        applyBand(currentBand)
    }

    private fun restoreSettings() {
        currentFrequency = stationStorage.lastFrequency
        updateFrequencyDisplay(currentFrequency)
        seekFrequency.progress = frequencyToProgress(currentFrequency)

        seekVolume.progress = (stationStorage.lastVolume * 100).toInt()
        tvVolumeValue.text = seekVolume.progress.toString()

        seekBass.progress = stationStorage.bassLevel
        tvBassValue.text = (seekBass.progress - 10).toString()

        seekTreble.progress = stationStorage.trebleLevel
        tvTrebleValue.text = (seekTreble.progress - 10).toString()

        updateAfIndicator(stationStorage.afEnabled)
        updateTaIndicator(stationStorage.taEnabled)
    }

    private fun setupListeners() {
        btnPlayStop.setOnClickListener {
            if (radioService?.isPlaying == true) stopPlayback() else startPlayback()
        }

        btnFreqDown.setOnClickListener { setFrequency(currentFrequency - currentBand.stepHz) }
        btnFreqUp.setOnClickListener { setFrequency(currentFrequency + currentBand.stepHz) }

        btnSeekBack.setOnClickListener {
            tvStatus.text = getString(R.string.status_seeking)
            radioService?.seekStation(forward = false)
        }
        btnSeekForward.setOnClickListener {
            tvStatus.text = getString(R.string.status_seeking)
            radioService?.seekStation(forward = true)
        }

        tvStationsHeader.setOnClickListener {
            stationsExpanded = !stationsExpanded
            lvStations.visibility = if (stationsExpanded) View.VISIBLE else View.GONE
            tvStationsHeader.text = "STATIONS ${if (stationsExpanded) "▼" else "▶"}"
        }

        seekFrequency.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) updateFrequencyDisplay(progressToFrequency(progress))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                setFrequency(progressToFrequency(sb.progress))
            }
        })

        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    radioService?.setVolume(progress / 100f)
                    stationStorage.lastVolume = progress / 100f
                }
                tvVolumeValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        seekBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress - 10
                tvBassValue.text = if (value > 0) "+$value" else value.toString()
                if (fromUser) { radioService?.setBass(progress); stationStorage.bassLevel = progress }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        seekTreble.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress - 10
                tvTrebleValue.text = if (value > 0) "+$value" else value.toString()
                if (fromUser) { radioService?.setTreble(progress); stationStorage.trebleLevel = progress }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnScan.setOnClickListener {
            if (scanner?.isScanning() == true) scanner?.stopScan() else startScan()
        }

        findViewById<Button>(R.id.btnExit).setOnClickListener {
            exitApp()
        }

        btnAddStation.setOnClickListener {
            DebugLog.log("UI", "btnAddStation clicked, freq=${currentFrequency/1e6}MHz")
            showAddStationDialog()
        }

        btnAf.setOnClickListener { toggleAf() }
        btnTa.setOnClickListener { toggleTa() }
        btnPty.setOnClickListener { showPtyInfo() }
        btnBand.setOnClickListener { showBandSelector() }

        // Debug panel
        btnDebug.setOnClickListener { toggleDebugPanel() }
        btnDebugSave.setOnClickListener { shareDebugLog() }
        findViewById<Button>(R.id.btnDebugLogToggle).setOnClickListener { v ->
            val btn = v as Button
            DebugLog.fileLoggingEnabled = !DebugLog.fileLoggingEnabled
            if (DebugLog.fileLoggingEnabled) {
                btn.text = "LOG:ON"
                btn.setTextColor(getColor(R.color.lcd_green))
            } else {
                btn.text = "LOG:OFF"
                btn.setTextColor(0xFFFF4444.toInt())
            }
        }
        btnDebugClear.setOnClickListener { DebugLog.clear(); tvDebugLog.text = "" }
        btnDebugClose.setOnClickListener { toggleDebugPanel() }
    }

    private fun shareDebugLog() {
        val intent = DebugLog.getShareIntent(this)
        if (intent != null) {
            startActivity(android.content.Intent.createChooser(intent, "Share FM Radio Debug Log"))
        } else {
            val file = DebugLog.getLogFile()
            val msg = if (file != null) "Log: ${file.absolutePath}" else "No log file"
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleDebugPanel() {
        try {
            val showing = layoutDebug.visibility == View.VISIBLE
            if (showing) {
                // First clear callback to stop any pending UI updates
                DebugLog.onNewLine = null
                DebugLog.enabled = false
                layoutDebug.visibility = View.GONE
            } else {
                layoutDebug.visibility = View.VISIBLE
                DebugLog.enabled = true
                // Limit text to last 200 lines to prevent OOM on large logs
                val fullText = DebugLog.getText()
                val lines = fullText.lines()
                val displayText = if (lines.size > 200) {
                    lines.takeLast(200).joinToString("\n")
                } else {
                    fullText
                }
                tvDebugLog.text = displayText
                scrollDebug.post { scrollDebug.fullScroll(View.FOCUS_DOWN) }
                // Capture view references safely for the callback
                val logView = tvDebugLog
                val scrollView = scrollDebug
                DebugLog.onNewLine = { line ->
                    runOnUiThread {
                        try {
                            if (logView.isAttachedToWindow && layoutDebug.visibility == View.VISIBLE) {
                                logView.append("\n$line")
                                // Trim if too long (prevent OOM over time)
                                if (logView.lineCount > 300) {
                                    val text = logView.text
                                    val start = logView.layout?.getLineStart(logView.lineCount - 200) ?: 0
                                    logView.text = text.subSequence(start, text.length)
                                }
                                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                            }
                        } catch (_: Exception) {
                            // View detached or invalid — ignore
                        }
                    }
                }
                // Log current state
                DebugLog.log("UI", "Debug enabled. Device=${rtlSdrDevice?.isDeviceOpen()}, playing=${radioService?.isPlaying}, freq=${currentFrequency/1e6}MHz")
                DebugLog.log("UI", "Volume=${seekVolume.progress}%, tuner=${rtlSdrDevice?.getTunerType()}")
            }
        } catch (e: Exception) {
            Log.e("FMRadio", "Debug panel error", e)
            DebugLog.log("UI", "Debug panel error: ${e.message}")
        }
    }

    private fun showBandSelector() {
        val bands = FmScanner.Band.values()
        val names = bands.map { "${it.shortName} — ${it.description}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_band_title))
            .setItems(names) { _, which ->
                selectBand(bands[which])
            }
            .show()
    }

    private fun selectBand(band: FmScanner.Band) {
        if (radioService?.isPlaying == true) stopPlayback()
        currentBand = band
        stationStorage.currentBandName = band.name
        radioService?.currentBand = band
        applyBand(band)
        setFrequency(band.startHz)
        showToast(getString(R.string.msg_band_changed, band.displayName))
    }

    private fun applyBand(band: FmScanner.Band) {
        tvBandIndicator.text = band.shortName
        seekFrequency.max = band.totalSteps
        tvBandStart.text = String.format("%.0f", band.startHz / 1e6)
        tvBandEnd.text = String.format("%.0f", band.endHz / 1e6)
        btnBand.text = getString(R.string.band_label_format,
            band.displayName, band.startHz / 1e6, band.endHz / 1e6)
    }

    private fun connectDevice() {
        tvStatus.text = getString(R.string.status_connecting)
        val device = RtlSdrDevice.findDevice(this)
        if (device == null) {
            tvStatus.text = getString(R.string.status_no_device)
            showToast(getString(R.string.msg_connect_rtlsdr))
            return
        }
        permissionHelper.requestPermission(device) { granted ->
            if (granted) openDevice(device)
            else {
                tvStatus.text = getString(R.string.status_permission_denied)
                showToast(getString(R.string.msg_usb_permission_needed))
            }
        }
    }

    @Volatile
    private var isConnecting = false

    private fun openDevice(usbDevice: UsbDevice) {
        if (isConnecting) {
            DebugLog.log("UI", "openDevice blocked — already connecting")
            return
        }
        isConnecting = true
        tvStatus.text = getString(R.string.status_connecting)
        setControlsEnabled(false)

        // Close previous device if any
        rtlSdrDevice?.close()
        rtlSdrDevice = null

        activityScope.launch {
            try {
                val dev = RtlSdrDevice(this@MainActivity)
                val success = withContext(Dispatchers.IO) {
                    dev.open(usbDevice)
                }

                if (success) {
                    rtlSdrDevice = dev
                    DebugLog.log("UI", "Device opened: tuner=${dev.getTunerType()}, name=${usbDevice.deviceName}")
                    val service = radioService
                    if (service != null) {
                        service.initDevice(dev)
                        tvStatus.text = getString(R.string.status_connected)
                        tvDeviceInfo.text = getString(R.string.device_info_format, dev.getTunerType().name, usbDevice.deviceName)
                        setControlsEnabled(true)
                        startPlayback()
                    } else {
                        pendingDevice = dev
                        pendingUsbDeviceName = usbDevice.deviceName
                        tvStatus.text = getString(R.string.status_connecting)
                    }
                } else {
                    tvStatus.text = getString(R.string.status_connection_failed)
                }
            } finally {
                isConnecting = false
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        btnPlayStop.isEnabled = enabled
        btnScan.isEnabled = enabled
        btnSeekBack.isEnabled = enabled
        btnSeekForward.isEnabled = enabled
    }

    private fun startPlayback() {
        val service = radioService ?: return
        if (rtlSdrDevice == null) { showToast(getString(R.string.msg_connect_first)); return }

        DebugLog.log("UI", "startPlayback: freq=${currentFrequency/1e6}MHz vol=${seekVolume.progress}%")
        service.tuneToFrequency(currentFrequency)
        service.startPlayback()
        service.setVolume(seekVolume.progress / 100f)
        service.setBass(seekBass.progress)
        service.setTreble(seekTreble.progress)

        btnPlayStop.setImageResource(R.drawable.ic_stop)
        tvStatus.text = getString(R.string.status_playing)
        clearRdsDisplay()
    }

    private fun stopPlayback() {
        radioService?.stopPlayback()
        btnPlayStop.setImageResource(R.drawable.ic_play)
        tvStatus.text = getString(R.string.status_stopped)
        clearRdsDisplay()
        updateStereoIndicator(false)
        updateSignalBars(-100f)
        spectrumView.clear()
    }

    private fun setFrequency(frequencyHz: Long) {
        val freq = frequencyHz.coerceIn(currentBand.startHz, currentBand.endHz)
        currentFrequency = freq
        stationStorage.lastFrequency = freq
        updateFrequencyDisplay(freq)
        updateStationNameDisplay(freq)
        seekFrequency.progress = frequencyToProgress(freq)
        if (radioService?.isPlaying == true) {
            radioService?.tuneToFrequency(freq)
            clearRdsDisplay()
        }
        stationAdapter.setSelectedFrequency(freq)
    }

    private fun updateStationNameDisplay(freq: Long) {
        // Show cached RDS PS → user name → hide (priority order)
        // When live RDS PS arrives, updateRdsDisplay() will replace it.
        val station = stationStorage.loadStations().find {
            Math.abs(it.frequencyHz - freq) < 50000
        }
        if (station != null) {
            if (station.rdsPs.isNotBlank()) {
                // Show cached RDS name immediately (cyan)
                tvStationName.text = station.rdsPs
                tvStationName.setTextColor(getColor(R.color.lcd_cyan))
                tvStationName.visibility = View.VISIBLE
                // Also show cached RDS RT if available
                if (station.rdsRt.isNotBlank()) {
                    tvRdsRt.text = station.rdsRt
                    tvRdsRt.visibility = View.VISIBLE
                }
                if (station.rdsPty.isNotBlank()) {
                    tvRdsPty.text = station.rdsPty
                    tvRdsPty.visibility = View.VISIBLE
                }
                tvRdsIndicator.setTextColor(getColor(R.color.lcd_green))
            } else if (station.name.isNotEmpty()) {
                // Show user-entered name (amber)
                tvStationName.text = station.name
                tvStationName.setTextColor(getColor(R.color.lcd_amber))
                tvStationName.visibility = View.VISIBLE
            } else {
                tvStationName.visibility = View.GONE
            }
        } else {
            tvStationName.visibility = View.GONE
        }
    }

    private fun tuneToStation(station: RadioStation) {
        setFrequency(station.frequencyHz)
        if (radioService?.isPlaying != true) {
            // Ensure scanner is not still using the device
            val sc = scanner
            if (sc != null && sc.isBusy) {
                activityScope.launch {
                    sc.stopScanAndWait()
                    withContext(Dispatchers.Main) { startPlayback() }
                }
            } else {
                startPlayback()
            }
        }
    }

    private fun startScan() {
        val dev = rtlSdrDevice ?: run { showToast(getString(R.string.msg_connect_first)); return }
        stopPlayback()
        scanner = FmScanner(dev)
        layoutScanning.visibility = View.VISIBLE
        btnScan.text = getString(R.string.btn_stop_scan)
        progressScan.progress = 0

        activityScope.launch {
            scanner?.scanBand(currentBand, object : FmScanner.ScanListener {
                override fun onScanProgress(currentFreqHz: Long, progress: Float) {
                    progressScan.progress = (progress * 100).toInt()
                    tvScanStatus.text = getString(R.string.scan_progress_format, currentFreqHz / 1e6, (progress * 100).toInt())
                    updateFrequencyDisplay(currentFreqHz)
                }
                override fun onStationFound(result: FmScanner.ScanResult) {
                    stationStorage.addStation(RadioStation(frequencyHz = result.frequencyHz, signalStrength = result.signalStrength))
                    loadSavedStations()
                }
                override fun onScanComplete(stations: List<FmScanner.ScanResult>) {
                    layoutScanning.visibility = View.GONE
                    btnScan.text = getString(R.string.btn_scan)
                    showToast(getString(R.string.msg_scan_complete, stations.size))
                    updateFrequencyDisplay(currentFrequency)
                }
                override fun onScanError(error: String) {
                    layoutScanning.visibility = View.GONE
                    btnScan.text = getString(R.string.btn_scan)
                    showToast(getString(R.string.msg_scan_error, error))
                }
            })
        }
    }

    private fun updateRdsDisplay(rdsData: RdsDecoder.RdsData) {
        tvRdsIndicator.setTextColor(if (rdsData.hasData) getColor(R.color.lcd_green) else getColor(R.color.lcd_dim))

        // RDS PS overrides user-entered station name on main display
        if (rdsData.ps.isNotBlank()) {
            tvStationName.text = rdsData.ps
            tvStationName.setTextColor(getColor(R.color.lcd_cyan)) // cyan for RDS
            tvStationName.visibility = View.VISIBLE
        }
        if (rdsData.rt.isNotBlank()) { tvRdsRt.text = rdsData.rt; tvRdsRt.visibility = View.VISIBLE }
        if (rdsData.ptyName.isNotBlank() && rdsData.pty > 0) { tvRdsPty.text = rdsData.ptyName; tvRdsPty.visibility = View.VISIBLE }

        tvTaIndicator.setTextColor(if (rdsData.ta) getColor(R.color.lcd_red) else getColor(R.color.lcd_dim))
        tvAfIndicator.setTextColor(
            if (rdsData.afList.isNotEmpty() && stationStorage.afEnabled)
                getColor(R.color.lcd_green) else getColor(R.color.lcd_dim)
        )

        if (rdsData.ps.isNotBlank()) {
            val stations = stationStorage.loadStations()
            val station = stations.find { Math.abs(it.frequencyHz - currentFrequency) < 25000 }
            if (station != null && station.rdsPs != rdsData.ps) {
                stationStorage.updateStation(station.copy(rdsPs = rdsData.ps, rdsRt = rdsData.rt, rdsPty = rdsData.ptyName))
                loadSavedStations()
            }
        }
    }

    private fun clearRdsDisplay() {
        tvRdsRt.visibility = View.GONE
        tvRdsPty.visibility = View.GONE
        tvRdsIndicator.setTextColor(getColor(R.color.lcd_dim))
        tvTaIndicator.setTextColor(getColor(R.color.lcd_dim))
        // Reset station name to user-entered (RDS will override when received)
        updateStationNameDisplay(currentFrequency)
    }

    private var smoothedSignalDb = -100f
    private var lastBars = 0

    private fun updateSignalBars(db: Float) {
        // No extra UI smoothing here — FmRadioService already measures signal
        // over a 333 ms window, which is smooth enough for the bars display.
        // The previous EMA (smoothed * 0.8 + db * 0.2) combined with the
        // service-side delta gate (abs(db - lastSignalDb) > 0.5) meant that
        // on a stable signal the UI got exactly one update from -100 dB to
        // the real level and then no more, so the smoothed value was stuck at
        // ~20% of the way there and bars took minutes (or never) to fill up.
        smoothedSignalDb = db

        val bars = when {
            db > -8f  -> 4
            db > -12f -> 3
            db > -18f -> 2
            db > -30f -> 1
            else      -> 0
        }

        if (bars == lastBars) return
        lastBars = bars

        val barText = when (bars) {
            0 -> "▁   "
            1 -> "▁▃  "
            2 -> "▁▃▅ "
            3 -> "▁▃▅▇"
            else -> "▁▃▅▇"
        }
        tvSignalBars.text = barText
        tvSignalBars.setTextColor(
            if (bars >= 3) getColor(R.color.lcd_green)
            else if (bars >= 1) getColor(R.color.lcd_amber)
            else getColor(R.color.lcd_dim)
        )
    }

    private fun updateStereoIndicator(stereo: Boolean) {
        tvStereoIndicator.setTextColor(if (stereo) getColor(R.color.lcd_green) else getColor(R.color.lcd_dim))
    }

    private fun toggleAf() {
        val newState = !stationStorage.afEnabled
        stationStorage.afEnabled = newState
        radioService?.afEnabled = newState
        updateAfIndicator(newState)
    }

    private fun toggleTa() {
        val newState = !stationStorage.taEnabled
        stationStorage.taEnabled = newState
        radioService?.taEnabled = newState
        updateTaIndicator(newState)
    }

    private fun updateAfIndicator(enabled: Boolean) {
        btnAf.setTextColor(if (enabled) getColor(R.color.lcd_green) else getColor(R.color.lcd_amber))
    }

    private fun updateTaIndicator(enabled: Boolean) {
        btnTa.setTextColor(if (enabled) getColor(R.color.lcd_green) else getColor(R.color.lcd_amber))
    }

    private fun showPtyInfo() {
        val rds = radioService?.currentRdsData ?: return
        if (rds.ptyName.isNotBlank()) showToast("PTY: ${rds.ptyName}")
        else showToast("No PTY data")
    }

    private fun showAddStationDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etFreq = EditText(this).apply {
            hint = getString(R.string.hint_frequency_mhz)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format("%.1f", currentFrequency / 1e6))
            selectAll()
        }
        val etName = EditText(this).apply {
            hint = getString(R.string.hint_station_name)
        }
        layout.addView(etFreq)
        layout.addView(etName)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_add_station_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val freqStr = etFreq.text.toString().trim().replace(',', '.')
                val name = etName.text.toString().trim()
                val freqMHz = freqStr.toDoubleOrNull()
                val bandStart = currentBand.startHz / 1e6
                val bandEnd = currentBand.endHz / 1e6
                if (freqMHz != null && freqMHz >= bandStart && freqMHz <= bandEnd) {
                    val freqHz = (freqMHz * 1_000_000).toLong()
                    stationStorage.addStation(RadioStation(frequencyHz = freqHz, name = name))
                    loadSavedStations()
                    setFrequency(freqHz)
                    if (radioService?.isPlaying != true) startPlayback()
                    showToast(getString(R.string.msg_station_added, String.format("%.1f MHz", freqMHz)))
                } else {
                    showToast("${getString(R.string.msg_invalid_frequency)}: ${String.format("%.1f", bandStart)}-${String.format("%.1f", bandEnd)} MHz")
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showStationOptions(station: RadioStation) {
        AlertDialog.Builder(this)
            .setTitle(station.displayName)
            .setItems(arrayOf(
                getString(R.string.option_rename),
                getString(R.string.option_delete)
            )) { _, which ->
                when (which) {
                    0 -> showRenameDialog(station)
                    1 -> { stationStorage.removeStation(station.frequencyHz); loadSavedStations() }
                }
            }.show()
    }

    private fun showRenameDialog(station: RadioStation) {
        val editText = EditText(this).apply { setText(station.name); hint = getString(R.string.hint_station_name) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_rename_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) { stationStorage.renameStation(station.frequencyHz, name); loadSavedStations() }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun exportStations() {
        val file = stationStorage.exportToDownloads()
        if (file != null) {
            showToast("Сохранено: Downloads/fm_stations.json")
        } else {
            showToast("Ошибка экспорта")
        }
    }

    private fun importStations() {
        // Open file picker so user can choose any .json file
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(android.content.Intent.createChooser(intent, "Выберите fm_stations.json"), REQUEST_IMPORT_STATIONS)
        } catch (e: Exception) {
            // Fallback: try Downloads then backup
            var count = stationStorage.importFromDownloads()
            if (count <= 0) count = stationStorage.importFromBackup()
            if (count > 0) {
                loadSavedStations()
                showToast("Импортировано $count станций")
            } else {
                showToast("Файл не найден")
            }
        }
    }

    companion object {
        private const val REQUEST_IMPORT_STATIONS = 9001
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_STATIONS && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
                // Write to temp file and import
                val tmp = java.io.File(cacheDir, "import_stations.json")
                tmp.writeText(json)
                val count = stationStorage.importFromFile(tmp)
                tmp.delete()
                if (count > 0) {
                    loadSavedStations()
                    showToast("Импортировано $count станций")
                } else {
                    showToast("Ошибка: неверный формат файла")
                }
            } catch (e: Exception) {
                showToast("Ошибка импорта: ${e.message}")
            }
        }
    }

    private fun loadSavedStations() {
        stationAdapter.updateStations(stationStorage.loadStations())
        stationAdapter.setSelectedFrequency(currentFrequency)
    }

    private fun updateFrequencyDisplay(frequencyHz: Long) {
        tvFrequency.text = if (frequencyHz >= 1000000000L)
            String.format("%.3f", frequencyHz / 1_000_000.0)
        else
            String.format("%.1f", frequencyHz / 1_000_000.0)
    }

    private fun frequencyToProgress(freq: Long): Int {
        val step = currentBand.stepHz
        return ((freq - currentBand.startHz) / step).toInt().coerceAtLeast(0)
    }

    private fun progressToFrequency(progress: Int): Long {
        val step = currentBand.stepHz
        return currentBand.startHz + progress * step
    }

    private fun startRadioService() {
        val intent = Intent(this, FmRadioService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun exitApp() {
        if (radioService?.isPlaying == true) stopPlayback()
        rtlSdrDevice?.close()
        rtlSdrDevice = null
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        stopService(Intent(this, FmRadioService::class.java))
        finishAffinity()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            // Bring activity to front so it's visible above system UI
            moveTaskToFront()

            // If no device or previous device was closed, try to connect
            if (rtlSdrDevice == null || rtlSdrDevice?.isDeviceOpen() != true) {
                rtlSdrDevice = null
                connectDevice()
            }
        }
    }

    private fun onUsbDeviceDetached() {
        // Stop playback and clean up device state
        if (radioService?.isPlaying == true) {
            stopPlayback()
        }
        rtlSdrDevice?.close()
        rtlSdrDevice = null
        pendingDevice = null
        setControlsEnabled(false)
        tvStatus.text = getString(R.string.status_no_device)
        tvDeviceInfo.text = ""
        showToast(getString(R.string.msg_connect_rtlsdr))
    }

    private fun moveTaskToFront() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_WITH_HOME)
        } catch (_: Exception) {
            // SecurityException on some devices — ignore
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbDetachReceiver) } catch (_: IllegalArgumentException) {}
        try { unregisterReceiver(usbAttachReceiver) } catch (_: IllegalArgumentException) {}
        permissionHelper.unregister()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        rtlSdrDevice?.close()
        activityScope.cancel()
        super.onDestroy()
    }
}
