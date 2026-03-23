package com.fmradio.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.fmradio.R
import com.fmradio.data.PresetItem
import com.fmradio.data.RadioStation
import com.fmradio.data.StationStorage
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

    private lateinit var btnSeekBack: ImageButton
    private lateinit var btnFreqDown: ImageButton
    private lateinit var btnPlayStop: ImageButton
    private lateinit var btnFreqUp: ImageButton
    private lateinit var btnSeekForward: ImageButton

    private lateinit var lvPresets: ListView
    private lateinit var presetAdapter: PresetAdapter
    private lateinit var btnAddPreset: Button
    private lateinit var tvPresetsHeader: TextView
    private lateinit var tvStationsHeader: TextView
    private var presetsExpanded = true
    private var stationsExpanded = true

    private lateinit var seekVolume: SeekBar
    private lateinit var seekBass: SeekBar
    private lateinit var seekTreble: SeekBar
    private lateinit var tvVolumeValue: TextView
    private lateinit var tvBassValue: TextView
    private lateinit var tvTrebleValue: TextView

    private lateinit var btnScan: Button
    private lateinit var btnAf: Button
    private lateinit var btnTa: Button
    private lateinit var btnPty: Button
    private lateinit var btnBand: Button

    private lateinit var layoutScanning: View
    private lateinit var progressScan: ProgressBar
    private lateinit var tvScanStatus: TextView

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
        permissionHelper = UsbPermissionHelper(this)
        permissionHelper.register()

        initViews()
        setupListeners()
        loadSavedStations()
        restoreBand()
        restoreSettings()

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

        btnSeekBack = findViewById(R.id.btnSeekBack)
        btnFreqDown = findViewById(R.id.btnFreqDown)
        btnPlayStop = findViewById(R.id.btnPlayStop)
        btnFreqUp = findViewById(R.id.btnFreqUp)
        btnSeekForward = findViewById(R.id.btnSeekForward)

        lvPresets = findViewById(R.id.lvPresets)
        btnAddPreset = findViewById(R.id.btnAddPreset)
        tvPresetsHeader = findViewById(R.id.tvPresetsHeader)
        tvStationsHeader = findViewById(R.id.tvStationsHeader)

        presetAdapter = PresetAdapter(
            presets = emptyList(),
            onPresetClick = { tuneToPreset(it) },
            onPresetLongClick = { showPresetOptions(it) },
            onDeleteClick = { deletePreset(it) }
        )
        lvPresets.adapter = presetAdapter

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

        lvStations = findViewById(R.id.lvStations)
        stationAdapter = StationAdapter(
            stations = emptyList(),
            onStationClick = { tuneToStation(it) },
            onFavoriteClick = { toggleFavorite(it) },
            onLongClick = { showStationOptions(it) }
        )
        lvStations.adapter = stationAdapter

        seekVolume.max = 100
        layoutScanning.visibility = View.GONE
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

        loadPresetsList()
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

        btnAddPreset.setOnClickListener { addCurrentFrequencyToPresets() }

        tvPresetsHeader.setOnClickListener {
            presetsExpanded = !presetsExpanded
            lvPresets.visibility = if (presetsExpanded) View.VISIBLE else View.GONE
            tvPresetsHeader.text = "PRESETS ${if (presetsExpanded) "▼" else "▶"}"
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

        btnAf.setOnClickListener { toggleAf() }
        btnTa.setOnClickListener { toggleTa() }
        btnPty.setOnClickListener { showPtyInfo() }
        btnBand.setOnClickListener { showBandSelector() }
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

    private fun openDevice(usbDevice: UsbDevice) {
        val dev = RtlSdrDevice(this)
        if (dev.open(usbDevice)) {
            rtlSdrDevice = dev
            radioService?.initDevice(dev)
            tvStatus.text = getString(R.string.status_connected)
            tvDeviceInfo.text = getString(R.string.device_info_format, dev.getTunerType().name, usbDevice.deviceName)
            setControlsEnabled(true)
            // Auto-start playback after connecting
            startPlayback()
        } else {
            tvStatus.text = getString(R.string.status_connection_failed)
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
    }

    private fun setFrequency(frequencyHz: Long) {
        val freq = frequencyHz.coerceIn(currentBand.startHz, currentBand.endHz)
        currentFrequency = freq
        stationStorage.lastFrequency = freq
        updateFrequencyDisplay(freq)
        seekFrequency.progress = frequencyToProgress(freq)
        if (radioService?.isPlaying == true) {
            radioService?.tuneToFrequency(freq)
            clearRdsDisplay()
        }
        stationAdapter.setSelectedFrequency(freq)
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

    private fun tuneToPreset(preset: PresetItem) {
        setFrequency(preset.frequencyHz)
        if (radioService?.isPlaying != true && rtlSdrDevice != null) {
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
        presetAdapter.setSelectedFrequency(preset.frequencyHz)
    }

    private fun addCurrentFrequencyToPresets() {
        stationStorage.addPresetItem(currentFrequency)
        loadPresetsList()
        presetAdapter.setSelectedFrequency(currentFrequency)
        showToast(String.format("Preset saved: %.1f MHz", currentFrequency / 1e6))
    }

    private fun deletePreset(preset: PresetItem) {
        stationStorage.removePresetItem(preset.frequencyHz)
        loadPresetsList()
    }

    private fun showPresetOptions(preset: PresetItem) {
        val editText = EditText(this).apply {
            setText(preset.name)
            hint = "Preset name"
        }
        AlertDialog.Builder(this)
            .setTitle("${preset.displayFrequency} MHz")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text.toString().trim()
                stationStorage.renamePresetItem(preset.frequencyHz, name)
                loadPresetsList()
            }
            .setNeutralButton("Delete") { _, _ -> deletePreset(preset) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadPresetsList() {
        presetAdapter.updatePresets(stationStorage.loadPresets())
        presetAdapter.setSelectedFrequency(currentFrequency)
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

        if (rdsData.ps.isNotBlank()) { tvRdsPs.text = rdsData.ps; tvRdsPs.visibility = View.VISIBLE }
        if (rdsData.rt.isNotBlank()) { tvRdsRt.text = rdsData.rt; tvRdsRt.visibility = View.VISIBLE }
        if (rdsData.ptyName.isNotBlank() && rdsData.pty > 0) { tvRdsPty.text = rdsData.ptyName; tvRdsPty.visibility = View.VISIBLE }

        tvTaIndicator.setTextColor(if (rdsData.ta) getColor(R.color.lcd_red) else getColor(R.color.lcd_dim))
        tvAfIndicator.setTextColor(
            if (rdsData.afList.isNotEmpty() && stationStorage.afEnabled)
                getColor(R.color.lcd_green) else getColor(R.color.lcd_dim)
        )

        if (rdsData.ps.isNotBlank()) {
            val stations = stationStorage.loadStations()
            val station = stations.find { Math.abs(it.frequencyHz - currentFrequency) < 50000 }
            if (station != null && station.rdsPs != rdsData.ps) {
                stationStorage.updateStation(station.copy(rdsPs = rdsData.ps, rdsRt = rdsData.rt, rdsPty = rdsData.ptyName))
                loadSavedStations()
            }
        }
    }

    private fun clearRdsDisplay() {
        tvRdsPs.visibility = View.GONE
        tvRdsRt.visibility = View.GONE
        tvRdsPty.visibility = View.GONE
        tvRdsIndicator.setTextColor(getColor(R.color.lcd_dim))
        tvTaIndicator.setTextColor(getColor(R.color.lcd_dim))
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

    private fun toggleFavorite(station: RadioStation) {
        stationStorage.toggleFavorite(station.frequencyHz)
        loadSavedStations()
    }

    private fun showStationOptions(station: RadioStation) {
        AlertDialog.Builder(this)
            .setTitle(station.displayName)
            .setItems(arrayOf(
                getString(R.string.option_rename),
                getString(R.string.option_delete),
                getString(R.string.option_toggle_favorite)
            )) { _, which ->
                when (which) {
                    0 -> showRenameDialog(station)
                    1 -> { stationStorage.removeStation(station.frequencyHz); loadSavedStations() }
                    2 -> toggleFavorite(station)
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

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && rtlSdrDevice == null) {
            connectDevice()
        }
    }

    override fun onDestroy() {
        permissionHelper.unregister()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        rtlSdrDevice?.close()
        activityScope.cancel()
        super.onDestroy()
    }
}
