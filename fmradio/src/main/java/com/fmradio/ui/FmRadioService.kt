package com.fmradio.ui

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
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

/**
 * The radio, and — since 3.0.490 — a media browser too.
 *
 * A car's instrument cluster does not go looking for arbitrary foreground
 * services. It enumerates media apps the documented way: by querying the
 * package manager for services that answer
 * android.media.browse.MediaBrowserService, connecting to them, and reading
 * the MediaSession token they hand back. Everything else here was already
 * right — the session is active, its metadata is filled in both the TITLE and
 * DISPLAY_* families, and the notification is a MediaStyle bound to that
 * session's token — but with no browser service to find, none of it was ever
 * asked for. That is consistent with the cluster showing nothing at all while
 * the app's own screen shows the RDS text perfectly.
 *
 * The framework MediaBrowserService is used rather than the androidx one
 * because the session here is a framework MediaSession, and setSessionToken
 * then takes the token we already have instead of needing the whole session
 * converted to the compat classes.
 *
 * onGetRoot records who connected. If the cluster ever does ask, the next
 * report names it, and this stops being guesswork.
 */
class FmRadioService : android.service.media.MediaBrowserService() {

    companion object {
        private const val TAG = "FmRadioService"
        /** Floor the volume is held at during a traffic bulletin. */
        private const val TA_VOLUME = 0.85f
        /** A bulletin this long has lost its end flag, not run this long. */
        private const val TA_MAX_MS = 5 * 60 * 1000L
        private const val CHANNEL_ID = "fm_radio_playback"
        private const val BROWSER_ROOT = "fmradio_root"
        private const val NOTIFICATION_ID = 1001
        private const val SEEK_THRESHOLD = -15f
        /** See FmScanner.CHANNEL_RATIO_DB — the same test, same reasoning. */
        private const val SEEK_RATIO_DB = -20.0f
        // Smaller USB buffer = more frequent callbacks = lower latency
        private const val USB_BUFFER_SIZE = 32768
        // IQ data queue depth. Each buffer is 32 KB = ~14 ms of IQ at 1.152 Msps.
        // 64 × 14 ms ≈ 900 ms of headroom. On BYD DiLink the rear camera app
        // causes ~500-1000 ms of thread starvation — this buffer absorbs it.
        private const val IQ_QUEUE_DEPTH = 64

        // ===== Tuner gain loop (see startGainControl) =====
        // FC0013 IF gain is 2 dB per step; 31 is the ~62 dB maximum that the
        // driver has always used, and the loop never goes above it.
        private const val IF_GAIN_MAX_STEP = 31
        /**
         * How much one FC0013 IF gain step really moves the level, in dB.
         *
         * The datasheet implies 2 dB. Measured on this tuner from field logs,
         * adjacent steps land at rms 0.182 and 0.249 — 2.73 dB. The gain loop
         * divides its dB error by this to decide how far to move, so using the
         * datasheet figure made every correction 37% too large.
         */
        private const val IF_GAIN_STEP_DB = 2.73
        // One decision every 200 ms tracks fading at driving speed while
        // staying far slower than programme modulation. The meters are sampled
        // ten times inside that window and averaged, because a single reading
        // is one 17 ms USB block and far too noisy to steer on.
        private const val GAIN_SAMPLE_MS = 20L
        // 300 ms of readings behind every decision.
        //
        // 200 ms was too short even with the median: a median of ten 17 ms
        // blocks spans a fifth of a second, so an excursion lasting a third of
        // a second IS the median, and the 3.0.512 log has two corrections
        // undone within a second because of it. Half a second fixed that and
        // went too far the other way — with the two-decision confirmation and a
        // settle cycle on top, a correction took up to a second and a half, and
        // a car driving out of coverage cannot wait that long for the converter
        // to be fed properly.
        //
        // Fifteen blocks still needs eight of them corrupted before a burst can
        // move the middle one, and pairs the confirmation down to 600 ms.
        private const val GAIN_SAMPLES = 15
        // Ignore one decision (200 ms) after a change so the loop does not
        // react to its own move before the level has settled. One is enough
        // now that corrections are proportional and therefore few.
        private const val GAIN_SETTLE_CYCLES = 1
        // Fraction of ADC full scale to aim for. A healthy RTL2832U input sits
        // near a quarter of full scale: enough signal to keep quantisation
        // noise irrelevant, enough headroom for FM's peaks and for a passing
        // strong neighbour channel.
        // Lowered from 0.34/0.20/0.27. At the old target the field log showed
        // the converter clipping on EVERY steady-state line — 0.22%, 0.42%,
        // 0.68%, 1.76% of samples pinned at the rails. In an 8-bit converter
        // that is not a harmless statistic: each clipped sample is a step
        // discontinuity, and the splatter it makes lands right across the
        // demodulated audio as a hiss that no filter downstream can remove.
        //
        // 0.18 buys about 3.5 dB more headroom, which takes the clipping to
        // essentially nothing. It costs 3.5 dB of quantisation noise, and at
        // this bandwidth that sits some 45 dB below the audio — far under the
        // station's own noise floor, so it is not a trade at all in practice.
        // The dead zone must be at least one gain step wide or the loop hunts:
        // a 2 dB step would carry it straight across a narrower one. But it was
        // 0.13 to 0.24, which is 5.3 dB — more than twice a step — and the loop
        // duly settled wherever it first landed inside it. On the reported
        // station that was 0.133 to 0.146 every time, against a target of 0.18.
        //
        // Sitting at the bottom of the zone throws away ADC range, and in an
        // 8-bit converter that is quantisation noise straight into the audio:
        // 0.14 gives 53.9 dB in the audio band where 0.21 gives 57.4 dB. So the
        // clipping fix cost 5.7 dB against the 0.27 it replaced, and more than
        // half of that was the zone being loose rather than the target being
        // low. The field data bounds the top: clipping is 0.000% at 0.218,
        // 0.02-0.22% at 0.24, and only becomes real at 0.257.
        //
        // 0.185 to 0.240 is 2.26 dB — just over one step, so still no hunting.
        // The zone must CONTAIN an achievable operating point, and the previous
        // 0.185..0.240 did not. Measured from the field on this tuner, adjacent
        // gain steps land at 0.182 and 0.249 — the step is 2.73 dB, not the
        // 2 dB the datasheet implies — so both sit outside a 2.26 dB zone and
        // the loop oscillated between them for ever, one change every few
        // seconds, each one a 2.7 dB jump in everything downstream.
        //
        // That was mine, from narrowing the zone to recover ADC range. The
        // range was worth recovering; the arithmetic was wrong. 0.170..0.265 is
        // 3.86 dB, wider than the real step with margin for the reading moving
        // with programme content, so both points are inside and the loop can
        // stop.
        // A dead zone this loop can still regulate inside.
        //
        // It was widened to 0.145..0.295 — 6.2 dB — on the reasoning that the
        // reading wandered 3 to 5 dB on its own. That reasoning read the
        // 3.0.512 log wrongly: the spread quoted there was measured ACROSS gain
        // steps, so most of it was the step change itself, not wander. The
        // 3.0.516 log settles it, because by then the medians were in and the
        // loop never moved: on 107.7, twenty-seven consecutive decisions over
        // four and a half minutes at a fixed step read 0.251 to 0.268. That is
        // six tenths of a decibel, not five.
        //
        // What a zone that wide actually did was stop the loop working. It
        // starts at step 20; 0.25 is inside 0.145..0.295; so it stayed at step
        // 20 on every station in the log — 105.5, 107.0 and 107.7 alike — and
        // never regulated anything. More IF gain than the signal needs is more
        // intermodulation from the eight other carriers in the 960 kHz window,
        // on every station at once, which is exactly what was reported.
        //
        // 0.170..0.265 is 3.9 dB: wider than one 2.73 dB step plus the six
        // tenths the reading really moves, and narrow enough that the loop has
        // to find the right step instead of accepting wherever it started.
        private const val ADC_RMS_HIGH = 0.265f
        private const val ADC_RMS_LOW = 0.170f
        // Middle of the dead zone — what the proportional correction aims at.
        private const val ADC_RMS_TARGET = 0.21f
        // Where the loop starts. Mid-scale rather than maximum so a strong
        // station is not grossly overloaded for the first seconds.
        private const val IF_GAIN_START_STEP = 20
        // Any sustained clipping at all is already audible on FM.
        // What counts as overload, as a PERCENTAGE of samples pinned at the
        // rails. This was 0.02 — two hundredths of one per cent — which a
        // healthy signal exceeds all the time: a field log at the correct level
        // (rms 0.27, dead on target) read 0.10 to 0.37 every single decision.
        // So the clip branch fired continuously, slammed the gain down 12 dB,
        // the level collapsed to 0.07, the loop wound it back up, and it
        // clipped again — a permanent 12 dB oscillation twice a second. That is
        // audible as the constant hiss and roughness, and it moves the RDS
        // carrier so far that block sync cannot hold.
        //
        // In the same log a genuine burst read 7.3%, so 1% separates the two
        // cleanly with room on both sides.
        private const val CLIP_LIMIT_PCT = 1.0f
        // Clipping has to be there on two decisions running before the loop
        // acts on it. One noisy 200 ms window is not overload.
        private const val CLIP_CONFIRM = 2
        // Log a steady-state line every ~10 s so field logs show the level.
        private const val GAIN_LOG_TICKS = 20

        /** Mirrors TEST_FORCE_MONO in fm_dsp.cpp. */
        const val TEST_FORCE_MONO = 0x40
        /**
         * Mirrors TEST_NB_ON in fm_dsp.cpp — the impulse blanker.
         *
         * Off by default because on a clean signal it can only take away. But
         * this receiver lives inside an electric car, on the whip that came in
         * the box, a few centimetres from a traction inverter and a DC-DC
         * converter — which is exactly the broadband switching hash it exists
         * to gate out. Whether it helps here is an empirical question, so it
         * gets a switch rather than a new default.
         */
        const val TEST_NOISE_BLANKER = 0x20

        /**
         * Mirrors TEST_NO_LOUDNESS in fm_dsp.cpp — switches loudness
         * normalisation OFF, leaving each station at the level it transmits.
         */
        const val TEST_NO_LOUDNESS = 0x80

        // More buffers than the channel can hold, so the producer can never
        // wrap onto one the consumer is still reading.
        // The queue was 12 packets — 205 ms. A field report on a station with
        // the cleanest signal this receiver has ever produced (noise 0.027, USB
        // at 100.0% with no lost reads, DSP not behind) still showed
        // "dropped=13(+0 at start)": thirteen wideband packets lost in thirty
        // seconds, none of them during startup. One every two seconds, 0.7% of
        // the total, spread evenly — which is a consumer that stalls now and
        // then for longer than 205 ms, not one that is too slow on average. A
        // deficit would have emptied the queue and kept dropping.
        //
        // Every one of those is a discontinuity, and RDS carries its bit clock
        // and its differential phase across packet boundaries — it cannot bridge
        // even one. That is why sync never held on a signal that should carry
        // RDS easily, and it is why the conclusion that RDS was signal-limited
        // was wrong.
        //
        // Text tolerates latency; it does not tolerate gaps. 48 packets is
        // 819 ms of slack for a stall to hide in, and the pool costs 1.5 MB.
        private const val RDS_POOL = 64
        private const val RDS_QUEUE = 48
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
    private var gainJob: Job? = null
    private var dspJob: Job? = null
    private var seekJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mediaSession: MediaSession? = null
    /** Last text published to the cluster, so identical updates are not resent. */
    private var lastMetadataKey: String = ""
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
    /** Wideband packets the RDS thread could not keep up with. Any at all is a fault. */
    @Volatile private var rdsDropped = 0L
    @Volatile private var rdsDroppedAtStart = 0L
    /** True once the RDS thread has taken its first packet. */
    @Volatile private var rdsConsumerStarted = false

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

    /**
     * The activity binds for direct control; the system binds for browsing.
     * MediaBrowserService owns the second case and must be given it, or the
     * cluster's connection attempt gets handed a binder it cannot talk to.
     */
    override fun onBind(intent: Intent): IBinder? =
        if (android.service.media.MediaBrowserService.SERVICE_INTERFACE == intent.action)
            super.onBind(intent) else binder

    /**
     * Anyone may browse. This is a broadcast radio: there is nothing here that
     * is not already coming out of the speakers, so refusing a caller could
     * only mean refusing the one that matters — and the whole difficulty has
     * been not knowing which caller that is.
     */
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: android.os.Bundle?
    ): android.service.media.MediaBrowserService.BrowserRoot {
        com.fmradio.util.StatusSnapshot.noteBrowserClient(clientPackageName)
        DebugLog.log(TAG, "MediaBrowser connect from $clientPackageName (uid $clientUid)")
        return android.service.media.MediaBrowserService.BrowserRoot(BROWSER_ROOT, null)
    }

    /**
     * The saved stations, as a browsable list. A cluster that offers next and
     * previous gets something to move through, and one that only draws the
     * current item still gets its metadata from the session.
     */
    override fun onLoadChildren(
        parentId: String,
        result: android.service.media.MediaBrowserService.Result<MutableList<android.media.browse.MediaBrowser.MediaItem>>
    ) {
        if (parentId != BROWSER_ROOT) {
            result.sendResult(mutableListOf())
            return
        }
        val items = try {
            stationStorage.loadStations().sortedBy { it.frequencyHz }.map { st ->
                val label = st.rdsPs.takeIf { it.isNotBlank() }
                    ?: st.name.takeIf { it.isNotBlank() }
                    ?: com.fmradio.util.Freq.mhz(st.frequencyHz)
                android.media.browse.MediaBrowser.MediaItem(
                    MediaDescription.Builder()
                        .setMediaId(st.frequencyHz.toString())
                        .setTitle(label)
                        .setSubtitle(com.fmradio.util.Freq.mhz(st.frequencyHz) + " MHz")
                        .build(),
                    android.media.browse.MediaBrowser.MediaItem.FLAG_PLAYABLE
                )
            }.toMutableList()
        } catch (_: Throwable) {
            mutableListOf<android.media.browse.MediaBrowser.MediaItem>()
        }
        result.sendResult(items)
    }

    override fun onCreate() {
        com.fmradio.util.StartupLog.write("FmRadioService.onCreate")
        super.onCreate()
        stationStorage = StationStorage(this)
        // Restore the DSP preferences (the mono choice lives here) before
        // anything can start playing.
        testFlags = try {
            getSharedPreferences("fm_radio_stations", MODE_PRIVATE)
                .getInt("dsp_test_flags", 0)
        } catch (_: Throwable) { 0 }
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
            com.fmradio.util.StartupLog.write("startForeground ok")
        } catch (e: Exception) {
            com.fmradio.util.StartupLog.write("startForeground FAILED: $e")
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
                        PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
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

                /** A station chosen in the car's browser. The id is its frequency. */
                override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
                    val hz = mediaId?.toLongOrNull() ?: return
                    tuneToFrequency(hz)
                    if (!isPlaying) startPlayback()
                }

                override fun onRewind() {
                    val step = currentBand.stepHz
                    tuneToFrequency((currentFrequency - step).coerceAtLeast(currentBand.startHz))
                }
            })

            isActive = true
        }
        // Hand the token to MediaBrowserService. A browser client that connects
        // before this is set gets a null token and nothing to read, so it is
        // done here rather than lazily.
        try { sessionToken = mediaSession?.sessionToken } catch (_: Throwable) {}
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
                    PlaybackState.ACTION_REWIND or
                    PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(state, currentFrequency, 1f)
                .build()
        )

        publishMetadata()
    }

    /**
     * What the car shows on the instrument cluster.
     *
     * The cluster reads whatever the active MediaSession publishes, which is
     * how the stock apps get a "now playing" line next to the speedometer. Three
     * things were missing for that to work here.
     *
     * The DISPLAY_* keys were only half filled in. TITLE/ARTIST is what a phone
     * notification reads; a lot of head-unit HMIs — Chinese ones especially —
     * read DISPLAY_TITLE/DISPLAY_SUBTITLE/DISPLAY_DESCRIPTION instead, and fall
     * back to nothing rather than to TITLE. Both families are filled now, with
     * the same text, so it does not matter which the car looks at.
     *
     * DURATION was absent. A session with no duration makes some HMIs treat the
     * item as invalid and draw nothing at all; -1 is the convention for a live
     * stream and is what tells them there is no seek bar to draw.
     *
     * And it was only republished when RDS delivered a station NAME. On a
     * station whose PS never arrives but whose RadioText does — which is most of
     * a marginal signal — the cluster kept whatever it was given at tune time
     * for ever. It now republishes on any change, and only on a change.
     */
    private fun publishMetadata() {
        val freqText = com.fmradio.util.Freq.mhz(currentFrequency) + " MHz"
        val savedName = stationStorage.loadStations()
            .find { Math.abs(it.frequencyHz - currentFrequency) < 50000 }
            ?.name?.takeIf { it.isNotBlank() }
        val station = currentRdsData.ps.trim().takeIf { it.isNotBlank() }
            ?: savedName
            ?: freqText
        // What is playing goes on the top line — that is the line the request
        // was about. The station and frequency go underneath it.
        val nowPlaying = currentRdsData.rt.trim().takeIf { it.isNotBlank() } ?: station
        val under = if (nowPlaying == station) freqText else "$station · $freqText"

        val key = "$nowPlaying|$under"
        if (key == lastMetadataKey) return          // nothing changed
        lastMetadataKey = key

        mediaSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, nowPlaying)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, nowPlaying)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, under)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, under)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, station)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, freqText)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, -1L)
                .build()
        )
        broadcastNowPlaying(nowPlaying, under, station)
        com.fmradio.util.StatusSnapshot.clusterLine = "$nowPlaying / $under"
    }

    /**
     * The other way a head unit learns what is playing.
     *
     * Everything the Android media stack asks for is already in place: the
     * session is active, it carries metadata in both the TITLE and DISPLAY_*
     * families, and the foreground notification is a MediaStyle bound to that
     * session's token. On a DiLink 4.0 the cluster still showed nothing, so the
     * cluster is not reading the session.
     *
     * What it is reading is almost certainly the broadcast the original Android
     * music player sent. Chinese head units, and OEM clusters generally, were
     * built against that de-facto protocol years before MediaSession existed and
     * many still listen for it and nothing else. Third-party players have
     * emitted it for the same reason for over a decade.
     *
     * Several action names are sent because each vendor kept its own: a unit
     * that listens for one ignores the rest, and a unit that listens for none is
     * no worse off than it is now. These are implicit broadcasts, so since
     * Android 8 they only reach receivers registered at runtime — which is what
     * a system-side cluster service uses — and they need no permission.
     *
     * This is a reasonable guess, not a diagnosis: it cannot be verified without
     * the car. StatusSnapshot.clusterLine records what was published so the next
     * report says whether the app had the right text to send in the first place.
     */
    private fun broadcastNowPlaying(track: String, artist: String, album: String) {
        val actions = arrayOf(
            "com.android.music.metachanged",
            "com.android.music.playstatechanged",
            "com.htc.music.metachanged",
            "com.sonyericsson.music.metachanged",
            "com.samsung.sec.android.MusicPlayer.metachanged",
            "com.nullsoft.winamp.metachanged",
            "com.andrew.apollo.metachanged"
        )
        for (action in actions) {
            try {
                sendBroadcast(Intent(action).apply {
                    putExtra("track", track)
                    putExtra("title", track)
                    putExtra("artist", artist)
                    putExtra("album", album)
                    putExtra("playing", isPlaying)
                    putExtra("isplaying", isPlaying)
                    // Live radio: no length and no position to scrub to. Some
                    // HMIs hide the row when duration is absent entirely, so it
                    // is sent as zero rather than left out.
                    putExtra("duration", 0L)
                    putExtra("position", 0L)
                    putExtra("id", currentFrequency)
                    putExtra("package", packageName)
                })
            } catch (_: Throwable) {
                // A unit that refuses one of these must not stop the others.
            }
        }
    }

    fun initDevice(rtlSdrDevice: RtlSdrDevice) {
        this.device = rtlSdrDevice
    }

    fun tuneToFrequency(frequencyHz: Long) {
        currentFrequency = frequencyHz

        rdsGeneration++  // invalidate any pending RDS data in queue
        lastMetadataKey = ""   // new station: always republish to the cluster

        // Note which branch runs. When isPlaying is false the tuner is NOT
        // told anything — only the DSP is reset — so if audio is somehow still
        // coming through, every station would sound like the last one tuned.
        com.fmradio.util.StartupLog.write(
            "tune ${frequencyHz / 1000} kHz (playing=$isPlaying)")

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
        // A different station knows nothing about the bulletin that was
        // playing, and its own RDS may never say anything at all, so nothing
        // would ever put the volume back.
        endTaBoost("station changed")
        com.fmradio.util.StatusSnapshot.rdsTp = false

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

        // The previous session's DSP thread must be GONE before this one
        // touches the native state. stopPlayback joins it with a 1.5 s timeout
        // and its own comment admits that can return with the thread still
        // running — and the native DSP is one global object, so two threads in
        // it at once corrupt its filter buffers and its 6000-sample wideband
        // buffer. A field log shows the process dying two seconds after
        // playback restarted following a scan, with no Java exception, which is
        // what that corruption looks like from outside.
        //
        // So wait here as well, and for longer. If it still will not go, do not
        // start: a session that refuses to begin is recoverable, a corrupted
        // native heap is not.
        val stale = dspThread
        if (stale != null && stale.isAlive && stale != Thread.currentThread()) {
            com.fmradio.util.StartupLog.write("startPlayback: waiting for previous DSP thread")
            try { stale.join(4000) } catch (_: InterruptedException) {}
            if (stale.isAlive) {
                com.fmradio.util.StartupLog.write(
                    "startPlayback: ABORTED — previous DSP thread will not exit")
                Log.e(TAG, "Previous DSP thread still alive; refusing to start")
                return
            }
            com.fmradio.util.StartupLog.write("startPlayback: previous DSP thread gone")
        }
        dspThread = null

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

        // The rate must match whatever the DSP hands the wideband buffer at —
        // native and Kotlin both use FmDemodulator.INTERMEDIATE_RATE.
        rdsDecoder = RdsDecoder(FmDemodulator.INTERMEDIATE_RATE).also { rds ->
            rds.listener = object : RdsDecoder.RdsListener {
                override fun onRdsData(data: RdsDecoder.RdsData) {
                    currentRdsData = data
                    handleTrafficAnnouncement(data)
                    onRdsDataReceived?.invoke(data)
                    // Any change, not just a station name: RadioText is the
                    // "now playing" line and on a marginal signal it often
                    // arrives while PS never does.
                    if (data.ps.isNotBlank() || data.rt.isNotBlank()) {
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
        // RDS needs an unbroken sample stream: the bit clock and the
        // differential detector both carry state across packet boundaries, so a
        // single lost packet is a discontinuity that costs block sync.
        //
        // The pool used to hold four buffers behind a channel of capacity four.
        // Two things went wrong with that. The producer wrapped onto a buffer
        // the consumer might still be reading, and when the channel was full
        // trySend simply dropped the packet — silently, after the buffer had
        // already been overwritten. A field log showed the result exactly: the
        // decoder never confirmed sync at all, rejecting one candidate after
        // another at "good=1 bad=6", which is the false-alarm rate of the
        // syndrome search on random bits and not what a real signal looks like.
        //
        // So: more buffers than the channel can hold, so an in-flight one is
        // never reused; and a drop is counted and reported instead of being
        // invisible.
        val rdsPackets = Array(RDS_POOL) { RdsPacket(FloatArray(6000)) }
        var rdsPacketIdx = 0
        val rdsChannel = Channel<RdsPacket>(RDS_QUEUE)

        demodulator?.widebandListener = { widebandSamples, count, pilotPhase ->
            if (count > 0 && count <= 6000) {
                val pkt = rdsPackets[rdsPacketIdx]
                System.arraycopy(widebandSamples, 0, pkt.samples, 0, count)
                pkt.count = count
                pkt.phase = pilotPhase
                pkt.gen = rdsGeneration
                if (rdsChannel.trySend(pkt).isSuccess) {
                    // Only advance on success: a dropped packet leaves the
                    // buffer free to be refilled next time, and never lets the
                    // index run ahead of what the consumer has taken.
                    rdsPacketIdx = (rdsPacketIdx + 1) % RDS_POOL
                } else {
                    // Separate the startup burst from ongoing loss. The RDS
                    // consumer coroutine is not scheduled until after the first
                    // packets are already arriving, so a handful of drops at the
                    // very beginning are expected and harmless. Drops AFTER that
                    // are the ones that break bit timing, and only those say
                    // anything — a single count cannot tell the two apart, which
                    // is exactly the question a report of "dropped=21" leaves
                    // open.
                    if (rdsConsumerStarted) {
                        rdsDropped++
                        com.fmradio.util.StatusSnapshot.rdsDropped = rdsDropped
                    } else {
                        rdsDroppedAtStart++
                        com.fmradio.util.StatusSnapshot.rdsDroppedAtStart = rdsDroppedAtStart
                    }
                }
            }
        }

        // RDS decoder runs in its own thread — no impact on audio
        serviceScope.launch(rdsDispatcher) {
            for (pkt in rdsChannel) {
                rdsConsumerStarted = true
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
            com.fmradio.util.StartupLog.write("stream setup: sampleRate")
            dev.setSampleRate(sampleRate)
            com.fmradio.util.StartupLog.write("stream setup: gain")
            dev.setAutoGain(true)
            // Set the front-end gain explicitly instead of inheriting whatever
            // the last operation left behind. The scan drops the LNA to its
            // minimum, and nothing here ever put it back: after one scan the
            // receiver ran with minimum RF gain for the rest of the session, so
            // every station lost sensitivity and gained noise, and the loop
            // below compensated by winding the IF trim up — which amplifies the
            // noise along with the signal. That is the reported "signal got
            // weaker and everything hisses, even strong stations".
            //
            // LNA high and fixed, IF trimmed by the gain loop, is what that loop
            // was designed around: it only ever moves the IF step.
            if (dev.supportsIfGainTrim) dev.setGain(15)     // FC0013 LNA maximum
            com.fmradio.util.StartupLog.write("stream setup: fullReset")
            dev.fullReset()
            com.fmradio.util.StartupLog.write("stream setup: frequency")
            dev.setFrequency(currentFrequency)
            Thread.sleep(50)
            // The last breadcrumb before the process died in a field log was
            // the one above, so the next few steps get their own.
            com.fmradio.util.StartupLog.write("stream setup: done, starting reader")

            Log.i(TAG, "USB setup done, starting streaming...")
            DebugLog.log("SVC", "USB setup done: rate=$sampleRate freq=${currentFrequency/1e6}MHz buf=$USB_BUFFER_SIZE")

            val innerJob = dev.startStreaming(USB_BUFFER_SIZE) { iqData ->
                if (iqQueue.size < IQ_QUEUE_DEPTH) {
                    iqQueue.add(iqData)
                } else {
                    com.fmradio.util.StatusSnapshot.iqDropped++
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
                        rdsDecoder?.setPilotFreq(ndsp.getPilotFreq(), ndsp.getIsStereo())
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
                if (demodCallCount % 8 == 0L) {
                    // Always on — a problem is noticed after it happens, so the
                    // report must not depend on logging having been switched on
                    // beforehand. See StatusSnapshot.
                    val snap = com.fmradio.util.StatusSnapshot
                    snap.playing = true
                    snap.frequencyHz = currentFrequency
                    snap.signalDb = ndsp?.getSignalDb() ?: demodulator?.currentSignalStrengthDb ?: -100f
                    snap.stereo = ndsp?.getIsStereo() ?: (demodulator?.isStereo == true)
                    snap.iqQueueDepth = iqQueue.size
                    snap.nativeDsp = ndsp != null
                    audioPlayer?.let {
                        snap.audioUnderruns = it.underrunCount()
                        snap.audioBufferBytes = it.bufferBytes()
                        snap.audioBufferFrames = it.bufferFrames()
                    }
                    ndsp?.let {
                        snap.adcRms = it.getAdcRms()
                        snap.adcClipPct = it.getAdcClipPct()
                        snap.noiseLevel = it.getNoiseLevel()
                        snap.rdsCarrierLevel = try { it.getRdsCarrierLevel() } catch (_: Throwable) { 0f }
                        snap.rdsShoulderLevel = try { it.getRdsShoulderLevel() } catch (_: Throwable) { 0f }
                        snap.stereoBlend = it.getStereoBlend()
                        snap.hiCutHz = it.getHiCutHz()
                        snap.blanked = it.getBlankedCount()
                        snap.softClipPct = it.getSoftClipPct()
                        snap.loudnessGain = it.getLoudnessGain()
                    }
                }
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

        com.fmradio.util.StartupLog.write("streaming started, AGC loop next")
        startGainControl(dev, ndsp)

        updateMediaSessionState()
        updateNotification()
        Log.i(TAG, "Playback started at ${currentFrequency / 1e6} MHz")
    }

    /**
     * Automatic gain control for the tuner.
     *
     * The FC0013 was previously set once, at maximum, with its own AGC turned
     * off, and never touched again for the rest of the session. That is fine
     * sitting still, but a moving vehicle sees the received level swing by
     * tens of dB: close to a transmitter the RTL2832U's 8-bit converter runs
     * into its end stops, which is heard as harsh, crackly distortion on a
     * station that sounds clean everywhere else. Picking a lower fixed value
     * instead just moves the problem to weak signals — the code history shows
     * exactly that being tried and reverted.
     *
     * So: close the loop. Only the IF trim moves, and only downward from the
     * maximum, so a weak signal is handled exactly as it was before while a
     * strong one gets the headroom it needs. Steps are 2 dB and the loop runs
     * five times a second, which tracks driving-speed fading without being
     * fast enough to pump on modulation.
     */
    private fun startGainControl(dev: RtlSdrDevice, ndsp: NativeFmDsp?) {
        gainJob?.cancel()
        if (ndsp == null || !dev.supportsIfGainTrim) {
            Log.i(TAG, "AGC: not available (native=${ndsp != null} tunerTrim=${dev.supportsIfGainTrim})")
            return
        }
        gainJob = serviceScope.launch(usbDispatcher) {
            // Start mid-scale, not at maximum. Beginning at 62 dB means a
            // strong local station overloads the converter from the first
            // sample, and stepping down one or two notches at a time took
            // about nine seconds to reach a sane level — heard as loud noise
            // on tuning in that then clears up.
            var step = IF_GAIN_START_STEP
            com.fmradio.util.StatusSnapshot.gainStep = step
            com.fmradio.util.StatusSnapshot.adcBurstBlocks = 0
            com.fmradio.util.StatusSnapshot.adcTotalBlocks = 0
            dev.setFc0013IfGainStep(step)
            var settleTicks = 0
            var settleAfterChange = 0
            var clipRun = 0
            // Consecutive decisions asking to move the SAME way — see below.
            var moveRun = 0
            var moveDir = 0
            while (isActive && isPlaying) {
                // Ten readings over the interval, and the MIDDLE one is used —
                // not the mean of the levels and not the worst of the clipping.
                //
                // Each reading covers a single 17 ms USB block. On FM the level
                // swings about 2:1 from block to block, which is why a single
                // reading was never enough. But a field log from this car shows
                // something the mean cannot survive: bursts of interference
                // that saturate the converter outright for a block or two at a
                // time. Inside one 200 ms decision, with the steady level at
                // rms 0.19 and no clipping at all, the loop saw rms 0.277 with
                // 12.1% clipped, then 0.282 with 13.2%, then once rms 0.505
                // with 4.5%. Nothing about the station changed; the noise
                // blanker was counting impulses throughout.
                //
                // The mean carries those blocks straight into the decision and
                // the max is made ENTIRELY of them, so the loop read overload,
                // dropped a step, found itself under the floor, climbed back,
                // and went round again — 18, 19, 18, 19, 17, 18 for three
                // minutes, each move 2.73 dB through everything downstream and
                // each one enough to break RDS block sync, which in that log
                // never held longer than a second.
                //
                // The median asks the right question. Real overload is in every
                // block, so the middle reading shows it and the gain still
                // comes down. A burst is in one or two, so the middle reading
                // ignores it — which is correct: turning the gain down does not
                // make a burst of interference go away, it only makes the
                // station quieter until the next burst pushes it back.
                val rmsAll = FloatArray(GAIN_SAMPLES)
                val clipAll = FloatArray(GAIN_SAMPLES)
                var samples = 0
                var bursts = 0
                repeat(GAIN_SAMPLES) {
                    delay(GAIN_SAMPLE_MS)
                    val r = ndsp.getAdcRms()
                    if (r > 0f) {
                        val c = ndsp.getAdcClipPct()
                        rmsAll[samples] = r
                        clipAll[samples] = c
                        if (c > CLIP_LIMIT_PCT) bursts++
                        samples++
                    }
                }
                if (samples == 0) continue       // no data yet
                java.util.Arrays.sort(rmsAll, 0, samples)
                java.util.Arrays.sort(clipAll, 0, samples)
                val rms = rmsAll[samples / 2]
                val clip = clipAll[samples / 2]
                // Blocks the converter was overloaded in, whatever the loop
                // decided to do about it. This is the interference itself, and
                // until now no number in the report showed it.
                com.fmradio.util.StatusSnapshot.adcBurstBlocks += bursts
                com.fmradio.util.StatusSnapshot.adcTotalBlocks += samples

                // After a change, let the tuner and the meters settle before
                // judging the result, or the loop reacts to its own move.
                // Count consecutive decisions that saw clipping.
                clipRun = if (clip > CLIP_LIMIT_PCT) clipRun + 1 else 0

                if (settleAfterChange > 0) {
                    settleAfterChange--
                    continue
                }

                // Correct proportionally: turn the level error straight into
                // decibels and divide by the 2 dB a step is worth. One notch
                // at a time is fine once settled but hopeless from a cold
                // start, which is where the audible problem was.
                val clipping = clipRun >= CLIP_CONFIRM
                val newStep = when {
                    clipping -> {
                        // Clipping compresses the reading, so rms understates
                        // how far out we are. But three steps is 8 dB on this
                        // tuner, and a field log shows what that costs: a 3.5%
                        // clip took the gain from step 15 to 12, the level fell
                        // to rms 0.121 — far below the 0.170 floor — and the
                        // loop spent the next second climbing back to 14. That
                        // round trip repeated every few seconds for the whole
                        // recording. Each move is a step in everything
                        // downstream, and clipping is caused by loud programme,
                        // so the pumping follows the programme.
                        //
                        // One step, then look again. If it is still clipping the
                        // next decision moves it again 200 ms later, which
                        // converges without ever overshooting the floor.
                        step - 1
                    }
                    rms > ADC_RMS_HIGH || rms < ADC_RMS_LOW -> {
                        val errDb = 20.0 * kotlin.math.log10((rms / ADC_RMS_TARGET).toDouble())
                        // Divide by the step size this tuner really has, not the
                        // 2 dB the datasheet claims. The comment on ADC_RMS_LOW
                        // above records the measurement: adjacent steps land at
                        // rms 0.182 and 0.249, which is 2.73 dB. Dividing a dB
                        // error by 2 when each step moves 2.73 asks for 37% more
                        // correction than is needed, every single time — a loop
                        // that overshoots by a third hunts instead of settling.
                        val delta = Math.round(errDb / IF_GAIN_STEP_DB).toInt().coerceIn(-8, 8)
                        step - delta
                    }
                    else -> step
                }.coerceIn(0, IF_GAIN_MAX_STEP)

                // Two decisions agreeing before the gain moves.
                //
                // The level this loop steers on is the power of the whole
                // 960 kHz window, which holds nine channels of the 100 kHz
                // grid. Any one FM carrier has a constant envelope, but a sum
                // of them does not — they beat against each other — so the
                // reading wanders by several dB with the gain untouched. A
                // field log at 107.7 shows it going from rms 0.164 to 0.305 at
                // a fixed step 18: 5.4 dB, wider than the 3.9 dB dead zone,
                // which means the zone cannot contain it and the loop cannot
                // settle inside it however well the arithmetic is done.
                //
                // What came out was a limit cycle every few seconds: a peak
                // reads as clipping, the gain drops a step, the next reading
                // falls under the floor, the gain goes back up, and round
                // again. One notch is 2.73 dB through everything downstream.
                //
                // Requiring the same direction twice running costs 200 ms of
                // response and throws away exactly the excursions that are the
                // window wandering rather than the signal changing. A real
                // level change persists and still moves the gain on the second
                // decision.
                //
                // CLIPPING IS EXEMPT, and that is not a detail. A level reading
                // is an estimate that this rule exists to doubt; a sample
                // sitting on the converter's rail is a fact. Worse, making
                // clipping wait for agreement can stop the gain moving at all:
                // a peak reads as clipping and asks to go down, the next window
                // reads under the floor and asks to go up, the direction flips
                // every time and the run never reaches two. The gain then sits
                // for ever at a level that clips a percent of its samples, and
                // an 8-bit converter clipping is broadband distortion right
                // across the window — noise that appears only when there is
                // programme, which is precisely what was reported after the
                // confirmation rule shipped.
                val dir = if (newStep > step) 1 else if (newStep < step) -1 else 0
                if (dir == 0) { moveRun = 0; moveDir = 0 }
                else if (dir == moveDir) moveRun++
                else { moveDir = dir; moveRun = 1 }
                // A correction of two steps or more is a real level change,
                // not dither, and waiting a second to believe it is what makes
                // the level lurch when driving. Five and a half decibels of
                // error does not arrive by accident.
                val bigMove = kotlin.math.abs(newStep - step) >= 2
                val confirmed = dir != 0 && (clipping || bigMove || moveRun >= 2)

                if (confirmed) {
                    moveRun = 0; moveDir = 0
                    step = newStep
                    clipRun = 0
                    com.fmradio.util.StatusSnapshot.gainStep = step
                    dev.setFc0013IfGainStep(step)
                    // A gain step moves the tuner's DC offset in one jump. The
                    // blocker now runs slowly on purpose, so tell it to catch
                    // up rather than let it carry the old offset for most of a
                    // minute. See reseedDc in fm_dsp.cpp.
                    try { ndsp.reseedDc() } catch (_: Throwable) {}
                    settleTicks = 0
                    settleAfterChange = GAIN_SETTLE_CYCLES
                    DebugLog.log("AGC", "IF gain -> step $step (${"%.1f".format(step * IF_GAIN_STEP_DB)} dB), " +
                            "rms=%.3f clip=%.3f%% (median of $samples) burstBlocks=$bursts".format(rms, clip))
                } else if (++settleTicks >= GAIN_LOG_TICKS) {
                    settleTicks = 0
                    DebugLog.log("AGC", "steady: step $step (${"%.1f".format(step * IF_GAIN_STEP_DB)} dB), " +
                            "rms=%.3f clip=%.3f%% burst=%d/%d | noise=%.4f stereo=%.2f hicut=%.0fHz nb=%d" +
                            // The subcarrier measurement, in the log as well as
                            // in the report. A report covers one station; a log
                            // covers every station the drive passed through,
                            // and the question "does this one transmit RDS at
                            // all" is worth answering for all of them at once.
                            " | rds=%.4f/%.4f"
                                .format(rms, clip, bursts, samples, ndsp.getNoiseLevel(),
                                        ndsp.getStereoBlend(), ndsp.getHiCutHz(),
                                        ndsp.getBlankedCount(),
                                        try { ndsp.getRdsCarrierLevel() } catch (_: Throwable) { 0f },
                                        try { ndsp.getRdsShoulderLevel() } catch (_: Throwable) { 0f }))
                }
            }
        }
    }

    fun stopPlayback() = stopPlayback(cancelSeekToo = true)

    /**
     * @param cancelSeekToo false when called from INSIDE the seek coroutine —
     *   otherwise cancelSeek() cancels the very job that is calling this, and
     *   the seek aborts before it starts.
     */
    private fun stopPlayback(cancelSeekToo: Boolean) {
        isPlaying = false
        com.fmradio.util.StatusSnapshot.playing = false
        gainJob?.cancel()
        gainJob = null

        // Release wake lock
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}

        if (cancelSeekToo) cancelSeek()

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

        // Releasing the AudioTrack while the DSP thread is still writing to it
        // is a use-after-free inside the audio framework — the process dies at
        // once, with no Java exception to catch. The join above has a timeout,
        // so it CAN return with that thread still running, and a log showed the
        // DSP running ~255 ms behind the USB stream, which makes overrunning
        // the timeout entirely possible. Never release under a live writer:
        // hand it to a background thread that waits for the writer to leave.
        val player = audioPlayer
        audioPlayer = null
        if (player != null) {
            if (t == null || !t.isAlive) {
                player.stop()
            } else {
                com.fmradio.util.StartupLog.write("stopPlayback: DSP thread still alive, deferring audio release")
                Thread({
                    try { t.join(5000) } catch (_: InterruptedException) {}
                    try { player.stop() } catch (_: Throwable) {}
                }, "FmAudioRelease").apply { isDaemon = true }.start()
            }
        }

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

    /** What the user set, before any traffic-announcement override. */
    @Volatile private var userVolume = 1f
    /** True while a traffic bulletin is being carried at the raised level. */
    @Volatile private var taBoostActive = false
    private var taBoostStartedAt = 0L

    fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    private fun applyVolume() {
        val v = if (taBoostActive) maxOf(userVolume, TA_VOLUME) else userVolume
        audioPlayer?.setVolume(v)
    }

    /**
     * Raise the volume for the duration of a traffic bulletin.
     *
     * This is what TA is for, and until now the app only lit an indicator with
     * it. A bulletin is worth hearing over whatever the volume happens to be
     * set to, so the level is a FLOOR, not an increase: a listener already
     * above it is left alone, which is how car radios have always done it, and
     * it means the announcement cannot come out quieter or louder than
     * expected depending on where the knob was.
     *
     * TP is required as well as TA. TA alone means "an announcement is on",
     * TP means "this station carries them at all", and a station that does not
     * carry traffic has no business raising the volume whatever its TA bit
     * says. The decoder only sets either from a block B that passed CRC
     * untouched, twice running — one wrong bit here is a jump in loudness with
     * nothing behind it.
     *
     * The time limit is the other half of that. RDS here loses about half its
     * blocks, so the bulletin's END can go missing as easily as its start; a
     * real one runs a couple of minutes, and after five the flag is not
     * telling the truth any more.
     */
    private fun handleTrafficAnnouncement(data: RdsDecoder.RdsData) {
        val announcing = data.ta && data.tp
        val now = System.currentTimeMillis()
        com.fmradio.util.StatusSnapshot.rdsTp = data.tp
        if (announcing && !taBoostActive) {
            if (!stationStorage.taVolumeEnabled) return
            taBoostActive = true
            taBoostStartedAt = now
            applyVolume()
            com.fmradio.util.StatusSnapshot.taActive = true
            com.fmradio.util.StatusSnapshot.taCount++
            DebugLog.log(TAG, "TA: announcement started, volume ${"%.0f".format(userVolume * 100)}% -> " +
                    "${"%.0f".format(maxOf(userVolume, TA_VOLUME) * 100)}%")
        } else if (taBoostActive && (!announcing || now - taBoostStartedAt >= TA_MAX_MS)) {
            endTaBoost(if (announcing) "no end flag after ${TA_MAX_MS / 1000}s" else "ended")
        }
    }

    private fun endTaBoost(why: String) {
        if (!taBoostActive) return
        taBoostActive = false
        applyVolume()
        com.fmradio.util.StatusSnapshot.taActive = false
        DebugLog.log(TAG, "TA: $why, volume back to ${"%.0f".format(userVolume * 100)}%")
    }

    // ========= DSP A/B test flags (runtime toggles for sound quality tuning) =========
    @Volatile
    var testFlags: Int = 0
        private set

    fun setTestFlags(flags: Int) {
        testFlags = flags
        try { nativeDsp?.setTestFlags(flags) } catch (_: Throwable) {}
        // Survive a restart: the mono choice in particular is a preference, not
        // a debug toggle, and having to set it again every time the app came
        // back would make it useless.
        try {
            getSharedPreferences("fm_radio_stations", MODE_PRIVATE)
                .edit().putInt("dsp_test_flags", flags).apply()
        } catch (_: Throwable) {}
    }

    /** Force mono regardless of signal — see TEST_FORCE_MONO in fm_dsp.cpp. */
    var forceMono: Boolean
        get() = (testFlags and TEST_FORCE_MONO) != 0
        set(on) = setTestFlags(
            if (on) testFlags or TEST_FORCE_MONO else testFlags and TEST_FORCE_MONO.inv())

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

        seekJob = serviceScope.launch {
            // stopPlayback() used to run HERE, before the coroutine — i.e. on
            // whichever thread pressed the button, which is the UI thread.
            // It waits for the USB read loop to finish (up to 2 s), for the
            // reader to leave its last transfer (2 s) and for the DSP thread to
            // join (1.5 s). Five and a half seconds of a blocked UI thread is
            // past what Android tolerates, and the system kills the process —
            // which is exactly "press seek and the app disappears".
            com.fmradio.util.StartupLog.write("seek: stopping playback")
            if (wasPlaying) stopPlayback(cancelSeekToo = false)
            com.fmradio.util.StartupLog.write("seek: begin ${if (forward) "up" else "down"}")
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

                    // A breadcrumb every few steps. Seek closes the app with no
                    // crash screen at all on the head unit, which means the
                    // process is dying below the Java handler — so the only
                    // evidence available is how far it got, and that has to be
                    // on disk before it dies. StartupLog flushes every line.
                    if (i % 4 == 0) {
                        com.fmradio.util.StartupLog.write("seek: step $i at ${freq / 1000} kHz")
                    }
                    dev.setFrequency(freq)
                    delay(30)
                    dev.resetBuffer()

                    val samples = dev.readSamples(32768, 500)
                    if (samples != null) {
                        val signalDb = tempDemod.measureSignalStrength(samples)
                        // The level alone cannot say whether the station is
                        // HERE or is a neighbour inside the same 960 kHz window,
                        // and it moves with the tuner gain, so a fixed bar on it
                        // was never meaningful. The channel ratio is
                        // gain-independent and answers exactly that question.
                        val ratio = tempDemod.measureChannelRatioDb(samples)
                        if (signalDb > SEEK_THRESHOLD && ratio > SEEK_RATIO_DB) {
                            found = freq
                            break
                        }
                    }

                    freq += if (forward) step else -step
                }

                com.fmradio.util.StartupLog.write("seek: sweep done, found=$found")
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

    /**
     * How many actions createNotification() adds to the builder. Kept next to
     * the builder so the compact-view slots can never name an action that does
     * not exist — see the comment at the call site for what that costs.
     */
    private val notificationActionCount = 0

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
            val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
            // Only name compact-view slots that actually exist. This asked for
            // actions 0, 1 and 2 while the builder had none, and on Android 10
            // that is fatal, not cosmetic: the system fails to inflate the
            // notification and kills the process with
            //   RemoteServiceException: Bad notification ...
            //   setShowActionsInCompactView: action 0 out of bounds (max -1)
            // which is exactly how the app died on a BYD DiLink 4.0 head unit
            // moments after showing "Connecting". Newer Android tolerates it,
            // so it went unnoticed.
            if (notificationActionCount > 0) {
                val slots = IntArray(minOf(3, notificationActionCount)) { it }
                style.setShowActionsInCompactView(*slots)
            }
            builder.setStyle(style)
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
