package com.fmradio.dsp

import android.util.Log
import com.fmradio.rtlsdr.RtlSdrDevice
import kotlinx.coroutines.*

/**
 * FM band auto-scanner. Scans 87.5 - 108.0 MHz in 100 kHz steps,
 * measures signal strength at each frequency, and returns stations found.
 */
class FmScanner(private val device: RtlSdrDevice) {

    companion object {
        private const val TAG = "FmScanner"

        // FM broadcast band limits (Hz)
        const val FM_BAND_START = 87500000L   // 87.5 MHz
        const val FM_BAND_END = 108000000L    // 108.0 MHz
        const val FM_STEP = 100000L           // 100 kHz step

        // Signal threshold for station detection (dB)
        private const val SIGNAL_THRESHOLD = -15f

        // Settling time after frequency change (ms)
        private const val SETTLE_TIME_MS = 80L

        // Number of samples to read for measurement
        private const val MEASUREMENT_SAMPLES = 65536

        // Number of measurements to average per frequency
        private const val MEASUREMENTS_PER_FREQ = 3

        // Noise floor samples at scan start
        private const val NOISE_FLOOR_SAMPLES = 5
    }

    data class ScanResult(
        val frequencyHz: Long,
        val signalStrength: Float
    ) {
        val frequencyMHz: Double get() = frequencyHz / 1_000_000.0

        val displayFrequency: String
            get() = String.format("%.1f", frequencyMHz)
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
     * Scan the entire FM band and return found stations.
     */
    suspend fun scan(
        listener: ScanListener,
        startFreq: Long = FM_BAND_START,
        endFreq: Long = FM_BAND_END,
        step: Long = FM_STEP,
        threshold: Float = SIGNAL_THRESHOLD
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        scanning = true
        val stations = mutableListOf<ScanResult>()

        val totalSteps = ((endFreq - startFreq) / step).toInt()
        var currentStep = 0

        Log.i(TAG, "Starting FM scan: ${startFreq/1e6} - ${endFreq/1e6} MHz, step=${step/1e3} kHz")

        try {
            // Stop any active streaming first
            if (device.isStreaming) {
                device.stopStreaming()
                delay(100)
            }

            // Configure device for scanning
            device.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
            device.setAutoGain(true)
            delay(50)

            // Measure noise floor at edges of band (where no stations expected)
            var noiseFloor = -30f
            val noiseFreqs = listOf(87400000L, 108100000L)
            var noiseSum = 0f
            var noiseMeasurements = 0
            for (freq in noiseFreqs) {
                device.setFrequency(freq)
                delay(SETTLE_TIME_MS)
                device.resetBuffer()
                val samples = device.readSamples(MEASUREMENT_SAMPLES)
                if (samples != null) {
                    noiseSum += demodulator.measureSignalStrength(samples)
                    noiseMeasurements++
                }
            }
            if (noiseMeasurements > 0) {
                noiseFloor = noiseSum / noiseMeasurements
                Log.i(TAG, "Measured noise floor: $noiseFloor dB")
            }

            // Use adaptive threshold: noise floor + margin
            val adaptiveThreshold = maxOf(threshold, noiseFloor + 6f)
            Log.i(TAG, "Using scan threshold: $adaptiveThreshold dB")

            var freq = startFreq
            while (freq <= endFreq && scanning) {
                // Set frequency
                device.setFrequency(freq)

                // Wait for PLL to settle
                delay(SETTLE_TIME_MS)

                // Clear buffer and take multiple measurements
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
                        Log.i(TAG, "Station found: ${result.displayFrequency} MHz ($avgSignal dB)")

                        withContext(Dispatchers.Main) {
                            listener.onStationFound(result)
                        }
                    }
                }

                currentStep++
                val progress = currentStep.toFloat() / totalSteps

                withContext(Dispatchers.Main) {
                    listener.onScanProgress(freq, progress)
                }

                freq += step
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan error", e)
            withContext(Dispatchers.Main) {
                listener.onScanError(e.message ?: "Unknown error")
            }
        }

        scanning = false

        // Merge close frequencies (within 200 kHz)
        val mergedStations = mergeCloseStations(stations)

        Log.i(TAG, "Scan complete. Found ${mergedStations.size} stations")

        withContext(Dispatchers.Main) {
            listener.onScanComplete(mergedStations)
        }

        mergedStations
    }

    fun stopScan() {
        scanning = false
    }

    fun isScanning(): Boolean = scanning

    /**
     * Merge stations that are within 200 kHz of each other,
     * keeping the one with stronger signal.
     */
    private fun mergeCloseStations(stations: List<ScanResult>): List<ScanResult> {
        if (stations.isEmpty()) return emptyList()

        val sorted = stations.sortedBy { it.frequencyHz }
        val merged = mutableListOf(sorted[0])

        for (i in 1 until sorted.size) {
            val last = merged.last()
            if (sorted[i].frequencyHz - last.frequencyHz < 200000) {
                // Keep the stronger signal
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
