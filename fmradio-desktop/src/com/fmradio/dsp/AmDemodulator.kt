package com.fmradio.dsp

import kotlin.math.*

/**
 * AM demodulator for shortwave (SW/HF) and air band reception.
 *
 * IQ samples -> DC removal -> IF LPF -> Decimate -> Envelope detection -> AGC ->
 *   Audio LPF -> DC removal -> Noise gate -> Output
 */
class AmDemodulator(
    private val inputSampleRate: Int = 1152000,
    private val audioSampleRate: Int = 48000
) {
    private val decimationFactor = inputSampleRate / audioSampleRate

    // DC removal on IQ input
    private var dcI = 0f
    private var dcQ = 0f
    private val dcAlpha = 0.998f

    // AGC (Automatic Gain Control)
    private var agcGain = 10.0f
    private val agcTarget = 0.4f
    private val agcAttack = 0.005f
    private val agcDecay = 0.0002f

    // IF low-pass filter (before decimation, at input sample rate)
    private val ifLpfOrder = 64
    private val ifLpfCoeffs: FloatArray
    private var ifBufI = FloatArray(ifLpfOrder)
    private var ifBufQ = FloatArray(ifLpfOrder)
    private var ifBufIdx = 0

    // Audio low-pass filter (after decimation, at audio sample rate)
    private val audioLpfOrder = 32
    private val audioLpfCoeffs: FloatArray
    private var audioLpfBuf = FloatArray(audioLpfOrder)
    private var audioLpfIdx = 0

    // DC removal on audio output
    private var audioDcState = 0f
    private val audioDcAlpha = 0.995f

    // Noise gate
    private var noiseFloor = 0.01f
    private var noiseGateGain = 0f
    private val noiseGateAttack = 0.05f
    private val noiseGateRelease = 0.002f

    private var decimCounter = 0

    init {
        // IF filter: 8 kHz cutoff at input sample rate (passes AM signal bandwidth)
        val ifCutoff = 8000f / (inputSampleRate.toFloat() / 2f)
        ifLpfCoeffs = designLowPassFilter(ifLpfOrder, ifCutoff)

        // Audio LPF: 4 kHz cutoff at audio sample rate (smooth output)
        val audioCutoff = 4000f / (audioSampleRate.toFloat() / 2f)
        audioLpfCoeffs = designLowPassFilter(audioLpfOrder, audioCutoff)
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

            // IF low-pass filter (at input rate, before decimation)
            ifBufI[ifBufIdx] = iSample
            ifBufQ[ifBufIdx] = qSample
            ifBufIdx = (ifBufIdx + 1) % ifLpfOrder

            // Decimate
            decimCounter++
            if (decimCounter < decimationFactor) continue
            decimCounter = 0

            // Apply IF filter
            var filtI = 0f
            var filtQ = 0f
            for (j in 0 until ifLpfOrder) {
                val idx = (ifBufIdx - 1 - j + ifLpfOrder) % ifLpfOrder
                filtI += ifBufI[idx] * ifLpfCoeffs[j]
                filtQ += ifBufQ[idx] * ifLpfCoeffs[j]
            }

            // AM envelope detection: magnitude of filtered IQ vector
            val envelope = sqrt(filtI * filtI + filtQ * filtQ)

            // AGC
            val level = envelope * agcGain
            if (level > agcTarget) {
                agcGain -= agcAttack * (level - agcTarget)
            } else {
                agcGain += agcDecay * (agcTarget - level)
            }
            agcGain = agcGain.coerceIn(0.1f, 200f)

            val audio = envelope * agcGain

            // Audio LPF (at audio rate)
            audioLpfBuf[audioLpfIdx] = audio
            audioLpfIdx = (audioLpfIdx + 1) % audioLpfOrder

            var filtAudio = 0f
            for (j in 0 until audioLpfOrder) {
                val idx = (audioLpfIdx - 1 - j + audioLpfOrder) % audioLpfOrder
                filtAudio += audioLpfBuf[idx] * audioLpfCoeffs[j]
            }

            // Remove DC from audio
            audioDcState = audioDcAlpha * audioDcState + (1 - audioDcAlpha) * filtAudio
            val out = filtAudio - audioDcState

            // Noise gate: suppress output when signal is below noise floor
            val absOut = if (out < 0) -out else out
            if (absOut > noiseFloor * 3f) {
                noiseGateGain += noiseGateAttack * (1f - noiseGateGain)
            } else {
                noiseGateGain -= noiseGateRelease * noiseGateGain
            }
            noiseGateGain = noiseGateGain.coerceIn(0f, 1f)

            // Update noise floor estimate (slow tracking)
            noiseFloor = 0.999f * noiseFloor + 0.001f * absOut.coerceAtMost(0.1f)

            val gated = out * noiseGateGain

            // Scale to 16-bit
            val scaled = (gated * 28000f).coerceIn(-30000f, 30000f)
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
        agcGain = 10.0f
        ifBufI = FloatArray(ifLpfOrder); ifBufQ = FloatArray(ifLpfOrder); ifBufIdx = 0
        audioLpfBuf = FloatArray(audioLpfOrder); audioLpfIdx = 0
        audioDcState = 0f
        decimCounter = 0
        noiseFloor = 0.01f; noiseGateGain = 0f
    }
}
