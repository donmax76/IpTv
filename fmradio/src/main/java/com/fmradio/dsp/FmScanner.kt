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

        try {
            if (device.isStreaming) {
                device.stopStreaming()
                delay(100)
            }

            device.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
            device.setAutoGain(true)
            delay(50)

            // Measure noise floor
            var noiseFloor = -30f
            val noiseFreq = (startFreq - step).coerceAtLeast(RTL_SDR_MIN_FREQ)
            device.setFrequency(noiseFreq)
            delay(SETTLE_TIME_MS)
            device.resetBuffer()
            val noiseSamples = device.readSamples(MEASUREMENT_SAMPLES)
            if (noiseSamples != null) {
                noiseFloor = demodulator.measureSignalStrength(noiseSamples)
            }

            val adaptiveThreshold = maxOf(threshold, noiseFloor + 6f)
            Log.i(TAG, "Noise floor: $noiseFloor dB, threshold: $adaptiveThreshold dB")

            var freq = startFreq
            while (freq <= endFreq && scanning) {
                device.setFrequency(freq)
                delay(SETTLE_TIME_MS)
                device.resetBuffer()

                var signalSum = 0f
                var validMeasurements = 0

                for (m in 0 until MEASUREMENTS_PER_FREQ) {
                    val samples = device.readSamples(MEASUREMENT_SAMPLES)
                    if (samples != null && samples.isNotEmpty()) {
                        signalSum += demodulator.measureSignalStrength(samples)
                        validMeasurements++
                    }
                }

                if (validMeasurements > 0) {
                    val avgSignal = signalSum / validMeasurements
                    if (avgSignal > adaptiveThreshold) {
                        val result = ScanResult(freq, avgSignal)
                        stations.add(result)
                        Log.i(TAG, "Signal found: ${result.displayFrequency} MHz ($avgSignal dB)")
                        withContext(Dispatchers.Main) { listener.onStationFound(result) }
                    }
                }

                currentStep++
                val progress = currentStep.toFloat() / totalSteps
                withContext(Dispatchers.Main) { listener.onScanProgress(freq, progress) }

                freq += step
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

        val mergedStations = mergeCloseStations(stations, step * 2)

        Log.i(TAG, "Scan complete. Found ${mergedStations.size} signals")
        withContext(Dispatchers.Main) { listener.onScanComplete(mergedStations) }
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

    fun isScanning(): Boolean = scanning

    /** True while the scan coroutine is actively using the device */
    @Volatile
    var isBusy = false
        private set

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
