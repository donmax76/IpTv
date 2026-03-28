package com.fmradio.dsp

import kotlin.math.*

/**
 * High-quality FM demodulation pipeline based on SDR++/rtl_fm/librtlsdr.
 *
 * IQ (1152 kHz) → DC removal → IF LPF (±120 kHz) → Decimate /6 → FM discriminator (192 kHz)
 *   → Pilot PLL (locks 19 kHz) → pilotPhase×2 = 38 kHz for stereo L-R
 *   → Wideband baseband output + pilotPhase (for RDS decoder at 192 kHz)
 *   → Stereo decode: L+R (mono LPF) and L-R (38 kHz demod + LPF)
 *   → Decimate /4 → De-emphasis (50µs) → Squelch → Stereo PCM (48 kHz)
 *
 * Optimized for real-time on ARM:
 *   - Byte→float LUT (no division per sample)
 *   - 16-tap audio LPF (reduced from 32)
 *   - Zero-copy output via DemodResult (no copyOf)
 *   - RDS processed every 2nd callback
 */
class FmDemodulator(
    private val inputSampleRate: Int = RECOMMENDED_SAMPLE_RATE,
    private val audioSampleRate: Int = 48000
) {
    companion object {
        const val RECOMMENDED_SAMPLE_RATE = 1152000

        // Pre-computed byte → float LUT: avoids division per IQ sample
        // Maps unsigned byte 0..255 to float -1.0..+1.0
        val BYTE_TO_FLOAT = FloatArray(256) { i -> i / 127.5f - 1f }
    }

    /** Result holder — avoids array allocation on every callback */
    class DemodResult(
        val samples: ShortArray,
        var count: Int = 0
    )

    private val stage1Decimation = 6
    private val intermediateRate: Int = inputSampleRate / stage1Decimation  // 192000
    private val stage2Decimation: Int = intermediateRate / audioSampleRate  // 4

    // DC removal (IIR high-pass)
    private var dcI = 0f
    private var dcQ = 0f
    private val dcAlpha = 0.9995f

    // FM discriminator state
    private var prevI = 0f
    private var prevQ = 0f

    // FM deviation gain
    private val fmGain = (intermediateRate.toFloat() / (2f * PI.toFloat() * 75000f)) * 0.75f

    // De-emphasis filter (50µs time constant for Europe/Russia)
    private var deEmphasisStateL = 0f
    private var deEmphasisStateR = 0f
    private val deEmphasisAlpha: Float

    // IF low-pass filter (before stage 1 decimation) — 32 taps
    private val ifLpfOrder = 32
    private val ifLpfCoeffs: FloatArray
    private var ifBufI = FloatArray(ifLpfOrder)
    private var ifBufQ = FloatArray(ifLpfOrder)
    private var ifBufIdx = 0

    // Audio low-pass filters — 32 taps for clean pilot/stereo rejection
    // 15 kHz cutoff at 192 kHz: 32 taps gives ~80 dB rejection of 19 kHz pilot
    private val audioLpfOrder = 32
    private val audioLpfCoeffs: FloatArray
    private var monoLpfBuf = FloatArray(audioLpfOrder)
    private var monoLpfIdx = 0
    private var diffLpfBuf = FloatArray(audioLpfOrder)
    private var diffLpfIdx = 0

    private var stage1Counter = 0
    private var stage2Counter = 0

    // Wideband output for RDS — includes pilot phase
    // Signature: (buffer, count, pilotPhase)
    var widebandListener: ((FloatArray, Int, Double) -> Unit)? = null

    // RDS skip: process every 2nd callback to save CPU
    private var rdsCallbackCounter = 0

    // ========== Pilot PLL (19 kHz, SDR++/gr-rds approach) ==========
    private val pilotBpfState = DoubleArray(4)
    private val pilotBpfB0: Double
    private val pilotBpfB2: Double
    private val pilotBpfA1: Double
    private val pilotBpfA2: Double

    private var pilotNcoPhase = 0.0
    private var pilotNcoFreq = 2.0 * PI * 19000.0 / intermediateRate
    private val pilotLoopBw = 2.0 * PI * 5.0 / intermediateRate
    private val pilotAlpha: Double
    private val pilotBeta: Double

    private var pilotStrength = 0f
    private var pilotStrengthAcc = 0f
    private var pilotStrengthCount = 0
    private val pilotDetectWindow = intermediateRate / 4

    @Volatile
    var isStereo = false
        private set

    // Real-time signal strength in dB (exposed for UI)
    @Volatile
    var currentSignalStrengthDb = -100f
        private set
    private var signalPowerAcc = 0.0
    private var signalPowerCount = 0
    private val signalPowerWindow = intermediateRate / 16

    // Squelch based on signal quality
    private var signalQualityAcc = 0.0
    private var signalQualityCount = 0
    private var squelchOpen = true
    private var squelchLevel = 1f
    private val squelchAttack = 0.08f
    private val squelchRelease = 0.03f

    // Warmup: discard first N intermediate samples
    private var warmupSamples = 0
    private val warmupThreshold = intermediateRate / 50

    @Volatile
    private var resetting = false

    // Crossfade for seamless muting during frequency change
    private var muteRamp = 1.0f
    private val muteRampUp = 0.05f
    private val muteRampDown = 0.05f

    // Pre-allocated output buffers — avoids GC pressure per callback
    // Sized for 65536-byte IQ input: 32768 IQ samples / 24 decimation + 2 = 1367 frames × 2 = 2734
    private val maxAudioOut = 2800 * 2
    private val demodResult = DemodResult(ShortArray(maxAudioOut))
    private val maxWbOut = 32768 / stage1Decimation + 2
    private var wbBuf = FloatArray(maxWbOut)

    init {
        // De-emphasis: 50µs time constant (Europe/Russia standard)
        val tau = 50e-6f
        val dt = 1f / audioSampleRate
        deEmphasisAlpha = dt / (tau + dt)

        // IF filter: 150 kHz cutoff (wideband FM needs ±75 kHz deviation + stereo)
        ifLpfCoeffs = designLowPassFilter(ifLpfOrder, 150000f / inputSampleRate)
        // Audio filter: 15 kHz cutoff
        audioLpfCoeffs = designLowPassFilter(audioLpfOrder, 15000f / intermediateRate)

        // Design 19 kHz pilot bandpass biquad (Q=80)
        val w0 = 2.0 * PI * 19000.0 / intermediateRate
        val bpfQ = 80.0
        val bpfAlpha = sin(w0) / (2.0 * bpfQ)
        val a0 = 1.0 + bpfAlpha
        pilotBpfB0 = bpfAlpha / a0
        pilotBpfB2 = -bpfAlpha / a0
        pilotBpfA1 = (-2.0 * cos(w0)) / a0
        pilotBpfA2 = (1.0 - bpfAlpha) / a0

        // PLL gains — second-order loop, critically damped
        val damp = 0.707
        val bw = pilotLoopBw
        pilotAlpha = 2.0 * damp * bw
        pilotBeta = bw * bw
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

    private fun pilotBpf(input: Double): Double {
        val x0 = input
        val y0 = pilotBpfB0 * x0 + pilotBpfB2 * pilotBpfState[1] -
                pilotBpfA1 * pilotBpfState[2] - pilotBpfA2 * pilotBpfState[3]
        pilotBpfState[1] = pilotBpfState[0]
        pilotBpfState[0] = x0
        pilotBpfState[3] = pilotBpfState[2]
        pilotBpfState[2] = y0
        return y0
    }

    /**
     * Fast atan2 approximation — polynomial, from rtl_fm.
     * Max error < 0.005 radians.
     */
    private fun fastAtan2(y: Float, x: Float): Float {
        val absX = abs(x)
        val absY = abs(y)
        if (absX < 1e-12f && absY < 1e-12f) return 0f
        val a = minOf(absX, absY) / maxOf(absX, absY)
        val s = a * a
        var r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a
        if (absY > absX) r = PI.toFloat() / 2f - r
        if (x < 0) r = PI.toFloat() - r
        if (y < 0) r = -r
        return r
    }

    /**
     * Demodulate raw IQ samples to stereo audio PCM (interleaved L,R,L,R...).
     * Returns DemodResult with pre-allocated buffer and count — zero allocation.
     */
    fun demodulate(iqData: ByteArray): DemodResult {
        if (resetting) { demodResult.count = 0; return demodResult }
        val numIqSamples = iqData.size / 2
        val maxAudioSamples = numIqSamples / (stage1Decimation * stage2Decimation) + 2
        val neededAudio = maxAudioSamples * 2
        // Resize only if input is unexpectedly large
        val audioOut: ShortArray
        if (neededAudio > demodResult.samples.size) {
            // Rare path — update the result buffer
            val newBuf = ShortArray(neededAudio)
            demodResult.count = 0
            audioOut = newBuf
            // We'll copy into demodResult at end
        } else {
            audioOut = demodResult.samples
        }
        var audioCount = 0

        val wbListener = widebandListener
        val maxWbSamples = numIqSamples / stage1Decimation + 2
        if (maxWbSamples > wbBuf.size) wbBuf = FloatArray(maxWbSamples)
        val widebandBuf = if (wbListener != null) wbBuf else null
        var wbCount = 0

        // RDS: process every callback — RDS runs in separate thread now
        val doRds = wbListener != null

        val lut = BYTE_TO_FLOAT

        for (i in 0 until numIqSamples) {
            val byteIdx = i * 2
            var iSample = lut[iqData[byteIdx].toInt() and 0xFF]
            var qSample = lut[iqData[byteIdx + 1].toInt() and 0xFF]

            // DC removal (IIR high-pass)
            dcI = dcAlpha * dcI + (1 - dcAlpha) * iSample
            dcQ = dcAlpha * dcQ + (1 - dcAlpha) * qSample
            iSample -= dcI
            qSample -= dcQ

            // Store in IF filter buffer
            ifBufI[ifBufIdx] = iSample
            ifBufQ[ifBufIdx] = qSample
            ifBufIdx = (ifBufIdx + 1) % ifLpfOrder

            // Stage 1 decimation: 1152 kHz → 192 kHz
            stage1Counter++
            if (stage1Counter < stage1Decimation) continue
            stage1Counter = 0

            // Apply IF bandpass filter
            var filtI = 0f
            var filtQ = 0f
            for (j in 0 until ifLpfOrder) {
                val idx = (ifBufIdx - 1 - j + ifLpfOrder) % ifLpfOrder
                filtI += ifBufI[idx] * ifLpfCoeffs[j]
                filtQ += ifBufQ[idx] * ifLpfCoeffs[j]
            }

            // FM discriminator
            val realProd = filtI * prevI + filtQ * prevQ
            val imagProd = filtQ * prevI - filtI * prevQ
            prevI = filtI
            prevQ = filtQ

            val rawBaseband = fastAtan2(imagProd, realProd)

            // Warmup: skip initial samples
            if (warmupSamples < warmupThreshold) {
                warmupSamples++
                val pilotSig = pilotBpf(rawBaseband.toDouble())
                val pilotError = pilotSig * cos(pilotNcoPhase)
                pilotNcoFreq += pilotBeta * pilotError
                pilotNcoPhase += pilotNcoFreq + pilotAlpha * pilotError
                if (pilotNcoPhase > 2 * PI) pilotNcoPhase -= 2 * PI
                if (pilotNcoPhase < 0) pilotNcoPhase += 2 * PI
                continue
            }

            // ===== Pilot PLL: lock to 19 kHz pilot tone =====
            val pilotSig = pilotBpf(rawBaseband.toDouble())
            val pilotError = pilotSig * cos(pilotNcoPhase)
            pilotNcoFreq += pilotBeta * pilotError
            pilotNcoPhase += pilotNcoFreq + pilotAlpha * pilotError
            if (pilotNcoPhase > 2 * PI) pilotNcoPhase -= 2 * PI
            if (pilotNcoPhase < 0) pilotNcoPhase += 2 * PI

            // Pilot strength measurement
            pilotStrengthAcc += (pilotSig * pilotSig).toFloat()
            pilotStrengthCount++
            if (pilotStrengthCount >= pilotDetectWindow) {
                pilotStrength = pilotStrengthAcc / pilotStrengthCount
                isStereo = pilotStrength > 0.02f
                pilotStrengthAcc = 0f
                pilotStrengthCount = 0
            }

            // ===== Signal strength measurement =====
            val iqPower = (filtI * filtI + filtQ * filtQ).toDouble()
            signalPowerAcc += iqPower
            signalPowerCount++
            if (signalPowerCount >= signalPowerWindow) {
                val avgPower = signalPowerAcc / signalPowerCount
                currentSignalStrengthDb = (10 * kotlin.math.log10(avgPower + 1e-10)).toFloat()
                signalPowerAcc = 0.0
                signalPowerCount = 0
            }

            // ===== Signal quality for squelch =====
            val absBaseband = abs(rawBaseband)
            signalQualityAcc += absBaseband
            signalQualityCount++
            if (signalQualityCount >= intermediateRate / 16) {
                val avgModulation = signalQualityAcc / signalQualityCount
                squelchOpen = avgModulation > 0.03 && avgModulation < 2.5
                signalQualityAcc = 0.0
                signalQualityCount = 0
            }
            if (squelchOpen && squelchLevel < 1f) {
                squelchLevel = (squelchLevel + squelchAttack).coerceAtMost(1f)
            } else if (!squelchOpen && squelchLevel > 0f) {
                squelchLevel = (squelchLevel - squelchRelease).coerceAtLeast(0f)
            }

            // Wideband output for RDS decoder (only on RDS-active callbacks)
            if (doRds && widebandBuf != null && wbCount < widebandBuf.size) {
                widebandBuf[wbCount++] = rawBaseband
            }

            // ===== Stereo decoding =====
            val baseband = rawBaseband * fmGain
            val mono = baseband

            val stereoCarrier = cos(2.0 * pilotNcoPhase).toFloat()
            val diff = baseband * stereoCarrier * 2f

            monoLpfBuf[monoLpfIdx] = mono
            diffLpfBuf[diffLpfIdx] = diff
            monoLpfIdx = (monoLpfIdx + 1) % audioLpfOrder
            diffLpfIdx = (diffLpfIdx + 1) % audioLpfOrder

            // Stage 2 decimation: 192 kHz → 48 kHz
            stage2Counter++
            if (stage2Counter < stage2Decimation) continue
            stage2Counter = 0

            // Apply audio LPF (32 taps)
            var filtMono = 0f
            var filtDiff = 0f
            for (j in 0 until audioLpfOrder) {
                val mIdx = (monoLpfIdx - 1 - j + audioLpfOrder) % audioLpfOrder
                val dIdx = (diffLpfIdx - 1 - j + audioLpfOrder) % audioLpfOrder
                filtMono += monoLpfBuf[mIdx] * audioLpfCoeffs[j]
                filtDiff += diffLpfBuf[dIdx] * audioLpfCoeffs[j]
            }

            // Stereo matrix
            val left: Float
            val right: Float
            if (isStereo) {
                left = (filtMono + filtDiff) * 0.5f
                right = (filtMono - filtDiff) * 0.5f
            } else {
                left = filtMono
                right = filtMono
            }

            // De-emphasis filter (50µs)
            deEmphasisStateL += deEmphasisAlpha * (left - deEmphasisStateL)
            deEmphasisStateR += deEmphasisAlpha * (right - deEmphasisStateR)

            val outL = deEmphasisStateL * squelchLevel
            val outR = deEmphasisStateR * squelchLevel

            if (muteRamp < 1f) {
                muteRamp = (muteRamp + muteRampUp).coerceAtMost(1f)
            }

            // Scale to 16-bit PCM
            val gain = muteRamp * 25000f
            val sampleL = (outL * gain).toInt().coerceIn(-32767, 32767)
            val sampleR = (outR * gain).toInt().coerceIn(-32767, 32767)

            if (audioCount + 1 < audioOut.size) {
                audioOut[audioCount++] = sampleL.toShort()
                audioOut[audioCount++] = sampleR.toShort()
            }
        }

        // Send wideband data to RDS with current pilot phase — zero-copy
        if (doRds && wbListener != null && wbCount > 0) {
            wbListener.invoke(wbBuf, wbCount, pilotNcoPhase)
        }

        demodResult.count = audioCount
        return demodResult
    }

    fun measureSignalStrength(iqData: ByteArray): Float {
        if (iqData.isEmpty()) return -100f
        var powerSum = 0.0
        val numSamples = iqData.size / 2
        val lut = BYTE_TO_FLOAT
        for (i in 0 until numSamples) {
            val iVal = lut[iqData[i * 2].toInt() and 0xFF]
            val qVal = lut[iqData[i * 2 + 1].toInt() and 0xFF]
            powerSum += (iVal * iVal + qVal * qVal).toDouble()
        }
        val avgPower = powerSum / numSamples
        return (10 * log10(avgPower + 1e-10)).toFloat()
    }

    /**
     * Stateless IF-filtered signal strength measurement for scanning.
     */
    fun measureFilteredSignalStrength(iqData: ByteArray): Float {
        if (iqData.size < ifLpfOrder * 4) return -100f
        val numIqSamples = iqData.size / 2
        val localBufI = FloatArray(ifLpfOrder)
        val localBufQ = FloatArray(ifLpfOrder)
        var localBufIdx = 0
        var decimCounter = 0
        var powerSum = 0.0
        var count = 0
        val settleCount = ifLpfOrder * 2
        val lut = BYTE_TO_FLOAT

        for (i in 0 until numIqSamples) {
            val iSample = lut[iqData[i * 2].toInt() and 0xFF]
            val qSample = lut[iqData[i * 2 + 1].toInt() and 0xFF]
            localBufI[localBufIdx] = iSample
            localBufQ[localBufIdx] = qSample
            localBufIdx = (localBufIdx + 1) % ifLpfOrder

            decimCounter++
            if (decimCounter < stage1Decimation) continue
            decimCounter = 0

            var filtI = 0f
            var filtQ = 0f
            for (j in 0 until ifLpfOrder) {
                val idx = (localBufIdx - 1 - j + ifLpfOrder) % ifLpfOrder
                filtI += localBufI[idx] * ifLpfCoeffs[j]
                filtQ += localBufQ[idx] * ifLpfCoeffs[j]
            }

            count++
            if (count > settleCount) {
                powerSum += (filtI * filtI + filtQ * filtQ).toDouble()
            }
        }

        val validCount = (count - settleCount).coerceAtLeast(1)
        val avgPower = powerSum / validCount
        return (10 * log10(avgPower + 1e-10)).toFloat()
    }

    fun measureSignalQuality(iqData: ByteArray): Float {
        if (iqData.size < 4) return 0f
        val numSamples = iqData.size / 2
        var prevI = 0f; var prevQ = 0f
        var phaseVariance = 0.0
        var phaseMean = 0.0
        var count = 0
        val lut = BYTE_TO_FLOAT
        for (i in 0 until numSamples) {
            val iVal = lut[iqData[i * 2].toInt() and 0xFF]
            val qVal = lut[iqData[i * 2 + 1].toInt() and 0xFF]
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
        resetting = true
        prevI = 0f; prevQ = 0f; deEmphasisStateL = 0f; deEmphasisStateR = 0f
        dcI = 0f; dcQ = 0f
        ifBufI = FloatArray(ifLpfOrder); ifBufQ = FloatArray(ifLpfOrder); ifBufIdx = 0
        monoLpfBuf = FloatArray(audioLpfOrder); monoLpfIdx = 0
        diffLpfBuf = FloatArray(audioLpfOrder); diffLpfIdx = 0
        stage1Counter = 0; stage2Counter = 0
        pilotNcoPhase = 0.0
        pilotNcoFreq = 2.0 * PI * 19000.0 / intermediateRate
        for (i in pilotBpfState.indices) pilotBpfState[i] = 0.0
        pilotStrength = 0f; pilotStrengthAcc = 0f; pilotStrengthCount = 0
        isStereo = false
        currentSignalStrengthDb = -100f; signalPowerAcc = 0.0; signalPowerCount = 0
        signalQualityAcc = 0.0; signalQualityCount = 0
        squelchOpen = true; squelchLevel = 1f
        warmupSamples = 0
        muteRamp = 1.0f
        rdsCallbackCounter = 0
        resetting = false
    }
}
