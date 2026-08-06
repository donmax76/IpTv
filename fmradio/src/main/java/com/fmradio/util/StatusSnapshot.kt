package com.fmradio.util

/**
 * What the radio is doing right now, kept in memory and always up to date.
 *
 * Every diagnostic the app could produce used to depend on file logging being
 * switched on beforehand. A problem is noticed after it happens, not before,
 * so the report that got sent was routinely from a session with logging off —
 * it said nothing about the frequency, the signal, the gain or the buffers,
 * and each round trip cost a day.
 *
 * Updating a handful of fields costs nothing measurable, so it is done
 * unconditionally and the report always carries the state at the moment the
 * user pressed the button.
 */
object StatusSnapshot {

    @Volatile var playing = false
    @Volatile var frequencyHz = 0L
    @Volatile var signalDb = 0f
    @Volatile var stereo = false
    @Volatile var gainStep = -1
    @Volatile var adcRms = 0f
    @Volatile var adcClipPct = 0f
    @Volatile var noiseLevel = 0f
    @Volatile var stereoBlend = 0f
    @Volatile var hiCutHz = 0f
    @Volatile var iqQueueDepth = 0
    @Volatile var nativeDsp = false
    /** Times the audio device ran dry — this is what "stuttering" means. */
    @Volatile var audioUnderruns = 0
    @Volatile var audioBufferBytes = 0
    /** What the device really gave, in frames — see AudioPlayer.bufferFrames. */
    @Volatile var audioBufferFrames = 0
    /** Samples the impulse blanker gated out; 0 means it is off or found nothing. */
    @Volatile var blanked = 0L
    /** Audio reaching the limiter knee. Above a fraction of a percent it is
     *  working on the programme, not on peaks, and that is audible grit. */
    @Volatile var softClipPct = 0f

    /** Loudness normalisation, 1.00 = station left alone. See loudGain in fm_dsp.cpp. */
    @Volatile var loudnessGain = 1f

    /** RDS health — the numbers that say whether text can arrive at all. */
    @Volatile var rdsSynced = false
    /** Blocks failing CRC right now, averaged over ~2 s. Reception quality. */
    @Volatile var rdsBerPct = 0f
    /** Same since the decoder started, acquisition search included. Not a quality measure. */
    @Volatile var rdsBerLifetimePct = 0f
    @Volatile var rdsGroups = 0L
    /** Wideband packets the RDS thread could not keep up with; any at all breaks sync. */
    @Volatile var rdsDropped = 0L
    /** Drops before the RDS thread was scheduled — expected, harmless. */
    @Volatile var rdsDroppedAtStart = 0L
    /** Syndrome-match rates while searching — see RdsDecoder.processBit. */
    @Volatile var rdsSearch = ""
    @Volatile var rdsPs = ""
    @Volatile var rdsRt = ""

    /** Exactly what was written to the dial, for diagnosing the display itself. */
    @Volatile var freqText = ""

    /** Last "now playing" line handed to the car — see FmRadioService.publishMetadata. */
    @Volatile var clusterLine = ""

    /**
     * Every package that has connected to the media browser, in order.
     *
     * This is the one question that could not be answered from here: which
     * component, if any, the instrument cluster uses to read what is playing.
     * If it connects, it names itself, and the guessing stops.
     */
    @Volatile var browserClients = ""

    @Synchronized fun noteBrowserClient(pkg: String) {
        if (pkg.isBlank() || browserClients.split(", ").contains(pkg)) return
        browserClients = if (browserClients.isBlank()) pkg else "$browserClients, $pkg"
    }

    @Volatile var lastError = ""

    fun radio(): String =
        if (!playing) "playback stopped (dial shows '" + freqText + "')"
        else ("freq=%.2fMHz sig=%.1fdB %s | gain=step %d rms=%.3f clip=%.3f%% | " +
              "noise=%.4f blend=%.2f hicut=%.0fHz | iq=%d dsp=%s")
            .format(frequencyHz / 1e6, signalDb, if (stereo) "STEREO" else "MONO",
                    gainStep, adcRms, adcClipPct, noiseLevel, stereoBlend, hiCutHz,
                    iqQueueDepth, if (nativeDsp) "native" else "kotlin") +
              " | audio: underruns=%d buf=%dB real=%dframes | nb=%d limiter=%.2f%% loud=%.2fx".format(audioUnderruns, audioBufferBytes, audioBufferFrames, blanked, softClipPct, loudnessGain) +
              " | dial='" + freqText + "' cluster='" + clusterLine + "'" +
              " | mediabrowser: " + (if (browserClients.isBlank()) "NOBODY CONNECTED" else browserClients)

    fun rds(): String =
        "synced=%s BERnow=%.1f%% BERlife=%.1f%% groups=%d dropped=%d(+%d при старте) PS='%s' RT='%s'"
            .format(rdsSynced, rdsBerPct, rdsBerLifetimePct, rdsGroups, rdsDropped, rdsDroppedAtStart, rdsPs, rdsRt) +
            (if (rdsSearch.isNotBlank()) "\n     search: $rdsSearch" else "")
}
