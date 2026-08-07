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

    /**
     * Raw USB buffers thrown away because the DSP could not take them.
     *
     * This has to be in the report, not only in a debug log nobody has switched
     * on. It went from never happening to bursts of seven after every retune
     * between two builds, and the only reason it was caught is that the user
     * happened to send a log. Each one is 17 ms of missing signal and a
     * discontinuity the RDS decoder cannot decode across; anything but 0 here
     * means the pipeline is not keeping up and nothing downstream is
     * trustworthy.
     */
    @Volatile var iqDropped = 0L
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

    /**
     * How often the converter is being driven into its rails, in 17 ms blocks.
     *
     * Not a property of the station: a field log at a steady rms of 0.19 with
     * no clipping had single blocks reading 12% and 13% clipped, seconds apart,
     * while the impulse blanker counted away in the background. That is
     * interference getting into the receiver — the car's own electrics, the
     * cable, the head unit's supply — and it is invisible in every other number
     * here because the level and the noise figure are both averages that
     * swallow it. As a percentage it says plainly whether the tuner is being
     * hit, and how hard.
     */
    @Volatile var adcBurstBlocks = 0L
    @Volatile var adcTotalBlocks = 0L

    /**
     * Level in the RDS band, on the same scale as [noiseLevel].
     *
     * The one measurement that says whether a station transmits RDS at all.
     * Below about 0.8x the noise reading there is no subcarrier and no decoder
     * can help; well above it the data is on air and any failure is ours.
     * See rdsCarrierLevel in fm_dsp.cpp.
     */
    @Volatile var rdsCarrierLevel = 0f

    /**
     * The same measurement in an empty band beside RDS, at 62 kHz.
     *
     * [rdsCarrierLevel] on its own cannot tell a subcarrier from a station
     * splashing into the band, and on 105.5 it did not: 0.0466 against a noise
     * floor of 0.0298, next to 97% block errors. Splatter and noise read the
     * same here as they do at 58.2 kHz; a subcarrier does not. The gap between
     * the two is the answer.
     */
    @Volatile var rdsShoulderLevel = 0f

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

    /** Station carries traffic bulletins at all (RDS TP bit). */
    @Volatile var rdsTp = false
    /** A bulletin is on air right now and the volume is being held up. */
    @Volatile var taActive = false
    /** Bulletins acted on since the app started — 0 says the bit never arrives. */
    @Volatile var taCount = 0

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
              " iqdropped=%d".format(iqDropped) +
              " | audio: underruns=%d buf=%dB real=%dframes | nb=%d limiter=%.2f%% loud=%.2fx".format(audioUnderruns, audioBufferBytes, audioBufferFrames, blanked, softClipPct, loudnessGain) +
              " | overload bursts: %.1f%% of blocks (%d/%d)".format(
                  if (adcTotalBlocks > 0) adcBurstBlocks * 100.0 / adcTotalBlocks else 0.0,
                  adcBurstBlocks, adcTotalBlocks) +
              " | dial='" + freqText + "' cluster='" + clusterLine + "'" +
              " | mediabrowser: " + (if (browserClients.isBlank()) "NOBODY CONNECTED" else browserClients)

    /** How far the RDS band stands above the empty band next to it, in dB. */
    private fun subcarrierDb(): Float =
        if (rdsCarrierLevel > 0f && rdsShoulderLevel > 0f)
            (20.0 * kotlin.math.log10(rdsCarrierLevel / rdsShoulderLevel)).toFloat()
        else 0f

    fun rds(): String =
        "subcarrier=%.4f shoulder=%.4f -> %+.1f dB, %s (noise=%.4f)\n     "
            .format(rdsCarrierLevel, rdsShoulderLevel, subcarrierDb(),
                    when {
                        rdsCarrierLevel <= 0f -> "not measured yet"
                        subcarrierDb() >= 6f  -> "RDS IS ON AIR — any failure is ours"
                        subcarrierDb() >= 3f  -> "weak subcarrier, marginal"
                        else -> "NO SUBCARRIER — this station sends no RDS"
                    }, noiseLevel) +
        "synced=%s BERnow=%.1f%% BERlife=%.1f%% groups=%d dropped=%d(+%d при старте) PS='%s' RT='%s'"
            .format(rdsSynced, rdsBerPct, rdsBerLifetimePct, rdsGroups, rdsDropped, rdsDroppedAtStart, rdsPs, rdsRt) +
            "\n     traffic: TP=%s TA=%s announcements=%d".format(rdsTp, taActive, taCount) +
            (if (rdsSearch.isNotBlank()) "\n     search: $rdsSearch" else "")
}
