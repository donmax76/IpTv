package com.fmradio.ui

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import com.fmradio.dsp.DebugLog
import com.fmradio.dsp.FmDemodulator
import com.fmradio.dsp.FmScanner
import com.fmradio.dsp.NativeFmDsp
import com.fmradio.dsp.RdsDecoder
import com.fmradio.rtlsdr.RtlSdrDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.Executors

class FmRadioService : Service() {

    companion object {
        private const val TAG = "FmRadioService"
        private const val CHANNEL_ID = "fm_radio_playback"
        private const val NOTIFICATION_ID = 1001
        private const val SEEK_THRESHOLD = -15f
        // Smaller USB buffer = more frequent callbacks = lower latency
        private const val USB_BUFFER_SIZE = 32768
        // IQ data queue depth. Each buffer is 32 KB = ~14 ms of IQ at 1.152 Msps.
        // 64 × 14 ms ≈ 900 ms of headroom. On BYD DiLink the rear camera app
        // causes ~500-1000 ms of thread starvation — this buffer absorbs it.
        private const val IQ_QUEUE_DEPTH = 64
    }

    // Dedicated single-thread dispatcher for USB streaming.
    // IMPORTANT: Process.setThreadPriority() without a TID argument sets the
    // CALLING thread's priority. A ThreadFactory lambda runs on whoever calls
    // execute() first (usually the main thread), so we must set the Android
    // priority from INSIDE the worker thread body, not in the factory. Without
    // this, the new worker runs at default background priority and drifts onto
    // LITTLE cores when the app loses UI focus → audio stutters.
    private val usbDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_AUDIO
                )
            } catch (_: Throwable) {}
            r.run()
        }, "FmUsbStream").apply {
            priority = Thread.MAX_PRIORITY - 1
        }
    }.asCoroutineDispatcher()

    // Dedicated single-thread dispatcher for DSP processing.
    // Same wrapping trick as usbDispatcher — setThreadPriority must be called
    // from inside the worker body so the foreground audio scheduling class
    // sticks to the DSP thread, not to whoever launched the service.
    private val dspDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
                )
            } catch (_: Throwable) {}
            r.run()
        }, "FmDspProcess").apply {
            priority = Thread.MAX_PRIORITY
        }
    }.asCoroutineDispatcher()

    // Dedicated single-thread dispatcher for RDS decoding (separate from DSP)
    private val rdsDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_AUDIO
                )
            } catch (_: Throwable) {}
            r.run()
        }, "FmRdsDecoder").apply {
            priority = Thread.MAX_PRIORITY - 2
        }
    }.asCoroutineDispatcher()

    inner class LocalBinder : Binder() {
        fun getService(): FmRadioService = this@FmRadioService
    }

    private val binder = LocalBinder()
    private var device: RtlSdrDevice? = null
    private var demodulator: FmDemodulator? = null
    private var nativeDsp: NativeFmDsp? = null
    private var audioPlayer: AudioPlayer? = null
    private var rdsDecoder: RdsDecoder? = null
    private var equalizer: AudioEqualizer? = null
    private var streamingJob: Job? = null
    private var dspJob: Job? = null
    private var seekJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private lateinit var stationStorage: StationStorage

    @Volatile
    var isPlaying = false
        private set

    @Volatile
    var currentFrequency: Long = 100000000L
        private set

    // Raw DSP thread reference so stopPlayback can join it (prevents a new
    // dspThread starting while the old one still touches the shared native
    // g_dsp state → filter corruption / distortion).
    @Volatile
    private var dspThread: Thread? = null

    // Cross-thread native-DSP reset request. tuneToFrequency sets this; the DSP
    // thread performs the actual reset() at a safe point in its loop, so we never
    // memset filter buffers from the main thread while the DSP thread reads them.
    @Volatile
    private var pendingDspReset = false

    @Volatile
    private var rdsGeneration = 0  // increments on freq change, RDS thread checks this

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
    var onSignalStrengthChanged: ((Float) -> Unit)? = null
    var onAudioData: ((ShortArray, Int) -> Unit)? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        stationStorage = StationStorage(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Keep CPU running when app is in background — critical for audio playback
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "FmRadio::Playback")
        createNotificationChannel()
        initMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed (missing permission?), continuing anyway", e)
        }
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
        // Priority: RDS PS → user-entered station name → frequency
        val stationName = stationStorage.loadStations()
            .find { Math.abs(it.frequencyHz - currentFrequency) < 50000 }
            ?.name?.takeIf { it.isNotBlank() }
        val title = currentRdsData.ps.takeIf { it.isNotBlank() }
            ?: stationName
            ?: freqText
        val subtitle = if (currentRdsData.rt.isNotBlank()) currentRdsData.rt else freqText

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

        rdsGeneration++  // invalidate any pending RDS data in queue

        if (isPlaying) {
            serviceScope.launch {
                device?.setFrequency(frequencyHz)
                delay(60)
                device?.resetBuffer()
            }
            // Ask the DSP thread to reset native filters + flush audio at a safe
            // point. Doing it here (main thread) would memset native buffers while
            // the DSP thread reads them → NaN/distortion on channel change.
            pendingDspReset = true
        } else {
            // Not playing — no DSP thread running, safe to reset directly.
            demodulator?.reset()
            nativeDsp?.reset()
        }

        rdsDecoder?.reset()
        currentRdsData = RdsDecoder.RdsData()

        updateMediaSessionState()
        updateNotification()
        onFrequencyChanged?.invoke(frequencyHz)
    }

    fun startPlayback() {
        if (isPlaying) return
        val dev = device ?: return

        // Acquire wake lock to prevent CPU throttling in background
        try { wakeLock?.acquire(4 * 60 * 60 * 1000L) } catch (_: Exception) {}

        cancelSeek()

        val sampleRate = FmDemodulator.RECOMMENDED_SAMPLE_RATE

        // Try native C++ DSP first (zero jitter), fall back to Kotlin.
        // Wrap in try/catch so a missing JNI symbol on a stale build doesn't
        // crash startup — we just fall back to the Kotlin demodulator.
        nativeDsp = if (NativeFmDsp.available) {
            try {
                NativeFmDsp().also { it.init() }
            } catch (e: Throwable) {
                Log.e(TAG, "Native DSP init failed, falling back to Kotlin", e)
                DebugLog.log("SVC", "Native DSP init failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        } else null
        if (nativeDsp != null) {
            DebugLog.log("SVC", "Using NATIVE C++ DSP (zero-jitter)")
            // Re-apply any test flags the user set before playback started
            try { nativeDsp?.setTestFlags(testFlags) } catch (_: Throwable) {}
        } else {
            DebugLog.log("SVC", "Using Kotlin DSP (native not available)")
        }

        demodulator = FmDemodulator(inputSampleRate = sampleRate, audioSampleRate = 48000)

        rdsDecoder = RdsDecoder(192000).also { rds ->  // intermediate rate is fixed at 192 kHz
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

        // Wideband listener sends RDS data to separate thread — never blocks DSP
        // Use pre-allocated holders to avoid GC pressure (zero allocation in DSP thread)
        class RdsPacket(val samples: FloatArray, var count: Int = 0, var phase: Double = 0.0, var gen: Int = 0)
        // 4 packet objects — one per channel slot. Previous bug: 2 objects
        // with capacity 4 → when RDS thread was slow, same packet was in
        // the channel AND being overwritten → corrupted wideband data → 95% BER!
        val rdsPackets = Array(4) { RdsPacket(FloatArray(6000)) }
        var rdsPacketIdx = 0
        val rdsChannel = Channel<RdsPacket>(4)

        demodulator?.widebandListener = { widebandSamples, count, pilotPhase ->
            if (count > 0 && count <= 6000) {
                val pkt = rdsPackets[rdsPacketIdx]
                rdsPacketIdx = (rdsPacketIdx + 1) % 4
                System.arraycopy(widebandSamples, 0, pkt.samples, 0, count)
                pkt.count = count
                pkt.phase = pilotPhase
                pkt.gen = rdsGeneration
                rdsChannel.trySend(pkt)
            }
        }

        // RDS decoder runs in its own thread — no impact on audio
        serviceScope.launch(rdsDispatcher) {
            for (pkt in rdsChannel) {
                if (pkt.gen != rdsGeneration) continue
                val rds = rdsDecoder ?: continue
                // Pass pkt.count — the number of valid samples actually written,
                // NOT samples.size (which is the full 6000-float buffer). Using
                // samples.size meant the decoder processed stale data after the
                // valid region, which corrupted the BPSK clock recovery and
                // prevented block sync from ever holding — effectively killing
                // RDS completely.
                rds.process(pkt.samples, pkt.count, pkt.phase)
            }
        }

        requestAudioFocus()
        DebugLog.log("SVC", "Audio focus requested")

        equalizer = AudioEqualizer(48000)
        audioPlayer = AudioPlayer(48000).also { it.start() }
        DebugLog.log("SVC", "AudioPlayer created & started")

        isPlaying = true
        var lastStereo = false
        var lastSignalDb = -100f
        var signalUpdateCounter = 0

        // ===== Direct Thread Pipeline (no coroutine overhead) =====
        // Previous Kotlin Channel + coroutine approach lost 12.5% of USB packets
        // due to coroutine suspension/resumption latency on Xiaomi MIUI.
        // Plain ConcurrentLinkedQueue + Thread eliminates this overhead.
        val iqQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

        // Producer: USB setup + read loop on dedicated USB thread
        streamingJob = serviceScope.launch(usbDispatcher) {
            dev.setSampleRate(sampleRate)
            dev.setAutoGain(true)
            dev.fullReset()
            dev.setFrequency(currentFrequency)
            Thread.sleep(50)

            Log.i(TAG, "USB setup done, starting streaming...")
            DebugLog.log("SVC", "USB setup done: rate=$sampleRate freq=${currentFrequency/1e6}MHz buf=$USB_BUFFER_SIZE")

            val innerJob = dev.startStreaming(USB_BUFFER_SIZE) { iqData ->
                if (iqQueue.size < IQ_QUEUE_DEPTH) {
                    iqQueue.add(iqData)
                } else {
                    Log.w("FmRadio", "IQ queue full (${iqQueue.size}), dropped ${iqData.size}B")
                    DebugLog.log("USB", "IQ queue full, dropped ${iqData.size}B")
                }
            }
            innerJob.join()
        }

        // Consumer: DSP processing on dedicated plain Thread (NO coroutine)
        val ndsp = nativeDsp
        val audioBuf = ShortArray(2800 * 2)
        var demodCallCount = 0L
        var totalAudioSamples = 0L
        var lastDemodLog = System.currentTimeMillis()

        val thread = Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {}

            while (isPlaying) {
                // Perform a requested DSP reset HERE (on the DSP thread) rather than
                // letting tuneToFrequency memset native filter buffers from the main
                // thread under our feet → avoids NaN bursts / distortion on freq change.
                if (pendingDspReset) {
                    pendingDspReset = false
                    ndsp?.reset()
                    demodulator?.reset()
                    iqQueue.clear()
                    audioPlayer?.flush()
                }

                val iqData = iqQueue.poll()
                if (iqData == null) {
                    java.util.concurrent.locks.LockSupport.parkNanos(100_000L) // 100µs
                    continue
                }

                val audioSamples: ShortArray
                val audioCount: Int
                if (ndsp != null) {
                    val nr = ndsp.process(iqData)
                    audioSamples = nr.samples
                    audioCount = nr.count
                    val wbListener = demodulator?.widebandListener
                    val wbCount = ndsp.getWbCount()
                    if (wbCount > 0) {
                        rdsDecoder?.setPilotFreq(ndsp.getPilotFreq())
                        wbListener?.invoke(ndsp.getWbBuffer(), wbCount, ndsp.getPilotPhase())
                    }
                } else {
                    audioCount = demodulator?.demodulate(iqData, audioBuf) ?: 0
                    audioSamples = audioBuf
                }
                demodCallCount++

                if (audioCount > 0) {
                    totalAudioSamples += audioCount
                    equalizer?.process(audioSamples, audioCount)
                    audioPlayer?.writeSamples(audioSamples, audioCount)
                    if (demodCallCount % 3 == 0L) {
                        onAudioData?.invoke(audioSamples, audioCount)
                    }
                }

                val now = System.currentTimeMillis()
                if (DebugLog.fileLoggingEnabled && (demodCallCount <= 3 || now - lastDemodLog > 1000)) {
                    val sigDb = ndsp?.getSignalDb() ?: demodulator?.currentSignalStrengthDb ?: -100f
                    val stereo = ndsp?.getIsStereo() ?: (demodulator?.isStereo == true)
                    val wbCount = ndsp?.getWbCount() ?: 0
                    val pilotPhase = ndsp?.getPilotPhase() ?: 0.0
                    val pilotFreq = ndsp?.getPilotFreq() ?: 0.0
                    DebugLog.log("DSP", "sig=${String.format("%.1f", sigDb)}dB stereo=$stereo wb=$wbCount q=${iqQueue.size} pilotFr=${String.format("%.6f", pilotFreq)} freq=${currentFrequency/1e6}MHz demod#=$demodCallCount [NATIVE]")
                    lastDemodLog = now
                }

                val stereoNow = ndsp?.getIsStereo() ?: (demodulator?.isStereo == true)
                if (stereoNow != lastStereo) {
                    lastStereo = stereoNow
                    onStereoChanged?.invoke(stereoNow)
                }

                signalUpdateCounter++
                if (signalUpdateCounter >= 4) {
                    signalUpdateCounter = 0
                    val db = ndsp?.getSignalDb() ?: demodulator?.currentSignalStrengthDb ?: -100f
                    if (kotlin.math.abs(db - lastSignalDb) > 0.5f) {
                        lastSignalDb = db
                        onSignalStrengthChanged?.invoke(db)
                    }
                }
            }
        }, "FmDspDirect")
        thread.priority = Thread.MAX_PRIORITY
        dspThread = thread
        thread.start()

        updateMediaSessionState()
        updateNotification()
        Log.i(TAG, "Playback started at ${currentFrequency / 1e6} MHz")
    }

    fun stopPlayback() {
        isPlaying = false

        // Release wake lock
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}

        cancelSeek()

        device?.stopStreaming()

        val sJob = streamingJob
        val dJob = dspJob
        streamingJob = null
        dspJob = null
        sJob?.cancel()
        dJob?.cancel()

        // Join the raw DSP thread before tearing down resources, so a new
        // playback session can't start a second thread against the shared
        // native g_dsp state (causes filter corruption / distortion).
        val t = dspThread
        dspThread = null
        if (t != null && t != Thread.currentThread()) {
            try { t.join(1500) } catch (_: InterruptedException) {}
        }

        demodulator?.widebandListener = null
        val oldDemod = demodulator
        demodulator = null
        val oldRds = rdsDecoder
        rdsDecoder = null
        val oldEq = equalizer
        equalizer = null

        audioPlayer?.stop()
        audioPlayer = null

        abandonAudioFocus()

        oldDemod?.reset()
        oldRds?.reset()
        oldEq?.reset()
        currentRdsData = RdsDecoder.RdsData()
        updateMediaSessionState()
        updateNotification()
        Log.i(TAG, "Playback stopped")
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                // Without this flag the SYSTEM auto-ducks our stream whenever
                // another app takes transient-may-duck focus (the launcher does
                // it on minimize on BYD DiLink) — the listener below never even
                // runs for ducking, so "ignore focus changes" alone didn't help.
                // willPauseWhenDucked(true) opts out of system auto-duck and
                // routes CAN_DUCK to our listener, which keeps full volume.
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    // Ignore all focus changes — keep playing at full volume.
                    // FM radio should behave like a hardware tuner: always on,
                    // regardless of what other apps are doing.
                    DebugLog.log("SVC", "AudioFocus: $focusChange (ignored, keep playing)")
                }
                .build()
            audioFocusRequest = focusReq
            am.requestAudioFocus(focusReq)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    fun setVolume(volume: Float) { audioPlayer?.setVolume(volume.coerceIn(0f, 1f)) }

    // ========= DSP A/B test flags (runtime toggles for sound quality tuning) =========
    @Volatile
    var testFlags: Int = 0
        private set

    fun setTestFlags(flags: Int) {
        testFlags = flags
        try { nativeDsp?.setTestFlags(flags) } catch (_: Throwable) {}
    }

    fun toggleTestFlag(bit: Int) {
        setTestFlags(testFlags xor bit)
    }

    fun setBass(level: Int) {
        equalizer?.bassGainDb = (level - 10).toFloat()
    }

    fun setTreble(level: Int) {
        equalizer?.trebleGainDb = (level - 10).toFloat()
    }

    private fun cancelSeek() {
        seekJob?.cancel()
        seekJob = null
    }

    fun seekStation(forward: Boolean) {
        val dev = device ?: return

        cancelSeek()

        val wasPlaying = isPlaying
        if (wasPlaying) stopPlayback()

        seekJob = serviceScope.launch {
            try {
                val tempDemod = FmDemodulator()
                dev.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
                dev.setAutoGain(true)

                // Clear endpoint before seek to ensure USB reads work
                dev.fullReset()

                val step = currentBand.stepHz
                var freq = currentFrequency + if (forward) step else -step
                var found: Long? = null

                val maxSteps = ((currentBand.endHz - currentBand.startHz) / step).toInt()

                for (i in 0 until maxSteps) {
                    if (!isActive) break

                    if (freq > currentBand.endHz) freq = currentBand.startHz
                    if (freq < currentBand.startHz) freq = currentBand.endHz

                    dev.setFrequency(freq)
                    delay(30)
                    dev.resetBuffer()

                    val samples = dev.readSamples(32768, 500)
                    if (samples != null) {
                        val signalDb = tempDemod.measureSignalStrength(samples)
                        if (signalDb > SEEK_THRESHOLD) {
                            found = freq
                            break
                        }
                    }

                    freq += if (forward) step else -step
                }

                if (isActive) dev.fullReset()

                withContext(Dispatchers.Main) {
                    if (found != null) {
                        currentFrequency = found
                        onFrequencyChanged?.invoke(found)
                    }
                    onSeekComplete?.invoke(found)
                    if (wasPlaying && isActive) startPlayback()
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Seek cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Seek error", e)
                try { dev.fullReset() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    onSeekComplete?.invoke(null)
                    if (wasPlaying && isActive) startPlayback()
                }
            } finally {
                seekJob = null
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when user swipes app from recents — FM radio is a
        // background service, not tied to the UI lifecycle.
        DebugLog.log("SVC", "onTaskRemoved — keeping playback alive")
    }

    override fun onDestroy() {
        stopPlayback()
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        usbDispatcher.close()
        dspDispatcher.close()
        rdsDispatcher.close()
        super.onDestroy()
    }
}
