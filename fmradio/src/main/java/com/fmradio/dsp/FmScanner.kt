package com.fmradio.dsp

import android.util.Log
import com.fmradio.rtlsdr.RtlSdrDevice
import kotlinx.coroutines.*

/**
 * Wideband radio scanner with configurable band presets.
 * Supports full RTL-SDR R820T range: 24 MHz - 1766 MHz.
 */
class FmScanner(private val device: RtlSdrDevice) {

    companion object {
        private const val TAG = "FmScanner"

        // Legacy FM band constants (kept for compatibility)
        const val FM_BAND_START = 87500000L
        const val FM_BAND_END = 108000000L
        const val FM_STEP = 100000L

        // Full RTL-SDR R820T tuner range
        const val RTL_SDR_MIN_FREQ = 24000000L      // 24 MHz
        const val RTL_SDR_MAX_FREQ = 1766000000L     // 1766 MHz

        // Signal threshold for station detection (dB)
        private const val SIGNAL_THRESHOLD = -15f
        // How far above the measured noise floor a frequency must sit to count
        // as a station. 6 dB is the usual rule and is what the code always
        // intended; it simply never got to apply it.
        private const val SIGNAL_MARGIN_DB = 6f
        // Modelled against a synthetic FM band with neighbours at +/-200 and
        // +/-400 kHz, quantised to 8 bits as the ADC delivers:
        //
        //   station strong .. very weak   -4 .. -18 dB
        //   empty, neighbours present          -33 dB
        //
        // -20 dB sits below the weakest station and 14 dB above an empty
        // channel. Pure noise reads about -8 dB here — it is the absolute level
        // test, ANDed with this one, that rejects that case.
        private const val CHANNEL_RATIO_DB = -20.0f
        // Sanity floor only. Nothing real is ever this quiet, so this exists
        // purely so a nonsense noise-floor reading cannot make the scan accept
        // every frequency.
        private const val ABSOLUTE_FLOOR_DB = -55f
        private const val SETTLE_TIME_MS = 80L
        private const val MEASUREMENT_SAMPLES = 65536
        private const val MEASUREMENTS_PER_FREQ = 3
    }

    /**
     * Radio band definitions covering the full RTL-SDR range.
     */
    enum class Band(
        val displayName: String,
        val shortName: String,
        val startHz: Long,
        val endHz: Long,
        val stepHz: Long,
        val description: String
    ) {
        FM_BROADCAST(
            "FM Radio", "FM",
            87500000L, 108000000L, 100000L,
            "FM Broadcast 87.5-108.0 MHz"
        ),
        FM_JAPAN(
            "FM Japan", "FM-J",
            76000000L, 95000000L, 100000L,
            "Japanese FM 76.0-95.0 MHz"
        ),
        AM_SHORTWAVE(
            "Shortwave", "SW",
            24000000L, 30000000L, 5000L,
            "HF Shortwave 24-30 MHz (limited)"
        ),
        VHF_LOW(
            "VHF Low", "VHF-L",
            30000000L, 50000000L, 12500L,
            "VHF Low Band 30-50 MHz"
        ),
        VHF_6M(
            "6m Amateur", "6M",
            50000000L, 54000000L, 5000L,
            "6 Meter Amateur Radio 50-54 MHz"
        ),
        TV_VHF(
            "TV VHF", "TV-V",
            54000000L, 88000000L, 250000L,
            "VHF TV channels 54-88 MHz"
        ),
        AIR_BAND(
            "Aviation", "AIR",
            108000000L, 137000000L, 25000L,
            "Aircraft AM 108-137 MHz"
        ),
        VHF_2M(
            "2m Amateur", "2M",
            144000000L, 148000000L, 12500L,
            "2 Meter Amateur Radio 144-148 MHz"
        ),
        WEATHER(
            "Weather", "WX",
            162400000L, 162550000L, 25000L,
            "NOAA Weather Radio 162.4-162.55 MHz"
        ),
        VHF_MARINE(
            "Marine VHF", "MAR",
            156000000L, 162000000L, 25000L,
            "Marine VHF 156-162 MHz"
        ),
        PMR446(
            "PMR446", "PMR",
            446006250L, 446193750L, 12500L,
            "PMR446 Walkie-Talkies 446 MHz"
        ),
        UHF_70CM(
            "70cm Amateur", "70CM",
            430000000L, 440000000L, 12500L,
            "70 Centimeter Amateur 430-440 MHz"
        ),
        UHF_TV(
            "TV UHF", "TV-U",
            470000000L, 890000000L, 250000L,
            "UHF TV channels 470-890 MHz"
        ),
        GSM900(
            "GSM 900", "GSM9",
            935000000L, 960000000L, 200000L,
            "GSM 900 Downlink 935-960 MHz"
        ),
        GSM1800(
            "GSM 1800", "G18",
            1805000000L, 1880000000L, 200000L,
            "GSM 1800 Downlink (if tuner supports)"
        ),
        ISM_433(
            "ISM 433", "433",
            433000000L, 435000000L, 10000L,
            "ISM Band 433 MHz (sensors, remotes)"
        ),
        ISM_868(
            "ISM 868", "868",
            868000000L, 870000000L, 25000L,
            "ISM Band 868 MHz (LoRa, IoT)"
        ),
        CUSTOM(
            "Custom", "USR",
            RTL_SDR_MIN_FREQ, RTL_SDR_MAX_FREQ, 100000L,
            "Full range 24-1766 MHz"
        );

        val totalSteps: Int get() = ((endHz - startHz) / stepHz).toInt()
    }

    data class ScanResult(
        val frequencyHz: Long,
        val signalStrength: Float
    ) {
        val frequencyMHz: Double get() = frequencyHz / 1_000_000.0
        val displayFrequency: String
            get() = if (frequencyHz >= 1000000000L)
                String.format("%.3f", frequencyMHz)
            else
                String.format("%.1f", frequencyMHz)
    }

    interface ScanListener {
        fun onScanProgress(currentFreqHz: Long, progress: Float)
        fun onStationFound(result: ScanResult)
        fun onScanComplete(stations: List<ScanResult>)
        fun onScanError(error: String)
    }

    @Volatile
    private var scanning = false

    @Volatile
    var isBusy = false
        private set

    fun isScanning(): Boolean = scanning

    private val demodulator = FmDemodulator()

    /**
     * Scan a frequency range and return found stations/signals.
     */
    suspend fun scan(
        listener: ScanListener,
        startFreq: Long = FM_BAND_START,
        endFreq: Long = FM_BAND_END,
        step: Long = FM_STEP,
        threshold: Float = SIGNAL_THRESHOLD
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        scanning = true
        isBusy = true
        val stations = mutableListOf<ScanResult>()
        val totalSteps = ((endFreq - startFreq) / step).toInt()
        var currentStep = 0

        Log.i(TAG, "Starting scan: ${startFreq/1e6} - ${endFreq/1e6} MHz, step=${step/1e3} kHz")

        var bestSeen = -200f
        try {
            // Stop any in-flight streaming UNCONDITIONALLY. The isStreaming flag may
            // already be false (cleared by stopPlayback) while the streaming coroutine
            // is still mid-read inside readSamples(). Both share asyncReadBuffer, so
            // racing two readers gives null/garbage and the scanner sees no signal.
            // The fullReset() below cancels in-flight URBs, drains stale data, and
            // clears endpoint stalls so the scanner has the bus to itself.
            com.fmradio.util.StartupLog.write("scan: stopStreaming")
            device.stopStreaming()
            delay(150)  // let any inflight USB read time out before we touch the bus
            com.fmradio.util.StartupLog.write("scan: fullReset")
            try { device.fullReset() } catch (e: Exception) { Log.w(TAG, "pre-scan fullReset failed", e) }

            com.fmradio.util.StartupLog.write("scan: setSampleRate")
            device.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
            // Use FIXED manual gain for scanning — auto AGC doesn't converge
            // within the 80 ms settle window per frequency.
            //
            // FC0013/FC0012 have INVERTED auto/manual logic in our setAutoGain():
            //   setAutoGain(true)  = manual mode (moderate gain, instant)  ← GOOD for scan
            //   setAutoGain(false) = auto AGC (~17 s convergence)          ← BAD for scan
            // R820T/R828D have the standard mapping:
            //   setAutoGain(false) = manual mode + setGain(14) works       ← GOOD for scan
            //   setAutoGain(true)  = hardware AGC (equalises everything)   ← BAD for scan
            when (device.getTunerType()) {
                RtlSdrDevice.TunerType.FC0013, RtlSdrDevice.TunerType.FC0012 -> {
                    // FC0013: manual mode (setAutoGain(true) = manual on FC).
                    //
                    // The LNA used to be dropped to its minimum here, on the
                    // theory that it gave better contrast between stations and
                    // noise. It does the opposite: with no RF gain ahead of the
                    // mixer the receiver's own noise dominates, every station
                    // sinks towards the floor, and the sweep finds nothing at
                    // all — which is exactly what the field reported, a scan
                    // returning zero stations.
                    //
                    // Sensitivity is what a sweep needs. LNA high, and the IF
                    // trim pinned to the same value the playback loop starts
                    // from, so one frequency is comparable with the next and
                    // with what playback will hear.
                    device.setAutoGain(true)
                    device.setGain(15)                  // LNA maximum
                    device.setFc0013IfGainStep(20)      // 40 dB, the loop's start
                }
                else -> {
                    device.setAutoGain(false) // R820T: manual mode
                    device.setGain(14)        // high fixed gain
                }
            }
            delay(80)

            // Measure noise floor
            com.fmradio.util.StartupLog.write("scan: measuring noise floor")
            var noiseFloor = -30f
            val noiseFreq = (startFreq - step).coerceAtLeast(RTL_SDR_MIN_FREQ)
            device.setFrequency(noiseFreq)
            delay(SETTLE_TIME_MS)
            device.resetBuffer()
            val noiseSamples = device.readSamples(MEASUREMENT_SAMPLES)
            if (noiseSamples != null) {
                // Measured the same way as every step of the sweep: the power
                // in the tuned channel, not in the whole window. The window
                // figure was never a noise floor — at 87.4 MHz the 960 kHz it
                // spans still holds the bottom of the band, so it read -13.9 dB
                // in the field and put the bar at -7.9 while the strongest
                // station in the city measured -6.8. One station found out of a
                // full band, and no threshold on that number could have done
                // better, because the number barely changes as you tune.
                noiseFloor = demodulator.measureChannel(noiseSamples)[0]
            }

            // Relative to the measured noise floor, NOT to an absolute number.
            //
            // This used to be maxOf(threshold, noiseFloor + 6), with threshold
            // fixed at -15 dB. But the scanner deliberately runs the tuner at
            // minimum LNA gain, so everything it measures sits far below what
            // the same station reads during playback: stations that show -8 to
            // -13 dB on air measure around -25 to -33 dB here. maxOf() then
            // discarded the adaptive value every time and left the bar at
            // -15 dB, which nothing could clear — the sweep completed and
            // reported an empty band. The absolute value survives only as a
            // sanity floor far below anything real.
            val adaptiveThreshold = maxOf(ABSOLUTE_FLOOR_DB, noiseFloor + SIGNAL_MARGIN_DB)
            Log.i(TAG, "Noise floor: $noiseFloor dB, threshold: $adaptiveThreshold dB")
            com.fmradio.util.StartupLog.write(
                "scan: noise floor %.1f dB, threshold %.1f dB".format(noiseFloor, adaptiveThreshold))

            com.fmradio.util.StartupLog.write("scan: sweep begin ${startFreq/1000}kHz..${endFreq/1000}kHz")
            val sweep = ArrayList<Triple<Long, Float, Float>>()
            var freq = startFreq
            while (freq <= endFreq && scanning) {
                device.setFrequency(freq)
                delay(SETTLE_TIME_MS)
                device.resetBuffer()

                var signalSum = 0f
                var ratioSum = 0f
                var validMeasurements = 0

                for (m in 0 until MEASUREMENTS_PER_FREQ) {
                    val samples = device.readSamples(MEASUREMENT_SAMPLES)
                    if (samples != null && samples.isNotEmpty()) {
                        val ch = demodulator.measureChannel(samples)
                        signalSum += ch[0]
                        ratioSum += ch[1]
                        validMeasurements++
                    }
                }

                if (validMeasurements > 0) {
                    val avgSignal = signalSum / validMeasurements
                    val avgRatio = ratioSum / validMeasurements
                    if (avgSignal > bestSeen) bestSeen = avgSignal
                    // Decided after the sweep, not here: see below.
                    sweep.add(Triple(freq, avgSignal, avgRatio))
                }

                currentStep++
                val progress = currentStep.toFloat() / totalSteps
                withContext(Dispatchers.Main) { listener.onScanProgress(freq, progress) }

                freq += step
            }

            // The floor comes from the band itself, taken as the median of
            // every step. A single reading at one "quiet" frequency is not a
            // floor: the field log measured -13.9 dB that way, put the bar at
            // -7.9, and the strongest station in the city came in at -6.8 — one
            // station found in a whole band. Most of the FM grid is empty even
            // in a city, so the middle of the sweep IS the noise floor, and it
            // cannot be fooled by whatever happens to sit at the edge.
            if (sweep.isNotEmpty()) {
                val median = sweep.map { it.second }.sorted()[sweep.size / 2]
                val bar = maxOf(ABSOLUTE_FLOOR_DB, median + SIGNAL_MARGIN_DB)
                com.fmradio.util.StartupLog.write(
                    ("scan: median %.1f dB, bar %.1f dB (edge sample was %.1f), " +
                     "%d steps measured").format(median, bar, noiseFloor, sweep.size))
                for ((f, lvl, ratio) in sweep) {
                    // Both have to agree. The level says something is being
                    // received at all; the ratio says it is centred HERE rather
                    // than a neighbour bleeding into the same 960 kHz window,
                    // which is the part the level alone can never tell.
                    if (lvl > bar && ratio > CHANNEL_RATIO_DB) {
                        val result = ScanResult(f, lvl)
                        stations.add(result)
                        Log.i(TAG, "Signal found: ${result.displayFrequency} MHz ($lvl dB, ratio $ratio)")
                        withContext(Dispatchers.Main) { listener.onStationFound(result) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan error", e)
            withContext(Dispatchers.Main) { listener.onScanError(e.message ?: "Unknown error") }
        }

        scanning = false
        isBusy = false

        // Full USB reset after scan to ensure clean state for playback
        try {
            device.fullReset()
        } catch (e: Exception) {
            Log.w(TAG, "Full reset after scan failed", e)
        }

        com.fmradio.util.StartupLog.write(
            "scan: sweep done, ${stations.size} found, strongest seen %.1f dB".format(bestSeen))
        val mergedStations = mergeCloseStations(stations, step * 2)

        Log.i(TAG, "Scan complete. Found ${mergedStations.size} signals")
        // The completion callback runs OUTSIDE the loop's try/catch, so an
        // exception in the UI here would reach the coroutine scope uncaught and
        // kill the app. Report it as a scan error instead.
        try {
            withContext(Dispatchers.Main) { listener.onScanComplete(mergedStations) }
        } catch (e: Throwable) {
            com.fmradio.util.StartupLog.write("scan: onScanComplete FAILED: $e")
            Log.e(TAG, "onScanComplete failed", e)
            try { withContext(Dispatchers.Main) { listener.onScanError(e.message ?: "complete failed") } } catch (_: Throwable) {}
        }
        com.fmradio.util.StartupLog.write("scan: finished")
        mergedStations
    }

    /** Scan a specific Band enum */
    suspend fun scanBand(band: Band, listener: ScanListener): List<ScanResult> {
        return scan(listener, band.startHz, band.endHz, band.stepHz)
    }

    fun stopScan() {
        scanning = false
    }

    /** Stop scan and wait for the scan coroutine to fully exit */
    suspend fun stopScanAndWait() {
        scanning = false
        // Give the scan loop time to exit (it may be blocking on USB read)
        withContext(Dispatchers.IO) {
            var waitMs = 0
            while (isBusy && waitMs < 2000) {
                delay(50)
                waitMs += 50
            }
        }
    }

    private fun mergeCloseStations(stations: List<ScanResult>, minSpacing: Long): List<ScanResult> {
        if (stations.isEmpty()) return emptyList()
        val sorted = stations.sortedBy { it.frequencyHz }
        val merged = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val last = merged.last()
            if (sorted[i].frequencyHz - last.frequencyHz < minSpacing) {
                if (sorted[i].signalStrength > last.signalStrength) {
                    merged[merged.lastIndex] = sorted[i]
                }
            } else {
                merged.add(sorted[i])
            }
        }
        return merged
    }
}
