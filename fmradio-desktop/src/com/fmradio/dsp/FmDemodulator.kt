package com.fmradio.dsp

import kotlin.math.*

/**
 * High-quality FM demodulation DSP pipeline with multi-stage decimation:
 *
 * IQ samples (1152 kHz) -> DC removal -> IF LPF -> Decimate /6 -> FM discriminator (192 kHz)
 *   -> Wideband baseband output (for RDS decoder at 192 kHz)
 *   -> Audio LPF -> Decimate /4 -> De-emphasis -> Soft limiter -> Output audio (48 kHz)
 */
class FmDemodulator(
    private val inputSampleRate: Int = RECOMMENDED_SAMPLE_RATE,
    private val audioSampleRate: Int = 48000
) {
    companion object {
        const val RECOMMENDED_SAMPLE_RATE = 1152000
    }

    private val stage1Decimation = 6
    private val intermediateRate: Int = inputSampleRate / stage1Decimation
    private val stage2Decimation: Int = intermediateRate / audioSampleRate

    // DC removal (fast-settling IIR)
    private var dcI = 0f
    private var dcQ = 0f
    private val dcAlpha = 0.999f

    // FM discriminator state
    private var prevI = 0f
    private var prevQ = 0f

    // De-emphasis filter (50us, single stage)
    private var deEmphasisState = 0f
    private val deEmphasisAlpha: Float

    // IF low-pass filter (before stage 1 decimation)
    private val ifLpfOrder = 96
    private val ifLpfCoeffs: FloatArray
    private var ifBufI = FloatArray(ifLpfOrder)
    private var ifBufQ = FloatArray(ifLpfOrder)
    private var ifBufIdx = 0

    // Audio low-pass filter (before stage 2 decimation)
    private val audioLpfOrder = 64
    private val audioLpfCoeffs: FloatArray
    private var audioLpfBuf = FloatArray(audioLpfOrder)
    private var audioLpfIdx = 0

    private var stage1Counter = 0
    private var stage2Counter = 0

    // Wideband output for RDS
    var widebandListener: ((FloatArray) -> Unit)? = null

    // Stereo pilot tone detection
    private var pilotPhase = 0.0
    private val pilotIncrement = 2.0 * PI * 19000.0 / intermediateRate
    private var pilotEnergy = 0f
    private var pilotSampleCount = 0
    private val pilotDetectWindow = intermediateRate / 4

    @Volatile
    var isStereo = false
        private set

    // Output smoothing (prevents inter-chunk clicks)
    private var lastOutputSample = 0f

    init {
        // De-emphasis: 50us time constant (Europe/Russia standard)
        val tau = 50e-6f
        val dt = 1f / audioSampleRate
        deEmphasisAlpha = dt / (tau + dt)

        // IF filter: 100 kHz cutoff at input rate (wide enough for FM signal + RDS)
        ifLpfCoeffs = designLowPassFilter(ifLpfOrder, 100000f / inputSampleRate)
        // Audio filter: 15 kHz cutoff at intermediate rate
        audioLpfCoeffs = designLowPassFilter(audioLpfOrder, 15000f / intermediateRate)
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
        val maxAudioSamples = numIqSamples / (stage1Decimation * stage2Decimation) + 2
        val audioOut = ShortArray(maxAudioSamples)
        var audioCount = 0

        val wbListener = widebandListener
        val maxWbSamples = numIqSamples / stage1Decimation + 2
        val widebandBuf = if (wbListener != null) FloatArray(maxWbSamples) else null
        var wbCount = 0

        for (i in 0 until numIqSamples) {
            var iSample = (iqData[i * 2].toInt() and 0xFF) / 127.5f - 1f
            var qSample = (iqData[i * 2 + 1].toInt() and 0xFF) / 127.5f - 1f

            // DC removal
            dcI = dcAlpha * dcI + (1 - dcAlpha) * iSample
            dcQ = dcAlpha * dcQ + (1 - dcAlpha) * qSample
            iSample -= dcI
            qSample -= dcQ

            // Store in IF filter buffer
            ifBufI[ifBufIdx] = iSample
            ifBufQ[ifBufIdx] = qSample
            ifBufIdx = (ifBufIdx + 1) % ifLpfOrder

            // Stage 1 decimation
            stage1Counter++
            if (stage1Counter < stage1Decimation) continue
            stage1Counter = 0

            // Apply IF LPF
            var filtI = 0f
            var filtQ = 0f
            for (j in 0 until ifLpfOrder) {
                val idx = (ifBufIdx - 1 - j + ifLpfOrder) % ifLpfOrder
                filtI += ifBufI[idx] * ifLpfCoeffs[j]
                filtQ += ifBufQ[idx] * ifLpfCoeffs[j]
            }

            // FM discriminator (complex conjugate multiplication + atan2)
            val realProd = filtI * prevI + filtQ * prevQ
            val imagProd = filtQ * prevI - filtI * prevQ
            prevI = filtI
            prevQ = filtQ

            val baseband = atan2(imagProd, realProd) / PI.toFloat()

            // Pilot tone detection for stereo indicator
            val pilotCorr = baseband * cos(pilotPhase).toFloat()
            pilotPhase += pilotIncrement
            if (pilotPhase > 2 * PI) pilotPhase -= 2 * PI
            pilotEnergy += pilotCorr * pilotCorr
            pilotSampleCount++
            if (pilotSampleCount >= pilotDetectWindow) {
                val avgEnergy = pilotEnergy / pilotSampleCount
                isStereo = avgEnergy > 0.001f
                pilotEnergy = 0f
                pilotSampleCount = 0
            }

            // Wideband output for RDS decoder (at 192 kHz)
            if (widebandBuf != null && wbCount < widebandBuf.size) {
                widebandBuf[wbCount++] = baseband
            }

            // Audio low-pass filter
            audioLpfBuf[audioLpfIdx] = baseband
            audioLpfIdx = (audioLpfIdx + 1) % audioLpfOrder

            // Stage 2 decimation
            stage2Counter++
            if (stage2Counter < stage2Decimation) continue
            stage2Counter = 0

            // Apply audio LPF
            var filtAudio = 0f
            for (j in 0 until audioLpfOrder) {
                val idx = (audioLpfIdx - 1 - j + audioLpfOrder) % audioLpfOrder
                filtAudio += audioLpfBuf[idx] * audioLpfCoeffs[j]
            }

            // De-emphasis filter (50us)
            deEmphasisState += deEmphasisAlpha * (filtAudio - deEmphasisState)
            var audio = deEmphasisState

            // Scale to 16-bit with soft limiter (tanh prevents harsh clipping)
            val raw = audio * 30000f
            val limited = 30000f * tanh(raw / 30000f)

            // Minimal smoothing to prevent inter-chunk clicks
            val smoothed = 0.98f * limited + 0.02f * lastOutputSample
            lastOutputSample = smoothed

            if (audioCount < audioOut.size) {
                audioOut[audioCount++] = smoothed.toInt().coerceIn(-32767, 32767).toShort()
            }
        }

        if (wbListener != null && wbCount > 0) {
            wbListener.invoke(if (wbCount == widebandBuf!!.size) widebandBuf else widebandBuf.copyOf(wbCount))
        }

        return if (audioCount == audioOut.size) audioOut else audioOut.copyOf(audioCount)
    }

    private fun tanh(x: Float): Float {
        // Fast tanh approximation
        if (x > 3f) return 1f
        if (x < -3f) return -1f
        val x2 = x * x
        return x * (27f + x2) / (27f + 9f * x2)
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

    /**
     * Measure signal quality by checking modulation activity.
     * Real FM stations have consistent modulation; noise does not.
     */
    fun measureSignalQuality(iqData: ByteArray): Float {
        if (iqData.size < 4) return 0f
        val numSamples = iqData.size / 2
        var prevI = 0f; var prevQ = 0f
        var phaseVariance = 0.0
        var phaseMean = 0.0
        var count = 0

        for (i in 0 until numSamples) {
            val iVal = (iqData[i * 2].toInt() and 0xFF) / 127.5f - 1f
            val qVal = (iqData[i * 2 + 1].toInt() and 0xFF) / 127.5f - 1f
            if (i > 0) {
                val realProd = iVal * prevI + qVal * prevQ
                val imagProd = qVal * prevI - iVal * prevQ
                val phase = atan2(imagProd, realProd)
                phaseMean += phase
                phaseVariance += phase * phase
                count++
            }
            prevI = iVal; prevQ = qVal
        }
        if (count == 0) return 0f
        phaseMean /= count
        phaseVariance = phaseVariance / count - phaseMean * phaseMean
        return phaseVariance.toFloat()
    }

    fun reset() {
        prevI = 0f; prevQ = 0f; deEmphasisState = 0f; dcI = 0f; dcQ = 0f
        ifBufI = FloatArray(ifLpfOrder); ifBufQ = FloatArray(ifLpfOrder); ifBufIdx = 0
        audioLpfBuf = FloatArray(audioLpfOrder); audioLpfIdx = 0
        stage1Counter = 0; stage2Counter = 0
        pilotPhase = 0.0; pilotEnergy = 0f; pilotSampleCount = 0; isStereo = false
        lastOutputSample = 0f
    }
}
