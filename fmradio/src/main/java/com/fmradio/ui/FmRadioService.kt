package com.fmradio.ui

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fmradio.R
import com.fmradio.dsp.AudioEqualizer
import com.fmradio.dsp.AudioPlayer
import com.fmradio.dsp.FmDemodulator
import com.fmradio.dsp.FmScanner
import com.fmradio.dsp.RdsDecoder
import com.fmradio.rtlsdr.RtlSdrDevice
import kotlinx.coroutines.*

/**
 * Foreground service for FM radio playback with RDS, EQ, seek, and stereo support.
 */
class FmRadioService : Service() {

    companion object {
        private const val TAG = "FmRadioService"
        private const val CHANNEL_ID = "fm_radio_playback"
        private const val NOTIFICATION_ID = 1001
        private const val SEEK_THRESHOLD = -15f
    }

    inner class LocalBinder : Binder() {
        fun getService(): FmRadioService = this@FmRadioService
    }

    private val binder = LocalBinder()
    private var device: RtlSdrDevice? = null
    private var demodulator: FmDemodulator? = null
    private var audioPlayer: AudioPlayer? = null
    private var rdsDecoder: RdsDecoder? = null
    private var equalizer: AudioEqualizer? = null
    private var streamingJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isPlaying = false
        private set

    var currentFrequency: Long = 100000000L
        private set

    // RDS data
    @Volatile
    var currentRdsData: RdsDecoder.RdsData = RdsDecoder.RdsData()
        private set

    // Stereo status
    val isStereo: Boolean get() = demodulator?.isStereo == true

    // AF/TA modes
    var afEnabled = false
    var taEnabled = false

    // Callbacks
    var onFrequencyChanged: ((Long) -> Unit)? = null
    var onRdsDataReceived: ((RdsDecoder.RdsData) -> Unit)? = null
    var onStereoChanged: ((Boolean) -> Unit)? = null
    var onSeekComplete: ((Long?) -> Unit)? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("FM Radio"))
        return START_STICKY
    }

    fun initDevice(rtlSdrDevice: RtlSdrDevice) {
        this.device = rtlSdrDevice
    }

    fun tuneToFrequency(frequencyHz: Long) {
        currentFrequency = frequencyHz
        device?.setFrequency(frequencyHz)

        // Reset RDS on frequency change
        rdsDecoder?.reset()
        currentRdsData = RdsDecoder.RdsData()

        updateNotification()
        onFrequencyChanged?.invoke(frequencyHz)
    }

    fun startPlayback() {
        if (isPlaying) return
        val dev = device ?: return

        val sampleRate = FmDemodulator.RECOMMENDED_SAMPLE_RATE

        demodulator = FmDemodulator(
            inputSampleRate = sampleRate,
            audioSampleRate = 48000
        )

        rdsDecoder = RdsDecoder(sampleRate / 6).also { rds ->
            rds.listener = object : RdsDecoder.RdsListener {
                override fun onRdsData(data: RdsDecoder.RdsData) {
                    currentRdsData = data
                    onRdsDataReceived?.invoke(data)
                    if (data.ps.isNotBlank()) {
                        updateNotification()
                    }
                    // AF: auto-switch if signal gets weak
                    if (afEnabled && data.afList.isNotEmpty()) {
                        checkAfSwitch(data)
                    }
                }
            }
        }

        demodulator?.widebandListener = { widebandSamples ->
            rdsDecoder?.process(widebandSamples)
        }

        equalizer = AudioEqualizer(48000)
        audioPlayer = AudioPlayer(48000).also { it.start() }

        dev.setSampleRate(sampleRate)
        dev.setAutoGain(true)
        dev.setFrequency(currentFrequency)

        isPlaying = true

        // Track stereo changes
        var lastStereo = false

        streamingJob = dev.startStreaming(16384) { iqData ->
            var audioSamples = demodulator?.demodulate(iqData)
            if (audioSamples != null && audioSamples.isNotEmpty()) {
                // Apply EQ
                val eq = equalizer
                if (eq != null) {
                    audioSamples = eq.process(audioSamples)
                }
                audioPlayer?.writeSamples(audioSamples)
            }

            // Check stereo status change
            val stereoNow = demodulator?.isStereo == true
            if (stereoNow != lastStereo) {
                lastStereo = stereoNow
                onStereoChanged?.invoke(stereoNow)
            }
        }

        updateNotification()
        Log.i(TAG, "Playback started at ${currentFrequency / 1e6} MHz")
    }

    fun stopPlayback() {
        isPlaying = false
        device?.stopStreaming()
        streamingJob?.cancel()
        streamingJob = null
        audioPlayer?.stop()
        audioPlayer = null
        demodulator?.widebandListener = null
        demodulator?.reset()
        demodulator = null
        rdsDecoder?.reset()
        rdsDecoder = null
        equalizer?.reset()
        equalizer = null
        currentRdsData = RdsDecoder.RdsData()
        updateNotification()
        Log.i(TAG, "Playback stopped")
    }

    fun setVolume(volume: Float) {
        audioPlayer?.setVolume(volume)
    }

    fun setBass(level: Int) {
        // level: 0-20, center=10
        val gainDb = (level - 10).toFloat()
        equalizer?.bassGainDb = gainDb
    }

    fun setTreble(level: Int) {
        val gainDb = (level - 10).toFloat()
        equalizer?.trebleGainDb = gainDb
    }

    /**
     * Seek to next/previous station with signal above threshold.
     * Stops current playback, scans in direction, tunes to found station.
     */
    fun seekStation(forward: Boolean) {
        val dev = device ?: return
        val wasPlaying = isPlaying
        if (wasPlaying) stopPlayback()

        serviceScope.launch {
            try {
                val tempDemod = FmDemodulator()
                dev.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
                dev.setAutoGain(true)

                val step = 100000L  // 100 kHz
                var freq = currentFrequency + if (forward) step else -step
                var found: Long? = null

                // Search up to full band
                val maxSteps = ((FmScanner.FM_BAND_END - FmScanner.FM_BAND_START) / step).toInt()

                for (i in 0 until maxSteps) {
                    // Wrap around
                    if (freq > FmScanner.FM_BAND_END) freq = FmScanner.FM_BAND_START
                    if (freq < FmScanner.FM_BAND_START) freq = FmScanner.FM_BAND_END

                    dev.setFrequency(freq)
                    delay(60)
                    dev.resetBuffer()

                    val samples = dev.readSamples(65536)
                    if (samples != null) {
                        val power = tempDemod.measureSignalStrength(samples)
                        if (power > SEEK_THRESHOLD) {
                            found = freq
                            break
                        }
                    }

                    freq += if (forward) step else -step
                }

                withContext(Dispatchers.Main) {
                    if (found != null) {
                        currentFrequency = found
                        onFrequencyChanged?.invoke(found)
                    }
                    onSeekComplete?.invoke(found)

                    if (wasPlaying) {
                        startPlayback()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Seek error", e)
                withContext(Dispatchers.Main) {
                    onSeekComplete?.invoke(null)
                    if (wasPlaying) startPlayback()
                }
            }
        }
    }

    /**
     * Check if we should switch to an alternative frequency (AF).
     * If current signal is weak and an AF has better signal, switch.
     */
    private fun checkAfSwitch(rdsData: RdsDecoder.RdsData) {
        // Simple AF: just store the list, actual switching done on user request
        // Full auto-switch would require measuring AF signals periodically
    }

    /** Switch to a specific AF frequency */
    fun switchToAf(freqMHz: Float) {
        val freqHz = (freqMHz * 1_000_000).toLong()
        tuneToFrequency(freqHz)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FM Radio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "FM Radio playback notification"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val freqText = String.format("%.1f FM", currentFrequency / 1e6)
        val rdsName = currentRdsData.ps.takeIf { it.isNotBlank() }
        val stereoText = if (isStereo) " [ST]" else ""
        val statusText = when {
            !isPlaying -> "FM Radio"
            rdsName != null -> "$rdsName — $freqText$stereoText"
            else -> "$freqText$stereoText"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FM Radio")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, createNotification(""))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    override fun onDestroy() {
        stopPlayback()
        serviceScope.cancel()
        super.onDestroy()
    }
}
