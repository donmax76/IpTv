package com.fmradio.ui

import android.app.*
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.fmradio.R
import com.fmradio.data.StationStorage
import com.fmradio.dsp.AudioEqualizer
import com.fmradio.dsp.AudioPlayer
import com.fmradio.dsp.FmDemodulator
import com.fmradio.dsp.FmScanner
import com.fmradio.dsp.RdsDecoder
import com.fmradio.rtlsdr.RtlSdrDevice
import kotlinx.coroutines.*

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

    private var mediaSession: MediaSession? = null

    private lateinit var stationStorage: StationStorage

    @Volatile
    var isPlaying = false
        private set

    var currentFrequency: Long = 100000000L
        private set

    var currentBand: FmScanner.Band = FmScanner.Band.FM_BROADCAST

    @Volatile
    var currentRdsData: RdsDecoder.RdsData = RdsDecoder.RdsData()
        private set

    val isStereo: Boolean get() = demodulator?.isStereo == true

    var afEnabled = false
    var taEnabled = false

    var onFrequencyChanged: ((Long) -> Unit)? = null
    var onRdsDataReceived: ((RdsDecoder.RdsData) -> Unit)? = null
    var onStereoChanged: ((Boolean) -> Unit)? = null
    var onSeekComplete: ((Long?) -> Unit)? = null
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        stationStorage = StationStorage(this)
        createNotificationChannel()
        initMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    private fun initMediaSession() {
        mediaSession = MediaSession(this, "FmRadioSession").apply {
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_REWIND
                    )
                    .setState(PlaybackState.STATE_STOPPED, 0, 1f)
                    .build()
            )

            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.i(TAG, "MediaSession: PLAY")
                    if (!isPlaying) startPlayback()
                }

                override fun onPause() {
                    Log.i(TAG, "MediaSession: PAUSE")
                    if (isPlaying) stopPlayback()
                    onPlaybackStateChanged?.invoke(false)
                }

                override fun onStop() {
                    Log.i(TAG, "MediaSession: STOP")
                    if (isPlaying) stopPlayback()
                    onPlaybackStateChanged?.invoke(false)
                }

                override fun onSkipToNext() {
                    Log.i(TAG, "MediaSession: NEXT")
                    navigateStation(forward = true)
                }

                override fun onSkipToPrevious() {
                    Log.i(TAG, "MediaSession: PREVIOUS")
                    navigateStation(forward = false)
                }

                override fun onFastForward() {
                    val step = currentBand.stepHz
                    tuneToFrequency((currentFrequency + step).coerceAtMost(currentBand.endHz))
                }

                override fun onRewind() {
                    val step = currentBand.stepHz
                    tuneToFrequency((currentFrequency - step).coerceAtLeast(currentBand.startHz))
                }
            })

            isActive = true
        }
    }

    private fun navigateStation(forward: Boolean) {
        val stations = stationStorage.loadStations().sortedBy { it.frequencyHz }

        if (stations.isEmpty()) {
            seekStation(forward)
            return
        }

        val current = currentFrequency
        val next = if (forward) {
            stations.firstOrNull { it.frequencyHz > current + 50000 }
                ?: stations.first()
        } else {
            stations.lastOrNull { it.frequencyHz < current - 50000 }
                ?: stations.last()
        }

        tuneToFrequency(next.frequencyHz)
        if (!isPlaying && device != null) {
            startPlayback()
        }
        onPlaybackStateChanged?.invoke(isPlaying)
    }

    private fun updateMediaSessionState() {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED

        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND
                )
                .setState(state, currentFrequency, 1f)
                .build()
        )

        val freqText = String.format("%.1f MHz", currentFrequency / 1e6)
        val title = currentRdsData.ps.takeIf { it.isNotBlank() } ?: freqText
        val subtitle = if (currentRdsData.rt.isNotBlank()) currentRdsData.rt else currentBand.displayName

        mediaSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "FM Radio RTL-SDR")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, freqText)
                .build()
        )
    }

    fun initDevice(rtlSdrDevice: RtlSdrDevice) {
        this.device = rtlSdrDevice
    }

    fun tuneToFrequency(frequencyHz: Long) {
        currentFrequency = frequencyHz
        device?.setFrequency(frequencyHz)

        // Reset DSP state to clear stale filter data from previous frequency
        demodulator?.reset()
        device?.resetBuffer()
        rdsDecoder?.reset()
        currentRdsData = RdsDecoder.RdsData()

        updateMediaSessionState()
        updateNotification()
        onFrequencyChanged?.invoke(frequencyHz)
    }

    fun startPlayback() {
        if (isPlaying) return
        val dev = device ?: return

        val sampleRate = FmDemodulator.RECOMMENDED_SAMPLE_RATE

        demodulator = FmDemodulator(inputSampleRate = sampleRate, audioSampleRate = 48000)

        rdsDecoder = RdsDecoder(sampleRate / 6).also { rds ->
            rds.listener = object : RdsDecoder.RdsListener {
                override fun onRdsData(data: RdsDecoder.RdsData) {
                    currentRdsData = data
                    onRdsDataReceived?.invoke(data)
                    if (data.ps.isNotBlank()) {
                        updateMediaSessionState()
                        updateNotification()
                    }
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
        var lastStereo = false

        streamingJob = dev.startStreaming(65536) { iqData ->
            var audioSamples = demodulator?.demodulate(iqData)
            if (audioSamples != null && audioSamples.isNotEmpty()) {
                val eq = equalizer
                if (eq != null) audioSamples = eq.process(audioSamples)
                audioPlayer?.writeSamples(audioSamples)
            }
            val stereoNow = demodulator?.isStereo == true
            if (stereoNow != lastStereo) {
                lastStereo = stereoNow
                onStereoChanged?.invoke(stereoNow)
            }
        }

        updateMediaSessionState()
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
        updateMediaSessionState()
        updateNotification()
        Log.i(TAG, "Playback stopped")
    }

    fun setVolume(volume: Float) { audioPlayer?.setVolume(volume) }

    fun setBass(level: Int) {
        equalizer?.bassGainDb = (level - 10).toFloat()
    }

    fun setTreble(level: Int) {
        equalizer?.trebleGainDb = (level - 10).toFloat()
    }

    fun seekStation(forward: Boolean) {
        val dev = device ?: return
        val wasPlaying = isPlaying
        if (wasPlaying) stopPlayback()

        serviceScope.launch {
            try {
                val tempDemod = FmDemodulator()
                dev.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
                dev.setAutoGain(true)

                val step = currentBand.stepHz
                var freq = currentFrequency + if (forward) step else -step
                var found: Long? = null

                val maxSteps = ((currentBand.endHz - currentBand.startHz) / step).toInt()

                for (i in 0 until maxSteps) {
                    if (freq > currentBand.endHz) freq = currentBand.startHz
                    if (freq < currentBand.startHz) freq = currentBand.endHz

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
                    if (wasPlaying) startPlayback()
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

    private fun checkAfSwitch(rdsData: RdsDecoder.RdsData) {}

    fun switchToAf(freqMHz: Float) {
        tuneToFrequency((freqMHz * 1_000_000).toLong())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "FM Radio Playback", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "FM Radio playback notification" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val freqText = String.format("%.1f MHz", currentFrequency / 1e6)
        val rdsName = currentRdsData.ps.takeIf { it.isNotBlank() }
        val stereoText = if (isStereo) " [ST]" else ""
        val statusText = when {
            !isPlaying -> "FM Radio"
            rdsName != null -> "$rdsName — $freqText$stereoText"
            else -> "$freqText$stereoText"
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("FM Radio")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        val session = mediaSession
        if (session != null) {
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        return builder.build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    override fun onDestroy() {
        stopPlayback()
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
