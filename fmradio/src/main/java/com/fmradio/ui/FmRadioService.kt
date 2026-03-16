package com.fmradio.ui

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fmradio.R
import com.fmradio.dsp.AudioPlayer
import com.fmradio.dsp.FmDemodulator
import com.fmradio.dsp.RdsDecoder
import com.fmradio.rtlsdr.RtlSdrDevice
import kotlinx.coroutines.*

/**
 * Foreground service for continuous FM radio playback with RDS support.
 */
class FmRadioService : Service() {

    companion object {
        private const val TAG = "FmRadioService"
        private const val CHANNEL_ID = "fm_radio_playback"
        private const val NOTIFICATION_ID = 1001
    }

    inner class LocalBinder : Binder() {
        fun getService(): FmRadioService = this@FmRadioService
    }

    private val binder = LocalBinder()
    private var device: RtlSdrDevice? = null
    private var demodulator: FmDemodulator? = null
    private var audioPlayer: AudioPlayer? = null
    private var rdsDecoder: RdsDecoder? = null
    private var streamingJob: Job? = null

    @Volatile
    var isPlaying = false
        private set

    var currentFrequency: Long = 100000000L
        private set

    // RDS data
    @Volatile
    var currentRdsData: RdsDecoder.RdsData = RdsDecoder.RdsData()
        private set

    var onFrequencyChanged: ((Long) -> Unit)? = null
    var onRdsDataReceived: ((RdsDecoder.RdsData) -> Unit)? = null

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

        // Create demodulator with recommended sample rate
        demodulator = FmDemodulator(
            inputSampleRate = sampleRate,
            audioSampleRate = 48000
        )

        // Create RDS decoder at intermediate rate (192 kHz)
        rdsDecoder = RdsDecoder(sampleRate / 6).also { rds ->
            rds.listener = object : RdsDecoder.RdsListener {
                override fun onRdsData(data: RdsDecoder.RdsData) {
                    currentRdsData = data
                    onRdsDataReceived?.invoke(data)
                    if (data.ps.isNotBlank()) {
                        updateNotification()
                    }
                }
            }
        }

        // Connect demodulator wideband output to RDS decoder
        demodulator?.widebandListener = { widebandSamples ->
            rdsDecoder?.process(widebandSamples)
        }

        audioPlayer = AudioPlayer(48000).also { it.start() }

        dev.setSampleRate(sampleRate)
        dev.setAutoGain(true)
        dev.setFrequency(currentFrequency)

        isPlaying = true

        streamingJob = dev.startStreaming(16384) { iqData ->
            val audioSamples = demodulator?.demodulate(iqData)
            if (audioSamples != null && audioSamples.isNotEmpty()) {
                audioPlayer?.writeSamples(audioSamples)
            }
        }

        updateNotification()
        Log.i(TAG, "Playback started at ${currentFrequency / 1e6} MHz (${sampleRate}Hz, RDS enabled)")
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
        currentRdsData = RdsDecoder.RdsData()
        updateNotification()
        Log.i(TAG, "Playback stopped")
    }

    fun setVolume(volume: Float) {
        audioPlayer?.setVolume(volume)
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
        val statusText = when {
            !isPlaying -> "FM Radio"
            rdsName != null -> "$rdsName — $freqText"
            else -> "Playing $freqText"
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(""))
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }
}
