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

    // UI elements
    private lateinit var tvFrequency: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var btnPlayStop: ImageButton
    private lateinit var btnScan: Button
    private lateinit var btnFreqDown: ImageButton
    private lateinit var btnFreqUp: ImageButton
    private lateinit var btnConnect: Button
    private lateinit var seekVolume: SeekBar
    private lateinit var seekFrequency: SeekBar
    private lateinit var rvStations: RecyclerView
    private lateinit var progressScan: ProgressBar
    private lateinit var tvScanStatus: TextView
    private lateinit var layoutScanning: View

    private lateinit var stationAdapter: StationAdapter

    private var currentFrequency: Long = 100000000L // 100.0 MHz

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as FmRadioService.LocalBinder
            radioService = binder.getService()
            serviceBound = true
            radioService?.onFrequencyChanged = { freq ->
                runOnUiThread { updateFrequencyDisplay(freq) }
            }
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

        // Restore last frequency
        currentFrequency = stationStorage.lastFrequency
        updateFrequencyDisplay(currentFrequency)

        // Start foreground service
        startRadioService()

        // Auto-connect if device is attached via intent
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            connectDevice()
        }
    }

    private fun initViews() {
        tvFrequency = findViewById(R.id.tvFrequency)
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        btnPlayStop = findViewById(R.id.btnPlayStop)
        btnScan = findViewById(R.id.btnScan)
        btnFreqDown = findViewById(R.id.btnFreqDown)
        btnFreqUp = findViewById(R.id.btnFreqUp)
        btnConnect = findViewById(R.id.btnConnect)
        seekVolume = findViewById(R.id.seekVolume)
        seekFrequency = findViewById(R.id.seekFrequency)
        rvStations = findViewById(R.id.rvStations)
        progressScan = findViewById(R.id.progressScan)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        layoutScanning = findViewById(R.id.layoutScanning)

        // Frequency seekbar: 87.5 - 108.0 MHz in 100 kHz steps
        seekFrequency.max = 205 // (108.0 - 87.5) / 0.1 = 205 steps
        seekFrequency.progress = frequencyToProgress(currentFrequency)

        // Volume seekbar
        seekVolume.max = 100
        seekVolume.progress = (stationStorage.lastVolume * 100).toInt()

        // Station list
        stationAdapter = StationAdapter(
            stations = emptyList(),
            onStationClick = { station -> tuneToStation(station) },
            onFavoriteClick = { station -> toggleFavorite(station) },
            onLongClick = { station -> showStationOptions(station) }
        )
        rvStations.layoutManager = LinearLayoutManager(this)
        rvStations.adapter = stationAdapter

        layoutScanning.visibility = View.GONE
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener { connectDevice() }

        btnPlayStop.setOnClickListener {
            if (radioService?.isPlaying == true) {
                stopPlayback()
            } else {
                startPlayback()
            }
        }

        btnFreqDown.setOnClickListener {
            setFrequency(currentFrequency - 100000) // -100 kHz
        }

        btnFreqUp.setOnClickListener {
            setFrequency(currentFrequency + 100000) // +100 kHz
        }

        btnScan.setOnClickListener {
            if (scanner?.isScanning() == true) {
                scanner?.stopScan()
            } else {
                startScan()
            }
        }

        seekFrequency.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val freq = progressToFrequency(progress)
                    updateFrequencyDisplay(freq)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val freq = progressToFrequency(seekBar.progress)
                setFrequency(freq)
            }
        })

        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val volume = progress / 100f
                    radioService?.setVolume(volume)
                    stationStorage.lastVolume = volume
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
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
            if (granted) {
                openDevice(device)
            } else {
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
            tvDeviceInfo.text = getString(
                R.string.device_info_format,
                dev.getTunerType().name,
                usbDevice.deviceName
            )
            btnConnect.text = getString(R.string.btn_disconnect)
            btnPlayStop.isEnabled = true
            btnScan.isEnabled = true

            showToast(getString(R.string.msg_device_connected))
        } else {
            tvStatus.text = getString(R.string.status_connection_failed)
            showToast(getString(R.string.msg_connection_failed))
        }
    }

    private fun startPlayback() {
        val service = radioService ?: return
        if (rtlSdrDevice == null) {
            showToast(getString(R.string.msg_connect_first))
            return
        }

        service.tuneToFrequency(currentFrequency)
        service.startPlayback()
        service.setVolume(seekVolume.progress / 100f)
        btnPlayStop.setImageResource(R.drawable.ic_stop)
        tvStatus.text = getString(R.string.status_playing)
    }

    private fun stopPlayback() {
        radioService?.stopPlayback()
        btnPlayStop.setImageResource(R.drawable.ic_play)
        tvStatus.text = getString(R.string.status_stopped)
    }

    private fun setFrequency(frequencyHz: Long) {
        val freq = frequencyHz.coerceIn(
            FmScanner.FM_BAND_START,
            FmScanner.FM_BAND_END
        )
        currentFrequency = freq
        stationStorage.lastFrequency = freq
        updateFrequencyDisplay(freq)
        seekFrequency.progress = frequencyToProgress(freq)

        if (radioService?.isPlaying == true) {
            radioService?.tuneToFrequency(freq)
        }

        stationAdapter.setSelectedFrequency(freq)
    }

    private fun tuneToStation(station: RadioStation) {
        setFrequency(station.frequencyHz)
        if (radioService?.isPlaying != true) {
            startPlayback()
        }
    }

    private fun startScan() {
        val dev = rtlSdrDevice
        if (dev == null) {
            showToast(getString(R.string.msg_connect_first))
            return
        }

        // Stop playback during scan
        stopPlayback()

        scanner = FmScanner(dev)
        layoutScanning.visibility = View.VISIBLE
        btnScan.text = getString(R.string.btn_stop_scan)
        progressScan.progress = 0

        lifecycleScope.launch {
            scanner?.scan(object : FmScanner.ScanListener {
                override fun onScanProgress(currentFreqHz: Long, progress: Float) {
                    progressScan.progress = (progress * 100).toInt()
                    tvScanStatus.text = getString(
                        R.string.scan_progress_format,
                        currentFreqHz / 1e6,
                        (progress * 100).toInt()
                    )
                }

                override fun onStationFound(result: FmScanner.ScanResult) {
                    val station = RadioStation(
                        frequencyHz = result.frequencyHz,
                        signalStrength = result.signalStrength
                    )
                    stationStorage.addStation(station)
                    loadSavedStations()
                }

                override fun onScanComplete(stations: List<FmScanner.ScanResult>) {
                    layoutScanning.visibility = View.GONE
                    btnScan.text = getString(R.string.btn_scan)
                    showToast(getString(R.string.msg_scan_complete, stations.size))
                }

                override fun onScanError(error: String) {
                    layoutScanning.visibility = View.GONE
                    btnScan.text = getString(R.string.btn_scan)
                    showToast(getString(R.string.msg_scan_error, error))
                }
            })
        }
    }

    private fun toggleFavorite(station: RadioStation) {
        stationStorage.toggleFavorite(station.frequencyHz)
        loadSavedStations()
    }

    private fun showStationOptions(station: RadioStation) {
        val options = arrayOf(
            getString(R.string.option_rename),
            getString(R.string.option_delete),
            getString(R.string.option_toggle_favorite)
        )

        AlertDialog.Builder(this)
            .setTitle(station.displayName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(station)
                    1 -> {
                        stationStorage.removeStation(station.frequencyHz)
                        loadSavedStations()
                    }
                    2 -> toggleFavorite(station)
                }
            }
            .show()
    }

    private fun showRenameDialog(station: RadioStation) {
        val editText = EditText(this).apply {
            setText(station.name)
            hint = getString(R.string.hint_station_name)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_rename_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    stationStorage.renameStation(station.frequencyHz, newName)
                    loadSavedStations()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun loadSavedStations() {
        val stations = stationStorage.loadStations()
        stationAdapter.updateStations(stations)
        stationAdapter.setSelectedFrequency(currentFrequency)
    }

    private fun updateFrequencyDisplay(frequencyHz: Long) {
        tvFrequency.text = String.format("%.1f", frequencyHz / 1_000_000.0)
    }

    private fun frequencyToProgress(freq: Long): Int {
        return ((freq - FmScanner.FM_BAND_START) / 100000).toInt()
    }

    private fun progressToFrequency(progress: Int): Long {
        return FmScanner.FM_BAND_START + progress * 100000L
    }

    private fun startRadioService() {
        val intent = Intent(this, FmRadioService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        permissionHelper.unregister()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        rtlSdrDevice?.close()
        super.onDestroy()
    }
}
