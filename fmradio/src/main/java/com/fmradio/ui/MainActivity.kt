package com.fmradio.ui

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
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fmradio.R
import com.fmradio.data.RadioStation
import com.fmradio.data.StationStorage
import com.fmradio.dsp.FmScanner
import com.fmradio.dsp.RdsDecoder
import com.fmradio.rtlsdr.RtlSdrDevice
import com.fmradio.rtlsdr.UsbPermissionHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var stationStorage: StationStorage
    private lateinit var permissionHelper: UsbPermissionHelper

    private var rtlSdrDevice: RtlSdrDevice? = null
    private var radioService: FmRadioService? = null
    private var serviceBound = false
    private var scanner: FmScanner? = null

    // Current band
    private var currentBand: FmScanner.Band = FmScanner.Band.FM_BROADCAST

    // UI: Status
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var btnConnect: Button

    // UI: LCD Display
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

    // UI: Controls
    private lateinit var btnSeekBack: ImageButton
    private lateinit var btnFreqDown: ImageButton
    private lateinit var btnPlayStop: ImageButton
    private lateinit var btnFreqUp: ImageButton
    private lateinit var btnSeekForward: ImageButton

    // UI: Presets
    private lateinit var presetButtons: List<Button>

    // UI: Volume + EQ
    private lateinit var seekVolume: SeekBar
    private lateinit var seekBass: SeekBar
    private lateinit var seekTreble: SeekBar
    private lateinit var tvVolumeValue: TextView
    private lateinit var tvBassValue: TextView
    private lateinit var tvTrebleValue: TextView

    // UI: Function buttons
    private lateinit var btnScan: Button
    private lateinit var btnAf: Button
    private lateinit var btnTa: Button
    private lateinit var btnPty: Button
    private lateinit var btnBand: Button

    // UI: Scan
    private lateinit var layoutScanning: View
    private lateinit var progressScan: ProgressBar
    private lateinit var tvScanStatus: TextView

    // UI: Station list
    private lateinit var rvStations: RecyclerView
    private lateinit var stationAdapter: StationAdapter

    private var currentFrequency: Long = 100000000L

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

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            connectDevice()
        }
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        btnConnect = findViewById(R.id.btnConnect)

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

        presetButtons = listOf(
            findViewById(R.id.btnPreset1), findViewById(R.id.btnPreset2),
            findViewById(R.id.btnPreset3), findViewById(R.id.btnPreset4),
            findViewById(R.id.btnPreset5), findViewById(R.id.btnPreset6)
        )

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

        rvStations = findViewById(R.id.rvStations)
        stationAdapter = StationAdapter(
            stations = emptyList(),
            onStationClick = { tuneToStation(it) },
            onFavoriteClick = { toggleFavorite(it) },
            onLongClick = { showStationOptions(it) }
        )
        rvStations.layoutManager = LinearLayoutManager(this)
        rvStations.adapter = stationAdapter

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

        updatePresetLabels()
        updateAfIndicator(stationStorage.afEnabled)
        updateTaIndicator(stationStorage.taEnabled)
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener { connectDevice() }

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

        presetButtons.forEachIndexed { index, btn ->
            btn.setOnClickListener { loadPreset(index) }
            btn.setOnLongClickListener { savePreset(index); true }
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

    // --- Band Selector ---

    private fun showBandSelector() {
        val bands = FmScanner.Band.values()
        val names = bands.map { "${it.shortName} — ${it.description}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_band_title))
            .setItems(names) { _, which ->
                val band = bands[which]
                selectBand(band)
            }
            .show()
    }

    private fun selectBand(band: FmScanner.Band) {
        if (radioService?.isPlaying == true) stopPlayback()
        currentBand = band
        stationStorage.currentBandName = band.name
        radioService?.currentBand = band
        applyBand(band)

        // Set frequency to band start
        val newFreq = band.startHz
        setFrequency(newFreq)
        showToast(getString(R.string.msg_band_changed, band.displayName))
    }

    private fun applyBand(band: FmScanner.Band) {
        // Update LCD band indicator
        tvBandIndicator.text = band.shortName

        // Update seekbar range
        val totalSteps = band.totalSteps
        seekFrequency.max = totalSteps

        // Update scale labels
        tvBandStart.text = String.format("%.0f", band.startHz / 1e6)
        tvBandEnd.text = String.format("%.0f", band.endHz / 1e6)

        // Update band button text
        btnBand.text = getString(R.string.band_label_format,
            band.displayName, band.startHz / 1e6, band.endHz / 1e6)
    }

    // --- Device ---

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
            btnConnect.text = getString(R.string.btn_disconnect)
            setControlsEnabled(true)
            showToast(getString(R.string.msg_device_connected))
        } else {
            tvStatus.text = getString(R.string.status_connection_failed)
            showToast(getString(R.string.msg_connection_failed))
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        btnPlayStop.isEnabled = enabled
        btnScan.isEnabled = enabled
        btnSeekBack.isEnabled = enabled
        btnSeekForward.isEnabled = enabled
    }

    // --- Playback ---

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

    // --- Frequency ---

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
        if (radioService?.isPlaying != true) startPlayback()
    }

    // --- Presets ---

    private fun loadPreset(index: Int) {
        val freq = stationStorage.getPreset(index)
        if (freq > 0) {
            setFrequency(freq)
            if (radioService?.isPlaying != true && rtlSdrDevice != null) startPlayback()
            highlightPreset(index)
        } else {
            showToast(getString(R.string.msg_preset_empty, index + 1))
        }
    }

    private fun savePreset(index: Int) {
        stationStorage.setPreset(index, currentFrequency)
        updatePresetLabels()
        highlightPreset(index)
        showToast(getString(R.string.msg_preset_saved, index + 1, currentFrequency / 1e6))
    }

    private fun updatePresetLabels() {
        presetButtons.forEachIndexed { index, btn ->
            val freq = stationStorage.getPreset(index)
            btn.text = if (freq > 0) String.format("%.1f", freq / 1e6)
            else (index + 1).toString()
        }
    }

    private fun highlightPreset(activeIndex: Int) {
        presetButtons.forEachIndexed { index, btn -> btn.isSelected = index == activeIndex }
    }

    // --- Scan ---

    private fun startScan() {
        val dev = rtlSdrDevice ?: run { showToast(getString(R.string.msg_connect_first)); return }
        stopPlayback()
        scanner = FmScanner(dev)
        layoutScanning.visibility = View.VISIBLE
        btnScan.text = getString(R.string.btn_stop_scan)
        progressScan.progress = 0

        lifecycleScope.launch {
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

    // --- RDS Display ---

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

    // --- AF / TA ---

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

    // --- Station management ---

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

    // --- Helpers ---

    private fun updateFrequencyDisplay(frequencyHz: Long) {
        // Adaptive format: <1000 MHz show 1 decimal, >=1000 MHz show 3 decimals
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

    override fun onDestroy() {
        permissionHelper.unregister()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        rtlSdrDevice?.close()
        super.onDestroy()
    }
}
