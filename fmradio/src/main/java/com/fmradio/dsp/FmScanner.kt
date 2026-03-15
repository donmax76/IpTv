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
        private const val SIGNAL_THRESHOLD = -20f

        // Settling time after frequency change (ms)
        private const val SETTLE_TIME_MS = 50L

        // Number of samples to read for measurement
        private const val MEASUREMENT_SAMPLES = 32768
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

        // Set optimal sample rate for scanning
        device.setSampleRate(RtlSdrDevice.DEFAULT_SAMPLE_RATE)
        device.setAutoGain(true)

        var freq = startFreq
        while (freq <= endFreq && scanning) {
            // Set frequency
            device.setFrequency(freq)

            // Wait for PLL to settle
            delay(SETTLE_TIME_MS)

            // Clear buffer and read fresh samples
            device.resetBuffer()
            val samples = device.readSamples(MEASUREMENT_SAMPLES)

            if (samples != null) {
                val signalStrength = demodulator.measureSignalStrength(samples)

                if (signalStrength > threshold) {
                    val result = ScanResult(freq, signalStrength)
                    stations.add(result)
                    Log.i(TAG, "Station found: ${result.displayFrequency} MHz (${signalStrength} dB)")

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
