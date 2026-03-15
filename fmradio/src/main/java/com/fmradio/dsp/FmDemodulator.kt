package com.fmradio.dsp

import kotlin.math.*

/**
 * FM demodulation DSP pipeline:
 * IQ samples -> Low-pass filter -> FM discriminator -> Audio decimation -> De-emphasis -> Audio output
 */
class FmDemodulator(
    private val inputSampleRate: Int = 1024000,
    private val audioSampleRate: Int = 48000
) {
    // Previous I/Q sample for FM discriminator
    private var prevI = 0f
    private var prevQ = 0f

    // De-emphasis filter state (75us for FM broadcast)
    private var deEmphasisState = 0f
    private val deEmphasisAlpha: Float

    // Decimation factor
    private val decimationFactor: Int

    // Low-pass FIR filter coefficients for IF filtering
    private val lpfCoeffs: FloatArray
    private val lpfOrder = 32
    private var lpfBufferI = FloatArray(lpfOrder)
    private var lpfBufferQ = FloatArray(lpfOrder)
    private var lpfIndex = 0

    // Audio low-pass filter
    private val audioLpfCoeffs: FloatArray
    private val audioLpfOrder = 16
    private var audioLpfBuffer = FloatArray(audioLpfOrder)
    private var audioLpfIndex = 0

    init {
        // Calculate decimation factor
        decimationFactor = inputSampleRate / audioSampleRate

        // De-emphasis: 75us time constant (used in US/Europe FM broadcast)
        val tau = 75e-6f
        val dt = 1f / audioSampleRate
        deEmphasisAlpha = dt / (tau + dt)

        // Design low-pass FIR filter for IF (cutoff at ~100kHz)
        lpfCoeffs = designLowPassFilter(lpfOrder, 100000f / inputSampleRate)

        // Audio low-pass filter (cutoff at ~15kHz)
        audioLpfCoeffs = designLowPassFilter(audioLpfOrder, 15000f / audioSampleRate)
    }

    private fun designLowPassFilter(order: Int, normalizedCutoff: Float): FloatArray {
        val coeffs = FloatArray(order)
        val mid = order / 2
        var sum = 0f

        for (i in 0 until order) {
            val n = i - mid
            coeffs[i] = if (n == 0) {
                2 * normalizedCutoff
            } else {
                sin(2 * PI.toFloat() * normalizedCutoff * n) / (PI.toFloat() * n)
            }
            // Apply Hamming window
            coeffs[i] *= (0.54f - 0.46f * cos(2 * PI.toFloat() * i / (order - 1)))
            sum += coeffs[i]
        }
        // Normalize
        for (i in coeffs.indices) {
            coeffs[i] /= sum
        }
        return coeffs
    }

    /**
     * Demodulate raw IQ samples (interleaved unsigned 8-bit) to audio PCM (16-bit).
     */
    fun demodulate(iqData: ByteArray): ShortArray {
        val numSamples = iqData.size / 2
        val audioSamples = mutableListOf<Short>()

        var decimCounter = 0

        for (i in 0 until numSamples) {
            // Convert unsigned 8-bit to float (-1.0 to 1.0)
            val iSample = (iqData[i * 2].toInt() and 0xFF) / 127.5f - 1f
            val qSample = (iqData[i * 2 + 1].toInt() and 0xFF) / 127.5f - 1f

            // Apply IF low-pass filter
            lpfBufferI[lpfIndex] = iSample
            lpfBufferQ[lpfIndex] = qSample

            var filteredI = 0f
            var filteredQ = 0f
            for (j in 0 until lpfOrder) {
                val idx = (lpfIndex - j + lpfOrder) % lpfOrder
                filteredI += lpfBufferI[idx] * lpfCoeffs[j]
                filteredQ += lpfBufferQ[idx] * lpfCoeffs[j]
            }
            lpfIndex = (lpfIndex + 1) % lpfOrder

            // Decimation - only process every Nth sample
            decimCounter++
            if (decimCounter < decimationFactor) continue
            decimCounter = 0

            // FM discriminator (atan2 of conjugate product)
            val realProduct = filteredI * prevI + filteredQ * prevQ
            val imagProduct = filteredQ * prevI - filteredI * prevQ
            prevI = filteredI
            prevQ = filteredQ

            var audio = atan2(imagProduct, realProduct) / PI.toFloat()

            // De-emphasis filter
            deEmphasisState += deEmphasisAlpha * (audio - deEmphasisState)
            audio = deEmphasisState

            // Audio low-pass filter
            audioLpfBuffer[audioLpfIndex] = audio
            var filteredAudio = 0f
            for (j in 0 until audioLpfOrder) {
                val idx = (audioLpfIndex - j + audioLpfOrder) % audioLpfOrder
                filteredAudio += audioLpfBuffer[idx] * audioLpfCoeffs[j]
            }
            audioLpfIndex = (audioLpfIndex + 1) % audioLpfOrder

            // Scale and clip to 16-bit PCM
            val pcmSample = (filteredAudio * 24000).toInt().coerceIn(-32768, 32767)
            audioSamples.add(pcmSample.toShort())
        }

        return audioSamples.toShortArray()
    }

    /**
     * Measure signal strength from IQ samples (for scanner).
     * Returns power in dB-like units.
     */
    fun measureSignalStrength(iqData: ByteArray): Float {
        if (iqData.isEmpty()) return -100f

        var powerSum = 0.0
        val numSamples = iqData.size / 2

        for (i in 0 until numSamples) {
            val iVal = (iqData[i * 2].toInt() and 0xFF) / 127.5f - 1f
            val qVal = (iqData[i * 2 + 1].toInt() and 0xFF) / 127.5f - 1f
            powerSum += (iVal * iVal + qVal * qVal).toDouble()
        }

        val avgPower = powerSum / numSamples
        return (10 * log10(avgPower + 1e-10)).toFloat()
    }

    fun reset() {
        prevI = 0f
        prevQ = 0f
        deEmphasisState = 0f
        lpfBufferI = FloatArray(lpfOrder)
        lpfBufferQ = FloatArray(lpfOrder)
        lpfIndex = 0
        audioLpfBuffer = FloatArray(audioLpfOrder)
        audioLpfIndex = 0
    }
}
