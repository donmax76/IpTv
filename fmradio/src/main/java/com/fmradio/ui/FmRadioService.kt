package com.fmradio.ui

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.fmradio.R
import com.fmradio.data.StationStorage
import com.fmradio.dsp.AudioEqualizer
import com.fmradio.dsp.AudioPlayer
import com.fmradio.dsp.FmDemodulator
import com.fmradio.dsp.FmScanner
import com.fmradio.dsp.RdsDecoder
import com.fmradio.rtlsdr.RtlSdrDevice
import kotlinx.coroutines.*

/**
 * Foreground service for FM radio playback with MediaSession support
 * for car steering wheel controls, Bluetooth remotes, and media buttons.
 *
 * Supported media buttons:
 * - PLAY/PAUSE: toggle playback
 * - NEXT: next saved station or seek forward
 * - PREVIOUS: previous saved station or seek backward
 * - STOP: stop playback
 * - FAST_FORWARD: frequency +0.1 MHz
 * - REWIND: frequency -0.1 MHz
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

    // MediaSession for car/remote controls
    private var mediaSession: MediaSessionCompat? = null

    // Station storage for preset navigation
    private lateinit var stationStorage: StationStorage

    @Volatile
    var isPlaying = false
        private set

    var currentFrequency: Long = 100000000L
        private set

    // Current band
    var currentBand: FmScanner.Band = FmScanner.Band.FM_BROADCAST

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
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        stationStorage = StationStorage(this)
        createNotificationChannel()
        initMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle media button intents from car stereo / Bluetooth remote
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    /**
     * Initialize MediaSession for car steering wheel and remote control support.
     * This enables MEDIA_NEXT, MEDIA_PREVIOUS, PLAY/PAUSE from:
     * - Car steering wheel buttons
     * - Bluetooth audio remotes
     * - Headset buttons
     * - Android Auto
     * - Lock screen media controls
     */
    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "FmRadioSession").apply {
            // Declare supported actions
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND
                    )
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f)
                    .build()
            )

            setCallback(object : MediaSessionCompat.Callback() {
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

                /**
                 * NEXT button on car steering wheel / remote:
                 * Switches to next saved station. If no saved stations, seeks forward.
                 */
                override fun onSkipToNext() {
                    Log.i(TAG, "MediaSession: NEXT (steering wheel)")
                    navigateStation(forward = true)
                }

                /**
                 * PREVIOUS button on car steering wheel / remote:
                 * Switches to previous saved station. If no saved stations, seeks backward.
                 */
                override fun onSkipToPrevious() {
                    Log.i(TAG, "MediaSession: PREVIOUS (steering wheel)")
                    navigateStation(forward = false)
                }

                /** FAST_FORWARD: frequency +0.1 MHz */
                override fun onFastForward() {
                    Log.i(TAG, "MediaSession: FAST_FORWARD (+0.1 MHz)")
                    val step = currentBand.stepHz
                    tuneToFrequency(
                        (currentFrequency + step)
                            .coerceAtMost(currentBand.endHz)
                    )
                }

                /** REWIND: frequency -0.1 MHz */
                override fun onRewind() {
                    Log.i(TAG, "MediaSession: REWIND (-0.1 MHz)")
                    val step = currentBand.stepHz
                    tuneToFrequency(
                        (currentFrequency - step)
                            .coerceAtLeast(currentBand.startHz)
                    )
                }
            })

            isActive = true
        }
    }

    /**
     * Navigate to next/previous saved station.
     * Used by car steering wheel NEXT/PREV buttons.
     *
     * Logic:
     * 1. Get sorted list of saved stations
     * 2. Find station closest to current frequency in the given direction
     * 3. If no saved stations, fall back to seek
     */
    private fun navigateStation(forward: Boolean) {
        val stations = stationStorage.loadStations()
            .sortedBy { it.frequencyHz }

        if (stations.isEmpty()) {
            // No saved stations - seek instead
            seekStation(forward)
            return
        }

        val current = currentFrequency
        val next = if (forward) {
            stations.firstOrNull { it.frequencyHz > current + 50000 }
                ?: stations.first() // wrap around
        } else {
            stations.lastOrNull { it.frequencyHz < current - 50000 }
                ?: stations.last() // wrap around
        }

        tuneToFrequency(next.frequencyHz)
        if (!isPlaying && device != null) {
            startPlayback()
        }
        onPlaybackStateChanged?.invoke(isPlaying)
    }

    private fun updateMediaSessionState() {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_STOPPED

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND
                )
                .setState(state, currentFrequency, 1f)
                .build()
        )

        // Update metadata (shows on car display / lock screen)
        val freqText = String.format("%.1f MHz", currentFrequency / 1e6)
        val title = currentRdsData.ps.takeIf { it.isNotBlank() } ?: freqText
        val subtitle = if (currentRdsData.rt.isNotBlank()) currentRdsData.rt else currentBand.displayName

        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "FM Radio RTL-SDR")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, freqText)
                .build()
        )
    }

    fun initDevice(rtlSdrDevice: RtlSdrDevice) {
        this.device = rtlSdrDevice
    }

    fun tuneToFrequency(frequencyHz: Long) {
        currentFrequency = frequencyHz
        device?.setFrequency(frequencyHz)

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

        streamingJob = dev.startStreaming(16384) { iqData ->
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

    private fun checkAfSwitch(rdsData: RdsDecoder.RdsData) {
        // AF list stored in RDS data, actual switching on user request
    }

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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FM Radio")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        // Add media controls to notification (for lock screen & quick access)
        val session = mediaSession
        if (session != null) {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

            // Previous station button
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_skip_previous, "Previous",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                )
            )

            // Play/Pause button
            if (isPlaying) {
                builder.addAction(
                    NotificationCompat.Action(
                        R.drawable.ic_stop, "Pause",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            this, PlaybackStateCompat.ACTION_PAUSE
                        )
                    )
                )
            } else {
                builder.addAction(
                    NotificationCompat.Action(
                        R.drawable.ic_play, "Play",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            this, PlaybackStateCompat.ACTION_PLAY
                        )
                    )
                )
            }

            // Next station button
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_skip_next, "Next",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    )
                )
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
