package com.fmradio.dsp

import kotlin.math.*

/**
 * AM demodulator for shortwave (SW/HF) and air band reception.
 *
 * IQ samples -> DC removal -> Envelope detection -> AGC -> Audio LPF -> Decimate -> Output
 *
 * Supports AM (envelope) and DSB (double sideband) modes.
 */
class AmDemodulator(
    private val inputSampleRate: Int = 1152000,
    private val audioSampleRate: Int = 48000
) {
    private val decimationFactor = inputSampleRate / audioSampleRate

    // DC removal
    private var dcI = 0f
    private var dcQ = 0f
    private val dcAlpha = 0.998f

    // AGC (Automatic Gain Control)
    private var agcGain = 1.0f
    private val agcTarget = 0.3f
    private val agcAttack = 0.01f   // fast attack
    private val agcDecay = 0.0001f  // slow decay

    // Audio low-pass filter
    private val audioLpfOrder = 48
    private val audioLpfCoeffs: FloatArray
    private var audioLpfBuf = FloatArray(audioLpfOrder)
    private var audioLpfIdx = 0

    // DC removal on audio output
    private var audioDcState = 0f
    private val audioDcAlpha = 0.995f

    private var decimCounter = 0

    init {
        // Audio LPF: cutoff at 5kHz for AM (narrower than FM)
        val cutoff = 5000f / (inputSampleRate.toFloat() / 2f)
        audioLpfCoeffs = designLowPassFilter(audioLpfOrder, cutoff)
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
            // Blackman-Harris window
            val w = i.toFloat() / (order - 1).toFloat()
            val a0 = 0.35875f; val a1 = 0.48829f; val a2 = 0.14128f; val a3 = 0.01168f
            coeffs[i] *= a0 - a1 * cos(2 * PI.toFloat() * w) +
                    a2 * cos(4 * PI.toFloat() * w) - a3 * cos(6 * PI.toFloat() * w)
            sum += coeffs[i]
        }
        for (i in coeffs.indices) coeffs[i] /= sum
        return coeffs
    }

    fun demodulate(iqData: ByteArray): ShortArray {
        val numIqSamples = iqData.size / 2
        val maxAudioSamples = numIqSamples / decimationFactor + 2
        val audioOut = ShortArray(maxAudioSamples)
        var audioCount = 0

        for (i in 0 until numIqSamples) {
            var iSample = (iqData[i * 2].toInt() and 0xFF) / 127.5f - 1f
            var qSample = (iqData[i * 2 + 1].toInt() and 0xFF) / 127.5f - 1f

            // DC removal
            dcI = dcAlpha * dcI + (1 - dcAlpha) * iSample
            dcQ = dcAlpha * dcQ + (1 - dcAlpha) * qSample
            iSample -= dcI
            qSample -= dcQ

            // AM envelope detection: magnitude of IQ vector
            val envelope = sqrt(iSample * iSample + qSample * qSample)

            // AGC
            val level = envelope * agcGain
            if (level > agcTarget) {
                agcGain -= agcAttack * (level - agcTarget)
            } else {
                agcGain += agcDecay * (agcTarget - level)
            }
            agcGain = agcGain.coerceIn(0.01f, 100f)

            val audio = envelope * agcGain

            // Audio LPF
            audioLpfBuf[audioLpfIdx] = audio
            audioLpfIdx = (audioLpfIdx + 1) % audioLpfOrder

            // Decimate
            decimCounter++
            if (decimCounter < decimationFactor) continue
            decimCounter = 0

            var filtAudio = 0f
            for (j in 0 until audioLpfOrder) {
                val idx = (audioLpfIdx - 1 - j + audioLpfOrder) % audioLpfOrder
                filtAudio += audioLpfBuf[idx] * audioLpfCoeffs[j]
            }

            // Remove DC from audio
            audioDcState = audioDcAlpha * audioDcState + (1 - audioDcAlpha) * filtAudio
            val out = filtAudio - audioDcState

            // Scale to 16-bit
            val scaled = (out * 30000f).coerceIn(-30000f, 30000f)
            if (audioCount < audioOut.size) {
                audioOut[audioCount++] = scaled.toInt().toShort()
            }
        }

        return if (audioCount == audioOut.size) audioOut else audioOut.copyOf(audioCount)
    }

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
        dcI = 0f; dcQ = 0f
        agcGain = 1.0f
        audioLpfBuf = FloatArray(audioLpfOrder); audioLpfIdx = 0
        audioDcState = 0f
        decimCounter = 0
    }
}
